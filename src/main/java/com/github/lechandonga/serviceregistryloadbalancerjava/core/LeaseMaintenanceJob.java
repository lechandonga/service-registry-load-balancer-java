package com.github.lechandonga.serviceregistryloadbalancerjava.core;

import com.github.lechandonga.serviceregistryloadbalancerjava.model.ServiceInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LeaseMaintenanceJob {
    private static final Logger log = LoggerFactory.getLogger(LeaseMaintenanceJob.class);

    private final ServiceRegistry registry;

    public LeaseMaintenanceJob(ServiceRegistry registry) {
        this.registry = registry;
    }

    @Scheduled(fixedDelayString = "${local-registry.lease-sweep-interval-millis:500}")
    public void expireLeases() {
        int expired = registry.expireLeases();
        if (expired > 0) {
            log.info("Lease sweep expired {} instance(s)", expired);
        }
    }
}
