package com.github.lechandonga.serviceregistryloadbalancerjava.model;

import java.time.Instant;

public record RegistryEvent(
        Instant occurredAt,
        String serviceName,
        String instanceId,
        InstanceStatus fromStatus,
        InstanceStatus toStatus,
        String message
) {
}
