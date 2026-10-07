package com.github.lechandonga.serviceregistryloadbalancerjava.core;

import com.github.lechandonga.serviceregistryloadbalancerjava.config.RegistryProperties;
import com.github.lechandonga.serviceregistryloadbalancerjava.model.InstanceStatus;
import com.github.lechandonga.serviceregistryloadbalancerjava.model.ServiceInstance;
import com.github.lechandonga.serviceregistryloadbalancerjava.support.BusinessTestLoggingExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.net.InetAddress;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(BusinessTestLoggingExtension.class)
class HealthProbeTest {
    private static final int FAILURE_THRESHOLD = 3;
    private static final int RECOVERY_THRESHOLD = 2;

    @Test
    void briefFailuresDoNotRemoveInstanceAndProbeStateRemainsExplainable() throws Exception {
        ServiceRegistry registry = new ServiceRegistry();
        int port;
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            port = server.getLocalPort();
            registry.register("payments", "a", "127.0.0.1", port, 1, 10_000);
        }

        TcpLoopbackHealthProbe probe = new TcpLoopbackHealthProbe(100);
        probeOnce(registry, probe, "a", false);
        assertEquals(InstanceStatus.ACTIVE, registry.getInstance("payments", "a").orElseThrow().status());
        assertEquals(1, registry.getInstance("payments", "a").orElseThrow().consecutiveFailures());

        probeOnce(registry, probe, "a", false);
        try (ServerSocket ignored = new ServerSocket(port, 50, InetAddress.getLoopbackAddress())) {
            probeOnce(registry, probe, "a", true);
            ServiceInstance active = registry.getInstance("payments", "a").orElseThrow();
            assertEquals(InstanceStatus.ACTIVE, active.status());
            assertEquals(0, active.consecutiveFailures());
        }
    }

    @Test
    void thresholdFailuresRemoveAndThresholdSuccessesRestore() throws Exception {
        ServiceRegistry registry = new ServiceRegistry();
        TcpLoopbackHealthProbe probe = new TcpLoopbackHealthProbe(100);
        int port;
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            port = server.getLocalPort();
            registry.register("payments", "a", "127.0.0.1", port, 1, 10_000);
        }

        probeOnce(registry, probe, "a", false);
        probeOnce(registry, probe, "a", false);
        assertEquals(InstanceStatus.ACTIVE, registry.getInstance("payments", "a").orElseThrow().status());

        probeOnce(registry, probe, "a", false);
        assertEquals(InstanceStatus.OUT_OF_SERVICE, registry.getInstance("payments", "a").orElseThrow().status());

        try (ServerSocket ignored = new ServerSocket(port, 50, InetAddress.getLoopbackAddress())) {
            probeOnce(registry, probe, "a", true);
            assertEquals(InstanceStatus.OUT_OF_SERVICE, registry.getInstance("payments", "a").orElseThrow().status());
            probeOnce(registry, probe, "a", true);
            assertEquals(InstanceStatus.ACTIVE, registry.getInstance("payments", "a").orElseThrow().status());
        }
    }

    @Test
    void failedProbeDuringRecoveryRestartsSuccessCounter() throws Exception {
        ServiceRegistry registry = new ServiceRegistry();
        TcpLoopbackHealthProbe probe = new TcpLoopbackHealthProbe(100);
        int port;
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            port = server.getLocalPort();
            registry.register("payments", "a", "127.0.0.1", port, 1, 10_000);
        }

        for (int index = 0; index < FAILURE_THRESHOLD; index++) {
            probeOnce(registry, probe, "a", false);
        }
        assertEquals(InstanceStatus.OUT_OF_SERVICE, registry.getInstance("payments", "a").orElseThrow().status());

        try (ServerSocket ignored = new ServerSocket(port, 50, InetAddress.getLoopbackAddress())) {
            probeOnce(registry, probe, "a", true);
        }
        probeOnce(registry, probe, "a", false);
        assertEquals(0, registry.getInstance("payments", "a").orElseThrow().consecutiveSuccesses());

        try (ServerSocket ignored = new ServerSocket(port, 50, InetAddress.getLoopbackAddress())) {
            probeOnce(registry, probe, "a", true);
            assertEquals(InstanceStatus.OUT_OF_SERVICE, registry.getInstance("payments", "a").orElseThrow().status());
            probeOnce(registry, probe, "a", true);
            assertEquals(InstanceStatus.ACTIVE, registry.getInstance("payments", "a").orElseThrow().status());
        }
    }

    @Test
    void scheduledProbeJobUsesTheSameRegistryStateAsRouting() throws Exception {
        ServiceRegistry registry = new ServiceRegistry();
        RegistryProperties properties = new RegistryProperties();
        properties.setFailureThreshold(FAILURE_THRESHOLD);
        properties.setRecoveryThreshold(RECOVERY_THRESHOLD);
        HealthCheckJob job = new HealthCheckJob(registry, new TcpLoopbackHealthProbe(100), properties);

        int port;
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            port = server.getLocalPort();
            registry.register("payments", "a", "127.0.0.1", port, 1, 10_000);
        }

        for (int index = 0; index < FAILURE_THRESHOLD; index++) {
            job.probeInstances();
        }

        ServiceInstance instance = registry.getInstance("payments", "a").orElseThrow();
        assertEquals(InstanceStatus.OUT_OF_SERVICE, instance.status());
        assertEquals(0, registry.activeInstances("payments").size());
        var recentEvents = registry.recentEvents();
        assertEquals("failure threshold reached; removed from routing",
                recentEvents.get(recentEvents.size() - 1).message());
    }

    private static void probeOnce(
            ServiceRegistry registry,
            TcpLoopbackHealthProbe probe,
            String instanceId,
            boolean expectedResult
    ) {
        ServiceInstance before = registry.getInstance("payments", instanceId).orElseThrow();
        boolean actual = probe.isHealthy(before);
        assertEquals(expectedResult, actual);
        registry.recordProbeResult("payments", instanceId, actual, FAILURE_THRESHOLD, RECOVERY_THRESHOLD);
    }
}
