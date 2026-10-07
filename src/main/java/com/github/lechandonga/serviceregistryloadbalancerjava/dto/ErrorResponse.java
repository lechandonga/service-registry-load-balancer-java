package com.github.lechandonga.serviceregistryloadbalancerjava.dto;

import com.github.lechandonga.serviceregistryloadbalancerjava.model.ReasonCode;

import java.time.Instant;

public record ErrorResponse(Instant timestamp, ReasonCode reason, String message) {
}
