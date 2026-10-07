package com.github.lechandonga.serviceregistryloadbalancerjava.core;

import com.github.lechandonga.serviceregistryloadbalancerjava.error.RegistryException;
import com.github.lechandonga.serviceregistryloadbalancerjava.model.InstanceStatus;
import com.github.lechandonga.serviceregistryloadbalancerjava.model.ReasonCode;
import com.github.lechandonga.serviceregistryloadbalancerjava.model.ServiceInstance;
import com.github.lechandonga.serviceregistryloadbalancerjava.support.BusinessTestLoggingExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(BusinessTestLoggingExtension.class)
class RegistrationLifecycleTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-08T00:00:00Z"));
    private final ServiceRegistry registry = new ServiceRegistry(clock);

    @Test
    void registerQueryRenewAndDeregisterKeepOneExplainableState() {
        ServiceInstance registered = registry.register("orders", "a", "127.0.0.1", 18080, 2, 1_000);

        assertEquals(InstanceStatus.ACTIVE, registered.status());
        assertEquals(2, registry.getInstance("orders", "a").orElseThrow().weight());

        clock.advanceMillis(600);
        ServiceInstance renewed = registry.renew("orders", "a");
        assertEquals(Duration.ofMillis(600), Duration.between(renewed.registeredAt(), renewed.lastHeartbeatAt()));

        clock.advanceMillis(600);
        assertEquals(1, registry.activeInstances("orders").size());

        ServiceInstance deregistered = registry.deregister("orders", "a");
        assertEquals(InstanceStatus.DEREGISTERED, deregistered.status());
        assertEquals(InstanceStatus.DEREGISTERED, registry.getInstance("orders", "a").orElseThrow().status());
        assertTrue(registry.activeInstances("orders").isEmpty());
        var recentEvents = registry.recentEvents();
        assertEquals("instance deregistered", recentEvents.get(recentEvents.size() - 1).message());
    }

    @Test
    void duplicateExpiredAndUnknownOperationsReturnDifferentReasons() {
        registry.register("orders", "a", "127.0.0.1", 18080, 1, 1_000);

        assertReason(ReasonCode.INSTANCE_ALREADY_REGISTERED,
                () -> registry.register("orders", "a", "127.0.0.1", 18081, 1, 1_000));

        assertReason(ReasonCode.INSTANCE_NOT_FOUND, () -> registry.renew("orders", "missing"));
        assertReason(ReasonCode.INSTANCE_NOT_FOUND, () -> registry.deregister("orders", "missing"));

        clock.advanceMillis(1_001);
        registry.expireLeases();
        assertEquals(InstanceStatus.EXPIRED, registry.getInstance("orders", "a").orElseThrow().status());
        assertReason(ReasonCode.INSTANCE_EXPIRED, () -> registry.renew("orders", "a"));

        ServiceInstance other = registry.register("orders", "b", "localhost", 18081, 1, 1_000);
        assertEquals(InstanceStatus.ACTIVE, other.status());
        registry.deregister("orders", "b");
        assertReason(ReasonCode.INSTANCE_DEREGISTERED, () -> registry.deregister("orders", "b"));
        assertReason(ReasonCode.INSTANCE_DEREGISTERED, () -> registry.renew("orders", "b"));
    }

    private static void assertReason(ReasonCode expected, Runnable runnable) {
        RegistryException ex = assertThrows(RegistryException.class, runnable::run);
        assertEquals(expected, ex.getReasonCode());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advanceMillis(long millis) {
            instant = instant.plusMillis(millis);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
