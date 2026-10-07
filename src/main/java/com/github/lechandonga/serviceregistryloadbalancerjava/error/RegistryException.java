package com.github.lechandonga.serviceregistryloadbalancerjava.error;

import com.github.lechandonga.serviceregistryloadbalancerjava.model.ReasonCode;

public class RegistryException extends RuntimeException {
    private final ReasonCode reasonCode;

    public RegistryException(ReasonCode reasonCode, String message) {
        super(message);
        this.reasonCode = reasonCode;
    }

    public ReasonCode getReasonCode() {
        return reasonCode;
    }
}
