package com.github.lechandonga.serviceregistryloadbalancerjava.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 周期性租约清理：超过 TTL 未续约的实例被判定 EXPIRED 并立即从选路快照摘除。
 */
public class LeaseReaper {

    private static final Logger log = LoggerFactory.getLogger(LeaseReaper.class);

    private final ServiceRegistry registry;
    private final long sweepIntervalMillis;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean started = new AtomicBoolean(false);

    public LeaseReaper(ServiceRegistry registry, long sweepIntervalMillis) {
        this.registry = registry;
        this.sweepIntervalMillis = sweepIntervalMillis;
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "slb-lease-reaper");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        if (started.compareAndSet(false, true)) {
            executor.scheduleWithFixedDelay(this::sweepSafely,
                    sweepIntervalMillis, sweepIntervalMillis, TimeUnit.MILLISECONDS);
            log.info("lease reaper started sweepInterval={}ms", sweepIntervalMillis);
        }
    }

    public void shutdown() {
        executor.shutdownNow();
        started.set(false);
    }

    private void sweepSafely() {
        try {
            List<InstanceView> expired = registry.expireLeases();
            if (!expired.isEmpty()) {
                log.warn("lease sweep evicted {} instance(s)", expired.size());
            }
        } catch (Throwable t) {
            log.error("lease sweep failed: {}", t.toString(), t);
        }
    }
}
