package com.github.lechandonga.serviceregistryloadbalancerjava.dto;

public record RegisterInstanceRequest(
        String serviceName,
        String instanceId,
        String host,
        Integer port,
        Integer weight,
        Long ttlMillis
) {
}
