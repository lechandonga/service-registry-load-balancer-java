package com.github.lechandonga.serviceregistryloadbalancerjava.core;

import com.github.lechandonga.serviceregistryloadbalancerjava.error.RegistryException;
import com.github.lechandonga.serviceregistryloadbalancerjava.model.InstanceStatus;
import com.github.lechandonga.serviceregistryloadbalancerjava.model.ReasonCode;
import com.github.lechandonga.serviceregistryloadbalancerjava.model.RegistryEvent;
import com.github.lechandonga.serviceregistryloadbalancerjava.model.ServiceInstance;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ServiceRegistry {
    private static final int EVENT_LIMIT = 500;

    private final Map<String, ServiceInstance> instances = new TreeMap<>();
    private final ArrayDeque<RegistryEvent> events = new ArrayDeque<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Clock clock;

    public ServiceRegistry() {
        this(Clock.systemUTC());
    }

    public ServiceRegistry(Clock clock) {
        this.clock = clock;
    }

    public ServiceInstance register(
            String serviceName,
            String instanceId,
            String host,
            int port,
            int weight,
            long ttlMillis
    ) {
        validateText(serviceName, "serviceName");
        validateText(instanceId, "instanceId");
        validateText(host, "host");
        if (port < 1 || port > 65535) {
            throw new RegistryException(ReasonCode.INVALID_REQUEST, "port must be between 1 and 65535");
        }
        if (weight < 1) {
            throw new RegistryException(ReasonCode.INVALID_REQUEST, "weight must be positive");
        }
        if (ttlMillis < 1) {
            throw new RegistryException(ReasonCode.INVALID_REQUEST, "ttlMillis must be positive");
        }
        if (!isLoopback(host)) {
            throw new RegistryException(ReasonCode.INVALID_REQUEST, "only local loopback hosts are supported");
        }

        lock.writeLock().lock();
        try {
            String key = key(serviceName, instanceId);
            ServiceInstance existing = instances.get(key);
            if (existing != null) {
                if (existing.status() == InstanceStatus.EXPIRED) {
                    throw new RegistryException(ReasonCode.INSTANCE_EXPIRED,
                            "instance already expired; register a new instanceId");
                }
                if (existing.status() == InstanceStatus.DEREGISTERED) {
                    throw new RegistryException(ReasonCode.INSTANCE_DEREGISTERED,
                            "instance was deregistered; register a new instanceId");
                }
                throw new RegistryException(ReasonCode.INSTANCE_ALREADY_REGISTERED,
                        "instance is already registered");
            }

            Instant now = Instant.now(clock);
            ServiceInstance instance = new ServiceInstance(
                    serviceName, instanceId, host, port, weight,
                    InstanceStatus.ACTIVE, ttlMillis, now, now, now, 0, 0
            );
            instances.put(key, instance);
            addEvent(serviceName, instanceId, null, InstanceStatus.ACTIVE, "instance registered");
            return instance;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public ServiceInstance renew(String serviceName, String instanceId) {
        validateText(serviceName, "serviceName");
        validateText(instanceId, "instanceId");

        lock.writeLock().lock();
        try {
            ServiceInstance existing = requireExisting(serviceName, instanceId);
            if (existing.status() == InstanceStatus.EXPIRED) {
                throw new RegistryException(ReasonCode.INSTANCE_EXPIRED,
                        "cannot renew an expired instance");
            }
            if (existing.status() == InstanceStatus.DEREGISTERED) {
                throw new RegistryException(ReasonCode.INSTANCE_DEREGISTERED,
                        "cannot renew a deregistered instance");
            }

            Instant now = Instant.now(clock);
            ServiceInstance renewed = new ServiceInstance(
                    existing.serviceName(), existing.instanceId(), existing.host(), existing.port(),
                    existing.weight(), existing.status(), existing.ttlMillis(), existing.registeredAt(),
                    now, existing.lastStatusChangedAt(), existing.consecutiveFailures(),
                    existing.consecutiveSuccesses()
            );
            instances.put(key(serviceName, instanceId), renewed);
            addEvent(serviceName, instanceId, existing.status(), existing.status(), "lease renewed");
            return renewed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public ServiceInstance deregister(String serviceName, String instanceId) {
        validateText(serviceName, "serviceName");
        validateText(instanceId, "instanceId");

        lock.writeLock().lock();
        try {
            ServiceInstance existing = requireExisting(serviceName, instanceId);
            if (existing.status() == InstanceStatus.EXPIRED) {
                throw new RegistryException(ReasonCode.INSTANCE_EXPIRED,
                        "cannot deregister an expired instance");
            }
            if (existing.status() == InstanceStatus.DEREGISTERED) {
                throw new RegistryException(ReasonCode.INSTANCE_DEREGISTERED,
                        "instance is already deregistered");
            }

            return replace(existing, InstanceStatus.DEREGISTERED, existing.consecutiveFailures(),
                    existing.consecutiveSuccesses(), "instance deregistered");
        } finally {
            lock.writeLock().unlock();
        }
    }

    public ServiceInstance updateWeight(String serviceName, String instanceId, int weight) {
        if (weight < 1) {
            throw new RegistryException(ReasonCode.INVALID_REQUEST, "weight must be positive");
        }

        lock.writeLock().lock();
        try {
            ServiceInstance existing = requireExisting(serviceName, instanceId);
            if (existing.status() == InstanceStatus.EXPIRED) {
                throw new RegistryException(ReasonCode.INSTANCE_EXPIRED, "expired instance cannot be changed");
            }
            if (existing.status() == InstanceStatus.DEREGISTERED) {
                throw new RegistryException(ReasonCode.INSTANCE_DEREGISTERED,
                        "deregistered instance cannot be changed");
            }

            ServiceInstance updated = new ServiceInstance(
                    existing.serviceName(), existing.instanceId(), existing.host(), existing.port(),
                    weight, existing.status(), existing.ttlMillis(), existing.registeredAt(),
                    existing.lastHeartbeatAt(), existing.lastStatusChangedAt(),
                    existing.consecutiveFailures(), existing.consecutiveSuccesses()
            );
            instances.put(key(serviceName, instanceId), updated);
            addEvent(serviceName, instanceId, existing.status(), existing.status(), "weight changed to " + weight);
            return updated;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int expireLeases() {
        lock.writeLock().lock();
        try {
            Instant now = Instant.now(clock);
            int expired = 0;
            for (ServiceInstance instance : List.copyOf(instances.values())) {
                boolean leased = instance.status() == InstanceStatus.ACTIVE
                        || instance.status() == InstanceStatus.OUT_OF_SERVICE;
                if (leased && !instance.lastHeartbeatAt().plusMillis(instance.ttlMillis()).isAfter(now)) {
                    replace(instance, InstanceStatus.EXPIRED, 0, 0, "lease expired");
                    expired++;
                }
            }
            return expired;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Optional<ServiceInstance> recordProbeResult(
            String serviceName,
            String instanceId,
            boolean healthy,
            int failureThreshold,
            int successThreshold
    ) {
        if (failureThreshold < 1 || successThreshold < 1) {
            throw new RegistryException(ReasonCode.INVALID_REQUEST, "probe thresholds must be positive");
        }

        lock.writeLock().lock();
        try {
            ServiceInstance existing = instances.get(key(serviceName, instanceId));
            if (existing == null || existing.status() == InstanceStatus.EXPIRED
                    || existing.status() == InstanceStatus.DEREGISTERED) {
                return Optional.empty();
            }

            if (existing.status() == InstanceStatus.ACTIVE) {
                if (healthy) {
                    return Optional.of(replaceCounters(existing, 0, 0, "probe succeeded"));
                }
                int failures = existing.consecutiveFailures() + 1;
                if (failures >= failureThreshold) {
                    return Optional.of(replace(existing, InstanceStatus.OUT_OF_SERVICE, failures, 0,
                            "failure threshold reached; removed from routing"));
                }
                return Optional.of(replaceCounters(existing, failures, 0,
                        "probe failed below failure threshold"));
            }

            if (healthy) {
                int successes = existing.consecutiveSuccesses() + 1;
                if (successes >= successThreshold) {
                    return Optional.of(replace(existing, InstanceStatus.ACTIVE, 0, successes,
                            "success threshold reached; restored to routing"));
                }
                return Optional.of(replaceCounters(existing, 0, successes,
                        "probe succeeded below recovery threshold"));
            }
            return Optional.of(replaceCounters(existing, 0, 0, "probe failed; recovery counters reset"));
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Optional<ServiceInstance> getInstance(String serviceName, String instanceId) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(instances.get(key(serviceName, instanceId)));
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<ServiceInstance> listService(String serviceName) {
        lock.readLock().lock();
        try {
            return instances.values().stream()
                    .filter(instance -> instance.serviceName().equals(serviceName))
                    .sorted(Comparator.comparing(ServiceInstance::instanceId))
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<ServiceInstance> listInstances() {
        lock.readLock().lock();
        try {
            return List.copyOf(instances.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<ServiceInstance> activeInstances(String serviceName) {
        lock.readLock().lock();
        try {
            return instances.values().stream()
                    .filter(instance -> instance.serviceName().equals(serviceName))
                    .filter(ServiceInstance::isRoutable)
                    .sorted(Comparator.comparing(ServiceInstance::instanceId))
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<RegistryEvent> recentEvents() {
        lock.readLock().lock();
        try {
            return List.copyOf(events);
        } finally {
            lock.readLock().unlock();
        }
    }

    private ServiceInstance requireExisting(String serviceName, String instanceId) {
        ServiceInstance instance = instances.get(key(serviceName, instanceId));
        if (instance == null) {
            throw new RegistryException(ReasonCode.INSTANCE_NOT_FOUND, "instance does not exist");
        }
        return instance;
    }

    private ServiceInstance replace(
            ServiceInstance existing,
            InstanceStatus newStatus,
            int failures,
            int successes,
            String message
    ) {
        Instant now = Instant.now(clock);
        ServiceInstance updated = new ServiceInstance(
                existing.serviceName(), existing.instanceId(), existing.host(), existing.port(),
                existing.weight(), newStatus, existing.ttlMillis(), existing.registeredAt(),
                existing.lastHeartbeatAt(), now, failures, successes
        );
        instances.put(key(existing.serviceName(), existing.instanceId()), updated);
        addEvent(existing.serviceName(), existing.instanceId(), existing.status(), newStatus, message);
        return updated;
    }

    private ServiceInstance replaceCounters(
            ServiceInstance existing,
            int failures,
            int successes,
            String message
    ) {
        ServiceInstance updated = new ServiceInstance(
                existing.serviceName(), existing.instanceId(), existing.host(), existing.port(),
                existing.weight(), existing.status(), existing.ttlMillis(), existing.registeredAt(),
                existing.lastHeartbeatAt(), existing.lastStatusChangedAt(), failures, successes
        );
        instances.put(key(existing.serviceName(), existing.instanceId()), updated);
        addEvent(existing.serviceName(), existing.instanceId(), existing.status(), existing.status(), message);
        return updated;
    }

    private void addEvent(
            String serviceName,
            String instanceId,
            InstanceStatus from,
            InstanceStatus to,
            String message
    ) {
        events.addLast(new RegistryEvent(Instant.now(clock), serviceName, instanceId, from, to, message));
        while (events.size() > EVENT_LIMIT) {
            events.removeFirst();
        }
    }

    private static String key(String serviceName, String instanceId) {
        return serviceName + "\u0000" + instanceId;
    }

    private static void validateText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new RegistryException(ReasonCode.INVALID_REQUEST, field + " is required");
        }
    }

    private static boolean isLoopback(String host) {
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (UnknownHostException ex) {
            throw new RegistryException(ReasonCode.INVALID_REQUEST, "unknown local host: " + host);
        }
    }
}
