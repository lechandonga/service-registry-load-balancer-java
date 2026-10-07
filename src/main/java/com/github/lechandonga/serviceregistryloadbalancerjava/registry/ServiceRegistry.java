package com.github.lechandonga.serviceregistryloadbalancerjava.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 纯内存的服务注册中心。
 * <p>一致性模型：每个服务名一把锁，注册、续约、注销、租约扫描、探测结果落账、快照构建
 * 都在同一把锁内完成，因此「注册 / 查询 / 选路」三个入口对实例状态的结论永远一致。
 * 选路只通过 {@link EligibleSnapshot} 读取实例，快照不可变，摘除生效后旧快照也不会复活实例。</p>
 */
public class ServiceRegistry {

    private static final Logger log = LoggerFactory.getLogger(ServiceRegistry.class);

    private final Map<String, ServiceState> services = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private final long defaultTtlMillis;

    public ServiceRegistry(LongSupplier clock, long defaultTtlMillis) {
        this.clock = clock;
        this.defaultTtlMillis = defaultTtlMillis;
    }

    private ServiceState stateOf(String serviceName) {
        return services.computeIfAbsent(serviceName, key -> new ServiceState());
    }

    private long now() {
        return clock.getAsLong();
    }

    /**
     * 注册实例。
     *
     * @return 本次注册实际生效的租约 TTL（毫秒）
     * @throws RegistryException 重复注册（ALREADY_REGISTERED）或参数非法（INVALID_REQUEST）
     */
    public long register(String serviceName, String instanceId, String host, int port,
                         int weight, Long ttlMillis) {
        validateEndpoint(serviceName, instanceId, host, port);
        if (weight < 1) {
            throw new RegistryException(RegistryErrorCode.INVALID_REQUEST,
                    "weight must be >= 1, got " + weight);
        }
        long ttl = (ttlMillis == null || ttlMillis <= 0) ? defaultTtlMillis : ttlMillis;
        long now = now();
        ServiceState state = stateOf(serviceName);
        synchronized (state.lock) {
            InstanceRecord existing = state.instances.get(instanceId);
            if (existing != null && existing.getState() == InstanceState.ONLINE
                    && !existing.leaseExpired(now)) {
                throw new RegistryException(RegistryErrorCode.ALREADY_REGISTERED,
                        "instance '" + instanceId + "' of service '" + serviceName
                                + "' is already registered with a live lease (last heartbeat "
                                + existing.getLastHeartbeat() + ")");
            }
            if (existing != null) {
                existing.setRegisteredAt(now);
                existing.setLastHeartbeat(now);
                existing.setLeaseTtlMillis(ttl);
                InstanceState previous = existing.getState();
                existing.setState(InstanceState.ONLINE);
                existing.setServing(true);
                existing.setConsecutiveFailures(0);
                existing.setConsecutiveSuccesses(0);
                existing.setWeight(weight);
                existing.appendEvent(now, InstanceEventType.REGISTERED,
                        "re-registered after previous state " + previous
                                + " on " + host + ":" + port + ", ttl=" + ttl + "ms");
                log.info("register(re-activate) service={} instance={} address={}:{} weight={} ttl={}ms",
                        serviceName, instanceId, host, port, weight, ttl);
            } else {
                InstanceRecord record = new InstanceRecord(serviceName, instanceId, host, port,
                        weight, now, ttl);
                record.appendEvent(now, InstanceEventType.REGISTERED,
                        "registered on " + host + ":" + port + ", weight=" + weight
                                + ", ttl=" + ttl + "ms");
                state.instances.put(instanceId, record);
                log.info("register service={} instance={} address={}:{} weight={} ttl={}ms",
                        serviceName, instanceId, host, port, weight, ttl);
            }
            rebuildSnapshot(state);
            return ttl;
        }
    }

    /** 周期续约（心跳）。不存在、已注销、已过期的实例一律拒绝。 */
    public void renew(String serviceName, String instanceId) {
        requireText(serviceName, "serviceName");
        requireText(instanceId, "instanceId");
        long now = now();
        ServiceState state = services.get(serviceName);
        if (state == null) {
            throw notFound(serviceName, instanceId);
        }
        synchronized (state.lock) {
            InstanceRecord record = state.instances.get(instanceId);
            if (record == null || record.getState() == InstanceState.DEREGISTERED) {
                throw notFound(serviceName, instanceId);
            }
            if (record.getState() == InstanceState.EXPIRED || record.leaseExpired(now)) {
                throw new RegistryException(RegistryErrorCode.LEASE_EXPIRED,
                        "renew rejected: lease of instance '" + instanceId + "' service '"
                                + serviceName + "' expired at heartbeat "
                                + record.getLastHeartbeat() + " (ttl "
                                + record.getLeaseTtlMillis() + "ms, now " + now + ")");
            }
            record.setLastHeartbeat(now);
            record.appendEvent(now, InstanceEventType.RENEWED,
                    "heartbeat renewed, ttl=" + record.getLeaseTtlMillis() + "ms");
            log.debug("renew service={} instance={}", serviceName, instanceId);
        }
    }

    /** 主动注销。实例不存在（含已注销、从未注册）时拒绝。 */
    public void deregister(String serviceName, String instanceId) {
        requireText(serviceName, "serviceName");
        requireText(instanceId, "instanceId");
        long now = now();
        ServiceState state = services.get(serviceName);
        if (state == null) {
            throw notFound(serviceName, instanceId);
        }
        synchronized (state.lock) {
            InstanceRecord record = state.instances.get(instanceId);
            if (record == null || record.getState() != InstanceState.ONLINE) {
                throw notFound(serviceName, instanceId);
            }
            record.setState(InstanceState.DEREGISTERED);
            record.setServing(false);
            record.appendEvent(now, InstanceEventType.DEREGISTERED, "instance deregistered");
            log.info("deregister service={} instance={}", serviceName, instanceId);
            rebuildSnapshot(state);
        }
    }

    /** 调整在线实例权重，摘除 / 过期 / 注销的实例拒绝调整。 */
    public void setWeight(String serviceName, String instanceId, int newWeight) {
        if (newWeight < 1) {
            throw new RegistryException(RegistryErrorCode.INVALID_REQUEST,
                    "weight must be >= 1, got " + newWeight);
        }
        long now = now();
        ServiceState state = services.get(serviceName);
        if (state == null) {
            throw notFound(serviceName, instanceId);
        }
        synchronized (state.lock) {
            InstanceRecord record = state.instances.get(instanceId);
            if (record == null || record.getState() == InstanceState.DEREGISTERED) {
                throw notFound(serviceName, instanceId);
            }
            if (record.getState() == InstanceState.EXPIRED || record.leaseExpired(now)) {
                throw new RegistryException(RegistryErrorCode.LEASE_EXPIRED,
                        "setWeight rejected: lease of '" + instanceId + "' expired");
            }
            int old = record.getWeight();
            record.setWeight(newWeight);
            record.appendEvent(now, InstanceEventType.WEIGHT_CHANGED,
                    "weight " + old + " -> " + newWeight);
            log.info("weight change service={} instance={} {} -> {}",
                    serviceName, instanceId, old, newWeight);
            rebuildSnapshot(state);
        }
    }

    /**
     * 租约扫描：超过 TTL 未续约的 ONLINE 实例标记为 EXPIRED 并立即从可路由快照摘除。
     *
     * @return 本次被判定过期的实例视图
     */
    public List<InstanceView> expireLeases() {
        long now = now();
        List<InstanceView> expired = new ArrayList<>();
        for (Map.Entry<String, ServiceState> entry : services.entrySet()) {
            String serviceName = entry.getKey();
            ServiceState state = entry.getValue();
            synchronized (state.lock) {
                boolean changed = false;
                for (InstanceRecord record : state.instances.values()) {
                    if (record.getState() == InstanceState.ONLINE && record.leaseExpired(now)) {
                        record.setState(InstanceState.EXPIRED);
                        record.setServing(false);
                        record.appendEvent(now, InstanceEventType.LEASE_EXPIRED,
                                "no heartbeat for " + (now - record.getLastHeartbeat())
                                        + "ms (ttl " + record.getLeaseTtlMillis() + "ms)");
                        expired.add(new InstanceView(serviceName, record.getInstanceId(),
                                record.getHost(), record.getPort(), record.getWeight()));
                        changed = true;
                        log.warn("lease expired service={} instance={} lastHeartbeat={} ttl={}ms",
                                serviceName, record.getInstanceId(),
                                record.getLastHeartbeat(), record.getLeaseTtlMillis());
                    }
                }
                if (changed) {
                    rebuildSnapshot(state);
                }
            }
        }
        return expired;
    }

    /**
     * 探测结果落账：连续失败达到阈值才摘除（serving=false），摘除后连续成功达到阈值才恢复。
     * 短暂抖动（次数未到阈值）不改变 serving，实例不会反复上下线。
     *
     * @return true 表示本次结果导致了摘除 / 恢复的状态翻转
     */
    public boolean recordProbeResult(String serviceName, String instanceId, boolean healthy,
                                     int failureThreshold, int successThreshold) {
        long now = now();
        ServiceState state = services.get(serviceName);
        if (state == null) {
            return false;
        }
        synchronized (state.lock) {
            InstanceRecord record = state.instances.get(instanceId);
            if (record == null || record.getState() != InstanceState.ONLINE
                    || record.leaseExpired(now)) {
                return false;
            }
            boolean flipped = false;
            if (healthy) {
                record.setConsecutiveFailures(0);
                record.setConsecutiveSuccesses(record.getConsecutiveSuccesses() + 1);
                if (!record.isServing()
                        && record.getConsecutiveSuccesses() >= successThreshold) {
                    record.setServing(true);
                    record.appendEvent(now, InstanceEventType.HEALTH_RESUMED,
                            "resumed after " + record.getConsecutiveSuccesses()
                                    + " consecutive probe successes");
                    flipped = true;
                    log.warn("instance resumed service={} instance={} consecutiveSuccesses={}",
                            serviceName, instanceId, record.getConsecutiveSuccesses());
                }
            } else {
                record.setConsecutiveSuccesses(0);
                record.setConsecutiveFailures(record.getConsecutiveFailures() + 1);
                if (record.isServing()
                        && record.getConsecutiveFailures() >= failureThreshold) {
                    record.setServing(false);
                    record.appendEvent(now, InstanceEventType.HEALTH_SUSPENDED,
                            "suspended after " + record.getConsecutiveFailures()
                                    + " consecutive probe failures");
                    flipped = true;
                    log.warn("instance suspended service={} instance={} consecutiveFailures={}",
                            serviceName, instanceId, record.getConsecutiveFailures());
                }
            }
            if (flipped) {
                rebuildSnapshot(state);
            }
            return flipped;
        }
    }


    /**
     * 在某服务的锁保护下执行选路函数：读快照与选路选择和注册/注销/探测落账完全串行，
     * 保证选路结论不可能观察到「已被摘除但仍返回」的实例（线性一致）。
     */
    public <T> T withServiceLock(String serviceName, java.util.function.Function<EligibleSnapshot, T> action) {
        ServiceState state = stateOf(serviceName);
        synchronized (state.lock) {
            return action.apply(state.snapshot.get());
        }
    }

    /** 获取服务当前的可路由快照（不可变），选路入口只依赖它。 */
    public EligibleSnapshot snapshot(String serviceName) {
        ServiceState state = services.get(serviceName);
        return state == null ? EligibleSnapshot.empty(0) : state.snapshot.get();
    }

    /** 查询单个实例的完整记录（含状态与事件历史）。不存在返回 null。 */
    public InstanceRecord findRecord(String serviceName, String instanceId) {
        ServiceState state = services.get(serviceName);
        if (state == null) {
            return null;
        }
        synchronized (state.lock) {
            return state.instances.get(instanceId);
        }
    }

    /** 列出某服务下所有实例记录（含过期 / 已注销），保持注册顺序。 */
    public List<InstanceRecord> listRecords(String serviceName) {
        ServiceState state = services.get(serviceName);
        if (state == null) {
            return List.of();
        }
        synchronized (state.lock) {
            return List.copyOf(state.instances.values());
        }
    }

    /** 供探测任务枚举：所有 ONLINE 实例（含当前被健康摘除、等待恢复的实例）。 */
    public List<InstanceRecord> listOnlineRecords() {
        List<InstanceRecord> all = new ArrayList<>();
        for (Map.Entry<String, ServiceState> entry : services.entrySet()) {
            ServiceState state = entry.getValue();
            synchronized (state.lock) {
                for (InstanceRecord record : state.instances.values()) {
                    if (record.getState() == InstanceState.ONLINE) {
                        all.add(record);
                    }
                }
            }
        }
        return all;
    }

    /** 在持有服务锁的前提下重建可路由快照：ONLINE、租约未过期、serving=true。 */
    private void rebuildSnapshot(ServiceState state) {
        long now = now();
        List<InstanceView> views = new ArrayList<>();
        for (InstanceRecord record : state.instances.values()) {
            if (record.getState() == InstanceState.ONLINE
                    && !record.leaseExpired(now) && record.isServing()) {
                views.add(new InstanceView(record.getServiceName(), record.getInstanceId(),
                        record.getHost(), record.getPort(), record.getWeight()));
            }
        }
        EligibleSnapshot previous = state.snapshot.get();
        if (eligibleChanged(previous.instances(), views)) {
            state.version++;
            state.snapshot.set(new EligibleSnapshot(state.version, views));
        }
    }

    private static boolean eligibleChanged(List<InstanceView> oldViews, List<InstanceView> views) {
        if (oldViews.size() != views.size()) {
            return true;
        }
        for (int i = 0; i < views.size(); i++) {
            InstanceView oldView = oldViews.get(i);
            InstanceView view = views.get(i);
            if (!oldView.instanceId().equals(view.instanceId()) || oldView.weight() != view.weight()) {
                return true;
            }
        }
        return false;
    }

    private static RegistryException notFound(String serviceName, String instanceId) {
        return new RegistryException(RegistryErrorCode.INSTANCE_NOT_FOUND,
                "no live instance '" + instanceId + "' of service '" + serviceName
                        + "' (never registered, already deregistered, or removed)");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new RegistryException(RegistryErrorCode.INVALID_REQUEST,
                    field + " must not be blank");
        }
    }

    private static void validateEndpoint(String serviceName, String instanceId,
                                         String host, int port) {
        requireText(serviceName, "serviceName");
        requireText(instanceId, "instanceId");
        requireText(host, "host");
        if (port < 1 || port > 65535) {
            throw new RegistryException(RegistryErrorCode.INVALID_REQUEST,
                    "port must be in [1,65535], got " + port);
        }
    }
}
