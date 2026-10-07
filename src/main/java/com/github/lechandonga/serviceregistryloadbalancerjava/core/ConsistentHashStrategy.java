package com.github.lechandonga.serviceregistryloadbalancerjava.core;

import com.github.lechandonga.serviceregistryloadbalancerjava.error.RegistryException;
import com.github.lechandonga.serviceregistryloadbalancerjava.model.ReasonCode;
import com.github.lechandonga.serviceregistryloadbalancerjava.model.ServiceInstance;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeMap;

public class ConsistentHashStrategy implements SelectionStrategy {
    private static final int VIRTUAL_NODES_PER_WEIGHT = 32;

    @Override
    public String name() {
        return "CONSISTENT_HASH";
    }

    @Override
    public ServiceInstance select(
            ServiceRegistry registry,
            String serviceName,
            String requestKey,
            Set<String> excludedInstanceIds
    ) {
        if (requestKey == null || requestKey.isBlank()) {
            throw new RegistryException(ReasonCode.REQUEST_KEY_REQUIRED,
                    "requestKey is required for CONSISTENT_HASH");
        }

        TreeMap<Long, ServiceInstance> ring = new TreeMap<>();
        for (ServiceInstance instance : registry.activeInstances(serviceName)) {
            if (excludedInstanceIds.contains(instance.instanceId())) {
                continue;
            }
            for (int weightIndex = 0; weightIndex < instance.weight(); weightIndex++) {
                for (int virtualIndex = 0; virtualIndex < VIRTUAL_NODES_PER_WEIGHT; virtualIndex++) {
                    String node = serviceName + "|" + instance.instanceId() + "|"
                            + weightIndex + "|" + virtualIndex;
                    ring.putIfAbsent(hash(node), instance);
                }
            }
        }

        if (ring.isEmpty()) {
            throw new RegistryException(ReasonCode.NO_AVAILABLE_INSTANCE,
                    "no active instance is available for service: " + serviceName);
        }

        long requestHash = hash(serviceName + "|" + requestKey);
        Long nodeHash = ring.ceilingKey(requestHash);
        if (nodeHash == null) {
            nodeHash = ring.firstKey();
        }
        return ring.get(nodeHash);
    }

    private static long hash(String value) {
        long hash = 0xcbf29ce484222325L;
        for (byte inputByte : value.getBytes(StandardCharsets.UTF_8)) {
            hash ^= inputByte;
            hash *= 0x100000001b3L;
        }
        return hash & Long.MAX_VALUE;
    }
}
