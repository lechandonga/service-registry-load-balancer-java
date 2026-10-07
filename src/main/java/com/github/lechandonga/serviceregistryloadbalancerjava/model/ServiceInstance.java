package com.github.lechandonga.serviceregistryloadbalancerjava.model;

import java.net.InetSocketAddress;
import java.time.Instant;

public record ServiceInstance(
        String serviceName,
        String instanceId,
        String host,
        int port,
        int weight,
        InstanceStatus status,
        long ttlMillis,
        Instant registeredAt,
        Instant lastHeartbeatAt,
        Instant lastStatusChangedAt,
        int consecutiveFailures,
        int consecutiveSuccesses
) {
    public InetSocketAddress socketAddress() {
        return new InetSocketAddress(host, port);
    }

    public boolean isRoutable() {
        return status == InstanceStatus.ACTIVE;
    }
}
