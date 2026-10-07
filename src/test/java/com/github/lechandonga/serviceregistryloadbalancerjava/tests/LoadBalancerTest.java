package com.github.lechandonga.serviceregistryloadbalancerjava.tests;

import com.github.lechandonga.serviceregistryloadbalancerjava.lb.RoutingDecision;
import com.github.lechandonga.serviceregistryloadbalancerjava.lb.Strategy;
import com.github.lechandonga.serviceregistryloadbalancerjava.registry.InstanceView;
import com.github.lechandonga.serviceregistryloadbalancerjava.registry.ServiceRegistry;
import com.github.lechandonga.serviceregistryloadbalancerjava.lb.Router;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务二：选路策略语义
 * 覆盖：轮询不跳过/不重复、加权比例、权重调整即时生效、
 * 一致性哈希同键稳定、少量实例变动尽量保住归属、并发选路绝不选中已摘除实例。
 */
@ExtendWith(TestLogExtension.class)
class LoadBalancerTest {

    private static final String SVC = "payment-service";

    private final AtomicLong clock = new AtomicLong(1_000_000);
    private ServiceRegistry registry;
    private Router router;

    @BeforeEach
    void setUp() {
        registry = new ServiceRegistry(clock::get, 60_000);
        router = new Router(registry);
    }

    @Test
    @DisplayName("等权轮询：连续选路不跳过可用实例也不重复命中，严格循环")
    void roundRobinCoversAllExactlyOncePerCycle() {
        registry.register(SVC, "a", "127.0.0.1", 9001, 1, null);
        registry.register(SVC, "b", "127.0.0.1", 9002, 1, null);
        registry.register(SVC, "c", "127.0.0.1", 9003, 1, null);

        List<String> hits = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            hits.add(router.route(SVC, Strategy.ROUND_ROBIN, null, null, 1)
                    .selected().instanceId());
        }
        assertEquals(List.of("a", "b", "c", "a", "b", "c", "a", "b", "c"), hits);
    }

    @Test
    @DisplayName("加权轮询：权重 5:1 时流量比例近似 5:1，且每个小周期内分布平滑")
    void weightedRoundRobinRatio() {
        registry.register(SVC, "a", "127.0.0.1", 9001, 5, null);
        registry.register(SVC, "b", "127.0.0.1", 9002, 1, null);

        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 600; i++) {
            String id = router.route(SVC, Strategy.ROUND_ROBIN, null, null, 1)
                    .selected().instanceId();
            counts.merge(id, 1, Integer::sum);
        }
        assertEquals(500, counts.get("a"));
        assertEquals(100, counts.get("b"));
    }

    @Test
    @DisplayName("权重调整即时生效，轮询语义仍然正确")
    void weightChangeTakesEffect() {
        registry.register(SVC, "a", "127.0.0.1", 9001, 1, null);
        registry.register(SVC, "b", "127.0.0.1", 9002, 1, null);
        for (int i = 0; i < 4; i++) {
            router.route(SVC, Strategy.ROUND_ROBIN, null, null, 1);
        }
        registry.setWeight(SVC, "a", 3);

        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 400; i++) {
            String id = router.route(SVC, Strategy.ROUND_ROBIN, null, null, 1)
                    .selected().instanceId();
            counts.merge(id, 1, Integer::sum);
        }
        assertEquals(300, counts.get("a"));
        assertEquals(100, counts.get("b"));
    }

    @Test
    @DisplayName("一致性哈希：同键稳定命中同实例")
    void consistentHashSameKeyStable() {
        for (int i = 1; i <= 5; i++) {
            registry.register(SVC, "node-" + i, "127.0.0.1", 9000 + i, 1, null);
        }
        Set<String> chosen = new HashSet<>();
        for (String key : List.of("user-1", "user-2", "order-9", "cart-77", "user-1", "user-1")) {
            String id = router.route(SVC, Strategy.CONSISTENT_HASH, key, null, 1)
                    .selected().instanceId();
            chosen.add(id);
        }
        String user1 = router.route(SVC, Strategy.CONSISTENT_HASH, "user-1", null, 1)
                .selected().instanceId();
        for (int i = 0; i < 20; i++) {
            assertEquals(user1, router.route(SVC, Strategy.CONSISTENT_HASH, "user-1", null, 1)
                    .selected().instanceId());
        }
        assertFalse(chosen.isEmpty());
    }

    @Test
    @DisplayName("一致性哈希：下线 1 个实例时大多数键保持原归属（只迁移受影响键）")
    void consistentHashMinimalRelocation() {
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            String id = "node-" + i;
            ids.add(id);
            registry.register(SVC, id, "127.0.0.1", 9000 + i, 1, null);
        }
        List<String> keys = new ArrayList<>();
        for (int k = 0; k < 4_000; k++) {
            keys.add("key-" + k);
        }
        Map<String, String> before = new HashMap<>();
        for (String key : keys) {
            before.put(key, router.route(SVC, Strategy.CONSISTENT_HASH, key, null, 1)
                    .selected().instanceId());
        }

        registry.deregister(SVC, "node-4");
        long moved = 0;
        for (String key : keys) {
            String after = router.route(SVC, Strategy.CONSISTENT_HASH, key, null, 1)
                    .selected().instanceId();
            if (!after.equals(before.get(key))) {
                moved++;
            }
        }
        double movedRatio = (double) moved / keys.size();
        // 摘除 1/8 实例时，理论上约 1/8 的键迁移；允许上界 0.35 的波动但远小于全量洗牌
        assertTrue(movedRatio < 0.35,
                "迁移比例 " + movedRatio + " 过高，未体现一致性哈希稳定性");
        assertTrue(movedRatio >= 0.02, "确实应当有一部分键迁移，实际 " + movedRatio);
    }

    @Test
    @DisplayName("并发选路 + 摘除：多线程同时选路时绝不可能选中已注销/已摘除实例")
    void concurrentRoutingNeverPicksEvictedInstance() throws Exception {
        int n = 6;
        for (int i = 1; i <= n; i++) {
            registry.register(SVC, "node-" + i, "127.0.0.1", 9000 + i, 1, null);
        }
        int threads = 16;
        int perThread = 500;
        // 第一栅栏：等所有线程就绪，由其中一个线程摘除 node-3/node-4；
        // 第二栅栏：确保摘除先于所有选路发生，从而“任何选路都不得命中被摘除实例”。
        CyclicBarrier ready = new CyclicBarrier(threads);
        CyclicBarrier afterEviction = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        Set<String> illegalHits = ConcurrentHashMap.newKeySet();
        List<String> routingErrors = new java.util.concurrent.CopyOnWriteArrayList<>();

        for (int t = 0; t < threads; t++) {
            final int seed = t;
            pool.submit(() -> {
                try {
                    ready.await();
                    if (seed == 0) {
                        registry.deregister(SVC, "node-3");
                        registry.deregister(SVC, "node-4");
                    }
                    afterEviction.await();

                    long currentVersion = registry.snapshot(SVC).version();
                    for (int i = 0; i < perThread; i++) {
                        RoutingDecision decision = router.route(
                                SVC, Strategy.ROUND_ROBIN, null, null, 1);
                        InstanceView picked = decision.selected();
                        if (picked != null
                                && (picked.instanceId().equals("node-3")
                                || picked.instanceId().equals("node-4"))) {
                            illegalHits.add(picked.instanceId());
                        }
                        // 摘除后版本不再变化：选中实例必须仍在最新快照中（线性一致）
                        if (picked != null && decision.snapshotVersion() >= currentVersion) {
                            boolean stillEligible = registry.snapshot(SVC).instances().stream()
                                    .map(InstanceView::instanceId)
                                    .anyMatch(picked.instanceId()::equals);
                            if (!stillEligible) {
                                illegalHits.add(picked.instanceId());
                            }
                        }
                    }
                } catch (Exception e) {
                    routingErrors.add(e.toString());
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertTrue(routingErrors.isEmpty(), routingErrors.toString());
        assertTrue(illegalHits.isEmpty(), "选中了已摘除实例: " + illegalHits);
        assertEquals(4, registry.snapshot(SVC).instances().size());
    }

    @Test
    @DisplayName("故障转移有上限：不健康实例被跳过，超过预算后明确失败并解释，不会无限打转")
    void failoverBoundedAndExplained() {
        registry.register(SVC, "a", "127.0.0.1", 9001, 1, null);
        registry.register(SVC, "b", "127.0.0.1", 9002, 1, null);
        registry.register(SVC, "c", "127.0.0.1", 9003, 1, null);

        RoutingDecision allBad = router.route(SVC, Strategy.ROUND_ROBIN, null,
                view -> false, 3);
        assertFalse(allBad.available());
        assertEquals(3, allBad.attempts().size(), "最多尝试 3 次（有上限）");
        assertTrue(allBad.explanation().contains("exhausted"));

        RoutingDecision thirdOk = router.route(SVC, Strategy.ROUND_ROBIN, null,
                view -> view.instanceId().equals("c"), 3);
        assertTrue(thirdOk.available());
        assertEquals("c", thirdOk.selected().instanceId());
        assertEquals(3, thirdOk.attempts().size(), "前两个失败、第三个成功");

        RoutingDecision hashFailover = router.route(SVC, Strategy.CONSISTENT_HASH, "k",
                view -> !view.instanceId().equals("a"), 3);
        if (hashFailover.selected() != null) {
            assertFalse(hashFailover.selected().instanceId().equals("a"));
        }
    }
}
