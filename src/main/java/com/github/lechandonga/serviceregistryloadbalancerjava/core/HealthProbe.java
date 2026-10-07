package com.github.lechandonga.serviceregistryloadbalancerjava.core;

import com.github.lechandonga.serviceregistryloadbalancerjava.model.ServiceInstance;

public interface HealthProbe {
    boolean isHealthy(ServiceInstance instance);
}
