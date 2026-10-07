package com.github.lechandonga.serviceregistryloadbalancerjava.lb;

import com.github.lechandonga.serviceregistryloadbalancerjava.registry.EligibleSnapshot;
import com.github.lechandonga.serviceregistryloadbalancerjava.registry.InstanceView;
import com.github.lechandonga.serviceregistryloadbalancerjava.registry.ServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * 负载均衡路由器：从注册中心读取不可变快照做选路，支持平滑加权轮询与一致性哈希，
 * 并在调用方报告实例不健康时做有上限的重试 / 转移。
 * <p>并发安全：取快照与推进游标 / 环在同一把服务锁内完成，选路拿到的必然是当前最新快照，
 * 因此探测摘除、注销与选路并发时，绝不可能选中已经摘除的实例；快照本身不可变，
 * 一次选路过程中候选集合也保持一致。</p>
 */
public class Router {

    private static final Logger log = LoggerFactory.getLogger(Router.class);

    /** 每个实例在哈希环上的虚拟节点倍数（按权重），节点越少时分散越均匀。 */
    private static final int VNODE_FACTOR = 160;

    private final ServiceRegistry registry;
    private final Map<String, RoundRobinState> rrStates = new ConcurrentHashMap<>();
    private final Map<String, HashRing> rings = new ConcurrentHashMap<>();

    public Router(ServiceRegistry registry) {
        this.registry = registry;
    }

    /**
     * 选路。
     *
     * @param serviceName  服务名
     * @param strategy     策略
     * @param requestKey   一致性哈希的请求键（轮询策略可传 null）
     * @param healthyProbe 调用方对候选实例的健康判定（例如真实连接尝试）；返回 false 则触发转移
     * @param maxAttempts  最大尝试次数（含首次），防止在故障实例上无限打转
     */
    public RoutingDecision route(String serviceName, Strategy strategy, String requestKey,
                                 Predicate<InstanceView> healthyProbe, int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        List<RoutingAttempt> attempts = new ArrayList<>();
        Set<String> skipped = new HashSet<>();

        // 首次选择在注册中心的服务锁内完成：与注册/注销/探测落账互斥，
        // 选路返回的实例必然仍在当前快照中，不可能选中已摘除实例。
        PickResult firstPick = registry.withServiceLock(serviceName, snap -> {
            if (snap.instances().isEmpty()) {
                return PickResult.empty(snap);
            }
            return PickResult.of(snap, pickOne(serviceName, strategy, requestKey, snap, skipped));
        });
        EligibleSnapshot snapshot = firstPick.snapshot();
        if (snapshot.instances().isEmpty()) {
            String reason = "no eligible instance for service '" + serviceName
                    + "' (snapshot v" + snapshot.version() + ")";
            log.info("route miss service={} strategy={} reason={}", serviceName, strategy, reason);
            return new RoutingDecision(serviceName, strategy, requestKey, snapshot.version(),
                    snapshot.instances(), attempts, null, reason);
        }
        InstanceView first = firstPick.view();

        InstanceView selected = null;
        if (healthyProbe == null || first == null || healthyProbe.test(first)) {
            if (first != null) {
                attempts.add(new RoutingAttempt(first, true, "selected by " + strategy));
                selected = first;
            }
        } else {
            skipped.add(first.instanceId());
            attempts.add(new RoutingAttempt(first, false,
                    "reported unhealthy by caller, failover (1/" + bound(maxAttempts, snapshot) + ")"));
            selected = continueFailover(serviceName, strategy, requestKey, healthyProbe,
                    maxAttempts, snapshot, skipped, attempts);
        }

        String explanation;
        if (strategy == Strategy.CONSISTENT_HASH) {
            String key = requestKey == null ? "" : requestKey;
            explanation = selected == null
                    ? "key '" + key + "': " + attempts.size()
                        + " instance(s) on the ring walk were unhealthy; failover budget "
                        + maxAttempts + " exhausted"
                    : "key '" + key + "' hashes to " + selected.instanceId() + " after "
                        + attempts.size() + " attempt(s); capped at " + maxAttempts;
        } else {
            explanation = selected == null
                    ? "all " + attempts.size() + " tried instance(s) were unhealthy; failover budget "
                        + maxAttempts + " exhausted"
                    : "round-robin selected " + selected.instanceId() + " after "
                        + attempts.size() + " attempt(s); capped at " + maxAttempts;
        }

        if (selected != null) {
            log.info("route hit service={} strategy={} instance={} attempts={}/{} snapshot=v{}",
                    serviceName, strategy, selected.instanceId(), attempts.size(),
                    maxAttempts, snapshot.version());
        } else {
            log.warn("route exhausted service={} strategy={} attempts={}/{} snapshot=v{}",
                    serviceName, strategy, attempts.size(), maxAttempts, snapshot.version());
        }
        return new RoutingDecision(serviceName, strategy, requestKey, snapshot.version(),
                snapshot.instances(), attempts, selected, explanation);
    }

    private static int bound(int maxAttempts, EligibleSnapshot snapshot) {
        return Math.min(maxAttempts, snapshot.instances().size());
    }

    private InstanceView continueFailover(String serviceName, Strategy strategy, String key,
                                          Predicate<InstanceView> healthyProbe, int maxAttempts,
                                          EligibleSnapshot initialSnapshot, Set<String> skipped,
                                          List<RoutingAttempt> attempts) {
        EligibleSnapshot snapshot = initialSnapshot;
        int budget = bound(maxAttempts, snapshot);
        while (attempts.size() < budget) {
            // 每次转移都在服务锁内重新读取最新快照：并发摘除的实例不会成为转移目标。
            PickResult result = registry.withServiceLock(serviceName,
                    snap -> PickResult.of(snap, pickOne(serviceName, strategy, key, snap, skipped)));
            snapshot = result.snapshot();
            budget = bound(maxAttempts, snapshot);
            InstanceView candidate = result.view();
            if (candidate == null) {
                break;
            }
            if (healthyProbe.test(candidate)) {
                attempts.add(new RoutingAttempt(candidate, true, "failover target accepted"));
                return candidate;
            }
            skipped.add(candidate.instanceId());
            attempts.add(new RoutingAttempt(candidate, false,
                    "reported unhealthy by caller, failover ("
                            + (attempts.size() + 1) + "/" + budget + ")"));
        }
        return null;
    }

    /** 锁内选路结果，同时携带当时的快照版本用于解释。 */
    private record PickResult(EligibleSnapshot snapshot, InstanceView view) {
        static PickResult empty(EligibleSnapshot snapshot) {
            return new PickResult(snapshot, null);
        }

        static PickResult of(EligibleSnapshot snapshot, InstanceView view) {
            return new PickResult(snapshot, view);
        }
    }

    private InstanceView pickOne(String serviceName, Strategy strategy, String key,
                                 EligibleSnapshot snapshot, Set<String> excludeIds) {
        if (strategy == Strategy.CONSISTENT_HASH) {
            HashRing ring = rings.computeIfAbsent(serviceName, ignored -> new HashRing());
            return ring.pick(snapshot.instances(), snapshot.version(),
                    key == null ? "" : key, excludeIds);
        }
        RoundRobinState state = rrStates.computeIfAbsent(serviceName, ignored -> new RoundRobinState());
        return state.pick(snapshot.instances(), snapshot.version(), excludeIds);
    }

    /**
     * 平滑加权轮询状态（Nginx smooth weighted round-robin）。
     * currentWeight 基于当前快照维护；快照版本变化时按新实例集合重置，
     * 保证可用实例既不会被跳过也不会在一轮内被重复命中。
     */
    private static final class RoundRobinState {
        private long stateVersion = -1;
        private final List<WrrEntry> entries = new ArrayList<>();

        InstanceView pick(List<InstanceView> candidates, long version, Set<String> excludeIds) {
            if (stateVersion != version) {
                entries.clear();
                for (InstanceView view : candidates) {
                    entries.add(new WrrEntry(view));
                }
                stateVersion = version;
            }
            WrrEntry best = null;
            int total = 0;
            for (WrrEntry entry : entries) {
                total += entry.effectiveWeight;
                entry.currentWeight += entry.effectiveWeight;
                if (!excludeIds.contains(entry.view.instanceId())
                        && (best == null || entry.currentWeight > best.currentWeight)) {
                    best = entry;
                }
            }
            if (best == null) {
                return null;
            }
            best.currentWeight -= total;
            return best.view;
        }
    }

    private static final class WrrEntry {
        private final InstanceView view;
        private final int effectiveWeight;
        private int currentWeight;

        private WrrEntry(InstanceView view) {
            this.view = view;
            this.effectiveWeight = view.weight();
        }
    }

    /**
     * 一致性哈希环：FNV-1a 64 位哈希，每实例 weight * VNODE_FACTOR 个虚拟节点。
     * 只在快照版本变化时重建环，因此少量实例上下线时，除受影响键外其余键归属保持稳定。
     */
    static final class HashRing {
        private long ringVersion = -1;
        // 每个槽位按遍历顺序保存落在该哈希上的全部虚拟节点，避免哈希碰撞时互相覆盖。
        private final TreeMap<Long, List<InstanceView>> ring = new TreeMap<>();

        InstanceView pick(List<InstanceView> candidates, long version,
                          String key, Set<String> excludeIds) {
            if (ringVersion != version) {
                ring.clear();
                for (InstanceView view : candidates) {
                    int vnodes = Math.max(1, view.weight()) * VNODE_FACTOR;
                    for (int i = 0; i < vnodes; i++) {
                        ring.computeIfAbsent(fnv1a64(view.instanceId() + "#" + i),
                                h -> new ArrayList<>()).add(view);
                    }
                }
                ringVersion = version;
            }
            long hash = fnv1a64(key);
            // 从 hash 点顺时针走一整圈，找第一个未被排除的实例。
            for (List<InstanceView> slot : ring.tailMap(hash, true).values()) {
                InstanceView view = firstNotExcluded(slot, excludeIds);
                if (view != null) {
                    return view;
                }
            }
            for (List<InstanceView> slot : ring.headMap(hash, true).values()) {
                InstanceView view = firstNotExcluded(slot, excludeIds);
                if (view != null) {
                    return view;
                }
            }
            return null;
        }

        private static InstanceView firstNotExcluded(List<InstanceView> slot,
                                                     Set<String> excludeIds) {
            for (InstanceView view : slot) {
                if (!excludeIds.contains(view.instanceId())) {
                    return view;
                }
            }
            return null;
        }
    }

    /** FNV-1a 64 位哈希。 */
    static long fnv1a64(String input) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : input.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xff);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
