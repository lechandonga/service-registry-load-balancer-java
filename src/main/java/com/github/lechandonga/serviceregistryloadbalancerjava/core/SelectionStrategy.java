package com.github.lechandonga.serviceregistryloadbalancerjava.core;

import com.github.lechandonga.serviceregistryloadbalancerjava.model.ServiceInstance;

import java.util.Set;

public interface SelectionStrategy {
    String name();

    ServiceInstance select(
            ServiceRegistry registry,
            String serviceName,
            String requestKey,
            Set<String> excludedInstanceIds
    );
}
