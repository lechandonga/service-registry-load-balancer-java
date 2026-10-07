package com.github.lechandonga.serviceregistryloadbalancerjava.web;

import com.github.lechandonga.serviceregistryloadbalancerjava.registry.InstanceEvent;
import com.github.lechandonga.serviceregistryloadbalancerjava.registry.InstanceRecord;
import com.github.lechandonga.serviceregistryloadbalancerjava.registry.InstanceState;

import java.util.List;

/** 实例完整状态：生命周期状态 + 是否可服务 + 连续成败计数 + 全程事件历史。 */
public record InstanceStatusResponse(
        String serviceName,
        String instanceId,
        String host,
        int port,
        int weight,
        InstanceState lifecycle,
        boolean serving,
        int consecutiveFailures,
        int consecutiveSuccesses,
        long registeredAt,
        long lastHeartbeat,
        long leaseTtlMillis,
        List<InstanceEvent> history) {

    public static InstanceStatusResponse from(InstanceRecord r) {
        return new InstanceStatusResponse(r.getServiceName(), r.getInstanceId(), r.getHost(),
                r.getPort(), r.getWeight(), r.getState(), r.isServing(),
                r.getConsecutiveFailures(), r.getConsecutiveSuccesses(),
                r.getRegisteredAt(), r.getLastHeartbeat(), r.getLeaseTtlMillis(),
                List.copyOf(r.getHistory()));
    }
}
