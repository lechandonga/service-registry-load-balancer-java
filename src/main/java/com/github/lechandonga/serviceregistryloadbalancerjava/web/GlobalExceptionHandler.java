package com.github.lechandonga.serviceregistryloadbalancerjava.web;

import com.github.lechandonga.serviceregistryloadbalancerjava.dto.ErrorResponse;
import com.github.lechandonga.serviceregistryloadbalancerjava.error.RegistryException;
import com.github.lechandonga.serviceregistryloadbalancerjava.model.ReasonCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(RegistryException.class)
    public ResponseEntity<ErrorResponse> handleRegistryException(RegistryException ex) {
        HttpStatus status = switch (ex.getReasonCode()) {
            case INSTANCE_NOT_FOUND, INSTANCE_DEREGISTERED -> HttpStatus.NOT_FOUND;
            case INSTANCE_ALREADY_REGISTERED, INSTANCE_EXPIRED,
                 REQUEST_KEY_REQUIRED, RETRY_LIMIT_EXCEEDED -> HttpStatus.CONFLICT;
            case NO_AVAILABLE_INSTANCE -> HttpStatus.SERVICE_UNAVAILABLE;
            case UNSUPPORTED_STRATEGY, INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
                .body(new ErrorResponse(Instant.now(), ex.getReasonCode(), ex.getMessage()));
    }
}
