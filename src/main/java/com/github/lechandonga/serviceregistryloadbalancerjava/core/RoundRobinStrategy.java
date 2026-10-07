package com.github.lechandonga.serviceregistryloadbalancerjava.core;

import com.github.lechandonga.serviceregistryloadbalancerjava.error.RegistryException;
import com.github.lechandonga.serviceregistryloadbalancerjava.model.ReasonCode;
import com.github.lechandonga.serviceregistryloadbalancerjava.model.ServiceInstance;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class RoundRobinStrategy implements SelectionStrategy {
    private final Map<String, AtomicLong> cursors = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "ROUND_ROBIN";
    }

    @Override
    public ServiceInstance select(
            ServiceRegistry registry,
            String serviceName,
            String requestKey,
            Set<String> excludedInstanceIds
    ) {
        List<ServiceInstance> candidates = registry.activeInstances(serviceName).stream()
                .filter(instance -> !excludedInstanceIds.contains(instance.instanceId()))
                .toList();
        if (candidates.isEmpty()) {
            throw new RegistryException(ReasonCode.NO_AVAILABLE_INSTANCE,
                    "no active instance is available for service: " + serviceName);
        }

        long cursor = cursors.computeIfAbsent(serviceName, ignored -> new AtomicLong()).getAndIncrement();
        int index = (int) Math.floorMod(cursor, candidates.size());
        return candidates.get(index);
    }
}
