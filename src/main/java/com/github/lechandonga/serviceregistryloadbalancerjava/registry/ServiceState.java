package com.github.lechandonga.serviceregistryloadbalancerjava.registry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** 单个服务名下的全部状态。任何字段访问都必须持有 {@code lock}。 */
final class ServiceState {

    final Object lock = new Object();
    final Map<String, InstanceRecord> instances = new LinkedHashMap<>();
    final AtomicReference<EligibleSnapshot> snapshot = new AtomicReference<>(EligibleSnapshot.empty(0));
    long version;
}
