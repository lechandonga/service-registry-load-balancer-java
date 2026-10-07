package com.github.lechandonga.serviceregistryloadbalancerjava.tests;

import com.github.lechandonga.serviceregistryloadbalancerjava.health.HealthProbeService;
import com.github.lechandonga.serviceregistryloadbalancerjava.health.ProbeChecker;
import com.github.lechandonga.serviceregistryloadbalancerjava.health.TcpLoopbackProbeChecker;
import com.github.lechandonga.serviceregistryloadbalancerjava.lb.Router;
import com.github.lechandonga.serviceregistryloadbalancerjava.lb.Strategy;
import com.github.lechandonga.serviceregistryloadbalancerjava.registry.InstanceRecord;
import com.github.lechandonga.serviceregistryloadbalancerjava.registry.ServiceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务三：健康探测抖动与恢复
 * 覆盖：单次抖动不摘除、连续失败到阈值才摘除、摘除后立即不可选路、
 * 摘除后偶发成功不恢复、连续成功到阈值才恢复；并用真实回环 TCP 端口做端到端验证。
 */
@ExtendWith(TestLogExtension.class)
class HealthProbeTest {

    private static final String SVC = "inventory-service";

    private final AtomicLong clock = new AtomicLong(1_000_000);
    private ServiceRegistry registry;
    private Router router;
    private HealthProbeService probeService;
    private final AtomicBoolean healthy = new AtomicBoolean(true);

    @BeforeEach
    void setUp() {
        registry = new ServiceRegistry(clock::get, 60_000);
        router = new Router(registry);
        ProbeChecker checker = record -> healthy.get();
        // 失败阈值 3、恢复阈值 2：短暂抖动不应导致上下线
        probeService = new HealthProbeService(registry, checker, 3, 2, 1_000);
    }

    @AfterEach
    void tearDown() {
        probeService.shutdown();
    }

    @Test
    @DisplayName("短暂抖动（2 次失败后恢复）不摘除实例")
    void briefJitterDoesNotEvict() {
        registry.register(SVC, "node-1", "127.0.0.1", 9001, 1, null);

        healthy.set(false);
        probeService.probeOnce();
        probeService.probeOnce();
        assertTrue(registry.findRecord(SVC, "node-1").isServing(), "2 次失败未到阈值 3，不应摘除");
        assertTrue(router.route(SVC, Strategy.ROUND_ROBIN, null, null, 1).available());

        healthy.set(true);
        probeService.probeOnce();
        assertEquals(0, registry.findRecord(SVC, "node-1").getConsecutiveFailures(),
                "一次成功应清零失败计数");
        assertTrue(registry.findRecord(SVC, "node-1").isServing());
    }

    @Test
    @DisplayName("连续失败达到阈值才摘除，摘除后选路不可见；偶发成功不恢复")
    void failThresholdEvictsAndIntermittentSuccessKeepsSuspended() {
        registry.register(SVC, "node-1", "127.0.0.1", 9001, 1, null);
        registry.register(SVC, "node-2", "127.0.0.1", 9002, 1, null);

        // node-2 始终健康，仅 node-1 进入故障
        ProbeChecker perInstance = record ->
                !record.getInstanceId().equals("node-1") || healthy.get();
        HealthProbeService targetedProbe = new HealthProbeService(registry, perInstance, 3, 2, 1_000);
        healthy.set(false);
        assertEquals(0, targetedProbe.probeOnce());
        assertEquals(0, targetedProbe.probeOnce());
        int flips = targetedProbe.probeOnce();
        assertEquals(1, flips, "第 3 次连续失败应触发摘除翻转");

        InstanceRecord record = registry.findRecord(SVC, "node-1");
        assertFalse(record.isServing());
        assertEquals(3, record.getConsecutiveFailures());
        assertTrue(record.getHistory().stream()
                .anyMatch(e -> e.type().name().equals("HEALTH_SUSPENDED")));

        assertEquals(1, registry.snapshot(SVC).instances().size());
        for (int i = 0; i < 4; i++) {
            assertEquals("node-2",
                    router.route(SVC, Strategy.ROUND_ROBIN, null, null, 1)
                            .selected().instanceId());
        }

        // 摘除后出现一次成功但未达到恢复阈值 2，不恢复
        healthy.set(true);
        assertEquals(0, targetedProbe.probeOnce());
        assertFalse(registry.findRecord(SVC, "node-1").isServing());
        healthy.set(false);
        targetedProbe.probeOnce();
        healthy.set(true);
        assertEquals(0, targetedProbe.probeOnce(), "恢复计数被中间的失败清零，仍不恢复");
        targetedProbe.shutdown();
    }

    @Test
    @DisplayName("摘除后连续成功达到阈值才恢复，且不会在抖动中反复上下线")
    void recoverOnlyAfterConsecutiveSuccessThreshold() {
        registry.register(SVC, "node-1", "127.0.0.1", 9001, 1, null);

        healthy.set(false);
        probeService.probeOnce();
        probeService.probeOnce();
        probeService.probeOnce();
        assertFalse(registry.findRecord(SVC, "node-1").isServing());
        assertNull(router.route(SVC, Strategy.ROUND_ROBIN, null, null, 1).selected());

        healthy.set(true);
        assertEquals(0, probeService.probeOnce());
        assertFalse(registry.findRecord(SVC, "node-1").isServing(), "1 次成功未到恢复阈值 2");
        int flips = probeService.probeOnce();
        assertEquals(1, flips, "第 2 次连续成功应触发恢复翻转");

        InstanceRecord record = registry.findRecord(SVC, "node-1");
        assertTrue(record.isServing());
        assertTrue(router.route(SVC, Strategy.ROUND_ROBIN, null, null, 1).available());
        assertTrue(record.getHistory().stream()
                .anyMatch(e -> e.type().name().equals("HEALTH_RESUMED")));
    }

    @Test
    @DisplayName("真实回环 TCP 探测：关闭 ServerSocket 后摘除，重新监听后恢复")
    void tcpLoopbackEndToEnd(@TempDir Path tmp) throws Exception {
        int port;
        ServerSocket serverSocket;
        try {
            serverSocket = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
            port = serverSocket.getLocalPort();
        } catch (IOException e) {
            throw new AssertionError("无法在回环地址打开测试端口", e);
        }

        ServiceRegistry realRegistry = new ServiceRegistry(System::currentTimeMillis, 60_000);
        Router realRouter = new Router(realRegistry);
        HealthProbeService realProbe = new HealthProbeService(realRegistry,
                new TcpLoopbackProbeChecker(200), 2, 2, 1_000);
        realRegistry.register(SVC, "tcp-node", "127.0.0.1", port, 1, null);

        realProbe.probeOnce();
        assertTrue(realRegistry.findRecord(SVC, "tcp-node").isServing());
        assertTrue(realRouter.route(SVC, Strategy.ROUND_ROBIN, null, null, 1).available());

        serverSocket.close();
        realProbe.probeOnce();
        realProbe.probeOnce();
        assertFalse(realRegistry.findRecord(SVC, "tcp-node").isServing(),
                "端口关闭、连续 2 次探测失败后应摘除");
        assertNull(realRouter.route(SVC, Strategy.ROUND_ROBIN, null, null, 1).selected());

        try (ServerSocket restarted = new ServerSocket(port, 50,
                java.net.InetAddress.getByName("127.0.0.1"))) {
            realProbe.probeOnce();
            realProbe.probeOnce();
            assertTrue(realRegistry.findRecord(SVC, "tcp-node").isServing(),
                    "端口重新监听、连续 2 次成功后应恢复");
            assertTrue(realRouter.route(SVC, Strategy.ROUND_ROBIN, null, null, 1).available());
        }
    }
}
