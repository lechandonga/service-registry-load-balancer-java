package com.github.lechandonga.serviceregistryloadbalancerjava.tests;

import com.github.lechandonga.serviceregistryloadbalancerjava.lb.RoutingDecision;
import com.github.lechandonga.serviceregistryloadbalancerjava.lb.Strategy;
import com.github.lechandonga.serviceregistryloadbalancerjava.registry.InstanceRecord;
import com.github.lechandonga.serviceregistryloadbalancerjava.registry.InstanceState;
import com.github.lechandonga.serviceregistryloadbalancerjava.registry.RegistryErrorCode;
import com.github.lechandonga.serviceregistryloadbalancerjava.registry.RegistryException;
import com.github.lechandonga.serviceregistryloadbalancerjava.registry.ServiceRegistry;
import com.github.lechandonga.serviceregistryloadbalancerjava.lb.Router;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务一：注册生命周期
 * 覆盖：正常注册、重复注册拒绝、正常续约、对过期实例续约拒绝、
 * 注销不存在实例拒绝、注销后任何选路入口拿不到、过期后可重新注册、全程状态可查。
 */
@ExtendWith(TestLogExtension.class)
class RegistryLifecycleTest {

    private static final String SVC = "order-service";

    private final AtomicLong clock = new AtomicLong(1_000_000);
    private ServiceRegistry registry;
    private Router router;

    @BeforeEach
    void setUp() {
        registry = new ServiceRegistry(clock::get, 10_000);
        router = new Router(registry);
    }

    @Test
    @DisplayName("注册-续约-查询：注册成功并带租约，续约刷新心跳，查询可看到完整历史")
    void registerRenewAndQuery() {
        long ttl = registry.register(SVC, "node-1", "127.0.0.1", 9001, 1, 10_000L);
        assertEquals(10_000L, ttl);

        clock.addAndGet(3_000);
        registry.renew(SVC, "node-1");

        InstanceRecord record = registry.findRecord(SVC, "node-1");
        assertNotNull(record);
        assertEquals(InstanceState.ONLINE, record.getState());
        assertTrue(record.isServing());
        assertEquals(1_003_000, record.getLastHeartbeat());
        assertTrue(record.getHistory().size() >= 2, "至少有注册与续约两个事件");

        RoutingDecision decision = router.route(SVC, Strategy.ROUND_ROBIN, null, null, 1);
        assertTrue(decision.available());
        assertEquals("node-1", decision.selected().instanceId());
    }

    @Test
    @DisplayName("重复注册被拒绝，原因码 ALREADY_REGISTERED")
    void duplicateRegisterRejected() {
        registry.register(SVC, "node-1", "127.0.0.1", 9001, 1, 10_000L);
        clock.addAndGet(1_000);
        RegistryException ex = assertThrows(RegistryException.class,
                () -> registry.register(SVC, "node-1", "127.0.0.1", 9001, 1, 10_000L));
        assertEquals(RegistryErrorCode.ALREADY_REGISTERED, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("already registered"));
    }

    @Test
    @DisplayName("租约过期后续约被拒绝 LEASE_EXPIRED，重新注册可恢复且历史保留")
    void renewExpiredRejectedButReregisterAllowed() {
        registry.register(SVC, "node-1", "127.0.0.1", 9001, 1, 10_000L);
        clock.addAndGet(10_001);

        RegistryException ex = assertThrows(RegistryException.class,
                () -> registry.renew(SVC, "node-1"));
        assertEquals(RegistryErrorCode.LEASE_EXPIRED, ex.getErrorCode());

        registry.expireLeases();
        assertEquals(InstanceState.EXPIRED, registry.findRecord(SVC, "node-1").getState());
        assertNull(router.route(SVC, Strategy.ROUND_ROBIN, null, null, 1).selected(),
                "过期实例必须从选路摘除");

        registry.register(SVC, "node-1", "127.0.0.1", 9001, 1, 10_000L);
        InstanceRecord record = registry.findRecord(SVC, "node-1");
        assertEquals(InstanceState.ONLINE, record.getState());
        assertTrue(record.isServing());
        assertTrue(record.getHistory().size() >= 3, "过期事件与重新注册事件都应保留");
    }

    @Test
    @DisplayName("注销不存在/重复注销被拒绝 INSTANCE_NOT_FOUND；注销后查询与选路均不可见")
    void deregisterRules() {
        RegistryException never = assertThrows(RegistryException.class,
                () -> registry.deregister(SVC, "ghost"));
        assertEquals(RegistryErrorCode.INSTANCE_NOT_FOUND, never.getErrorCode());

        registry.register(SVC, "node-1", "127.0.0.1", 9001, 1, null);
        registry.register(SVC, "node-2", "127.0.0.1", 9002, 1, null);

        registry.deregister(SVC, "node-1");
        RegistryException again = assertThrows(RegistryException.class,
                () -> registry.deregister(SVC, "node-1"));
        assertEquals(RegistryErrorCode.INSTANCE_NOT_FOUND, again.getErrorCode());

        RegistryException renewGone = assertThrows(RegistryException.class,
                () -> registry.renew(SVC, "node-1"));
        assertEquals(RegistryErrorCode.INSTANCE_NOT_FOUND, renewGone.getErrorCode());

        InstanceRecord removed = registry.findRecord(SVC, "node-1");
        assertEquals(InstanceState.DEREGISTERED, removed.getState());
        assertFalse(removed.isServing());
        assertEquals(1, registry.snapshot(SVC).instances().size());

        for (int i = 0; i < 6; i++) {
            RoutingDecision decision = router.route(SVC, Strategy.ROUND_ROBIN, null, null, 1);
            assertEquals("node-2", decision.selected().instanceId(), "注销后任何选路都不能拿到 node-1");
        }
    }

    @Test
    @DisplayName("租约扫描自动摘除不续约实例，事件历史可解释")
    void leaseReaperEviction() {
        registry.register(SVC, "node-1", "127.0.0.1", 9001, 1, 10_000L);
        registry.register(SVC, "node-2", "127.0.0.1", 9002, 1, 10_000L);
        clock.addAndGet(5_000);
        registry.renew(SVC, "node-1");
        clock.addAndGet(5_001);

        var expired = registry.expireLeases();
        assertEquals(1, expired.size());
        assertEquals("node-2", expired.get(0).instanceId());
        assertEquals(1, registry.snapshot(SVC).instances().size());

        InstanceRecord record = registry.findRecord(SVC, "node-2");
        assertTrue(record.getHistory().stream()
                .anyMatch(e -> e.type().name().equals("LEASE_EXPIRED")));
    }

    @Test
    @DisplayName("非法参数被拒绝 INVALID_REQUEST")
    void invalidRequests() {
        RegistryException ex = assertThrows(RegistryException.class,
                () -> registry.register(" ", "node-1", "127.0.0.1", 9001, 1, null));
        assertEquals(RegistryErrorCode.INVALID_REQUEST, ex.getErrorCode());
        RegistryException badPort = assertThrows(RegistryException.class,
                () -> registry.register(SVC, "node-1", "127.0.0.1", 0, 1, null));
        assertEquals(RegistryErrorCode.INVALID_REQUEST, badPort.getErrorCode());
    }
}
