package com.github.lechandonga.serviceregistryloadbalancerjava.core;

import com.github.lechandonga.serviceregistryloadbalancerjava.config.RegistryProperties;
import com.github.lechandonga.serviceregistryloadbalancerjava.model.InstanceStatus;
import com.github.lechandonga.serviceregistryloadbalancerjava.model.ServiceInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class HealthCheckJob {
    private static final Logger log = LoggerFactory.getLogger(HealthCheckJob.class);

    private final ServiceRegistry registry;
    private final HealthProbe probe;
    private final RegistryProperties properties;

    public HealthCheckJob(ServiceRegistry registry, HealthProbe probe, RegistryProperties properties) {
        this.registry = registry;
        this.probe = probe;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${local-registry.probe-interval-millis:1000}")
    public void probeInstances() {
        List<ServiceInstance> instances = registry.listInstances().stream()
                .filter(instance -> instance.status() == InstanceStatus.ACTIVE
                        || instance.status() == InstanceStatus.OUT_OF_SERVICE)
                .toList();

        for (ServiceInstance instance : instances) {
            boolean healthy = probe.isHealthy(instance);
            registry.recordProbeResult(
                    instance.serviceName(),
                    instance.instanceId(),
                    healthy,
                    properties.getFailureThreshold(),
                    properties.getRecoveryThreshold()
            ).ifPresent(updated -> {
                if (updated.status() != instance.status()) {
                    log.info("Instance {} transitioned {} -> {}",
                            updated.instanceId(), instance.status(), updated.status());
                }
            });
        }
    }
}
