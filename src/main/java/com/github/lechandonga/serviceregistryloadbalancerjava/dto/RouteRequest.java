package com.github.lechandonga.serviceregistryloadbalancerjava.dto;

import java.util.List;

public record RouteRequest(
        String strategy,
        String requestKey,
        List<String> failedInstanceIds,
        Integer maxAttempts
) {
}
