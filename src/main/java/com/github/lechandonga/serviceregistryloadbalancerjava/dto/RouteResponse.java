package com.github.lechandonga.serviceregistryloadbalancerjava.dto;

import com.github.lechandonga.serviceregistryloadbalancerjava.model.ServiceInstance;

import java.util.List;

public record RouteResponse(
        String serviceName,
        String strategy,
        String requestKey,
        ServiceInstance selectedInstance,
        List<RouteAttempt> attempts,
        boolean limited
) {
}
