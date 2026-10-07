package com.github.lechandonga.serviceregistryloadbalancerjava.dto;

public record RouteAttempt(int attempt, String instanceId, String result, String reason) {
}
