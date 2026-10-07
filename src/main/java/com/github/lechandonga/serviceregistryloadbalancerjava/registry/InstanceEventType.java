package com.github.lechandonga.serviceregistryloadbalancerjava.registry;

/** 实例审计事件类型，保证实例从注册到注销的状态全程可查、可解释。 */
public enum InstanceEventType {
    REGISTERED,
    RENEWED,
    DEREGISTERED,
    LEASE_EXPIRED,
    HEALTH_SUSPENDED,
    HEALTH_RESUMED,
    WEIGHT_CHANGED
}
