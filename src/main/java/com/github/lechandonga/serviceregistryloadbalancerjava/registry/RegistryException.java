package com.github.lechandonga.serviceregistryloadbalancerjava.registry;

/**
 * 注册 / 续约 / 注销被拒绝时抛出，携带可区分的 {@link RegistryErrorCode}。
 */
public class RegistryException extends RuntimeException {

    private final RegistryErrorCode errorCode;

    public RegistryException(RegistryErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public RegistryErrorCode getErrorCode() {
        return errorCode;
    }
}
