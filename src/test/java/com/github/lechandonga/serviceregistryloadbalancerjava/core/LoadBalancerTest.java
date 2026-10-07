package com.github.lechandonga.serviceregistryloadbalancerjava.core;

import com.github.lechandonga.serviceregistryloadbalancerjava.dto.RouteResponse;
import com.github.lechandonga.serviceregistryloadbalancerjava.error.RegistryException;
import com.github.lechandonga.serviceregistryloadbalancerjava.model.ReasonCode;
import com.github.lechandonga.serviceregistryloadbalancerjava.model.ServiceInstance;
import com.github.lechandonga.serviceregistryloadbalancerjava.support.BusinessTestLoggingExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(BusinessTestLoggingExtension.class)
class LoadBalancerTest {
    @Test
    void roundRobinVisitsEveryAvailableInstanceExactlyOncePerCycle() {
        ServiceRegistry registry = new ServiceRegistry();
        registry.register("orders", "a", "127.0.0.1", 18081, 2, 10_000);
        registry.register("orders", "b", "127.0.0.1", 18082, 1, 10_000);
        LoadBalancer balancer = newLoadBalancer(registry);

        Map<String, Integer> hits = new HashMap<>();
        String previous = "";
        for (int index = 0; index < 6; index++) {
            ServiceInstance selected = balancer.select("orders", "ROUND_ROBIN", null);
            hits.merge(selected.instanceId(), 1, Integer::sum);
            assertNotEquals(previous, selected.instanceId());
            previous = selected.instanceId();
        }

        assertEquals(3, hits.get("a"));
        assertEquals(3, hits.get("b"));
    }

    @Test
    void consistentHashStabilizesKeysWhenOneInstanceChanges() {
        ServiceRegistry registry = new ServiceRegistry();
        registry.register("users", "a", "127.0.0.1", 18081, 1, 10_000);
        registry.register("users", "b", "127.0.0.1", 18082, 1, 10_000);
        registry.register("users", "c", "127.0.0.1", 18083, 1, 10_000);
        LoadBalancer balancer = newLoadBalancer(registry);

        Map<String, String> before = new HashMap<>();
        for (int index = 0; index < 2_000; index++) {
            String key = "key-" + index;
            before.put(key, balancer.select("users", "CONSISTENT_HASH", key).instanceId());
        }

        registry.register("users", "d", "127.0.0.1", 18084, 1, 10_000);
        for (Map.Entry<String, String> entry : before.entrySet()) {
            String after = balancer.select("users", "CONSISTENT_HASH", entry.getKey()).instanceId();
            if (!after.equals(entry.getValue())) {
                assertEquals("d", after);
            }
        }

        registry.deregister("users", "d");
        for (Map.Entry<String, String> entry : before.entrySet()) {
            assertEquals(entry.getValue(),
                    balancer.select("users", "CONSISTENT_HASH", entry.getKey()).instanceId());
        }
    }

    @Test
    void failoverHasABoundedNumberOfDistinctAttempts() {
        ServiceRegistry registry = new ServiceRegistry();
        registry.register("orders", "a", "127.0.0.1", 18081, 1, 10_000);
        registry.register("orders", "b", "127.0.0.1", 18082, 1, 10_000);
        registry.register("orders", "c", "127.0.0.1", 18083, 1, 10_000);
        LoadBalancer balancer = newLoadBalancer(registry);

        RouteResponse response = balancer.routeWithFailover(
                "orders", "CONSISTENT_HASH", "retry-key", Set.of("a", "b"), 3);
        assertEquals("c", response.selectedInstance().instanceId());

        RegistryException limited = assertThrows(RegistryException.class, () -> balancer.routeWithFailover(
                "orders", "ROUND_ROBIN", null, Set.of("a", "b"), 1));
        assertEquals(ReasonCode.RETRY_LIMIT_EXCEEDED, limited.getReasonCode());

        RegistryException exhausted = assertThrows(RegistryException.class, () -> balancer.routeWithFailover(
                "orders", "ROUND_ROBIN", null, Set.of("a", "b", "c"), 3));
        assertEquals(ReasonCode.RETRY_LIMIT_EXCEEDED, exhausted.getReasonCode());
    }

    @Test
    void concurrentSelectionCannotReturnAnInstanceAfterDeregistration() throws Exception {
        int threadCount = 8;
        CountDownLatch selectionInsideSnapshot = new CountDownLatch(threadCount);
        CountDownLatch allowRead = new CountDownLatch(1);
        ServiceRegistry registry = new ServiceRegistry(Clock.systemUTC()) {
            @Override
            public List<ServiceInstance> activeInstances(String serviceName) {
                List<ServiceInstance> snapshot = super.activeInstances(serviceName);
                selectionInsideSnapshot.countDown();
                try {
                    allowRead.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
                return snapshot;
            }
        };
        registry.register("orders", "a", "127.0.0.1", 18081, 1, 10_000);
        registry.register("orders", "b", "127.0.0.1", 18082, 1, 10_000);
        SelectionStrategy blockingRoundRobin = new SelectionStrategy() {
            @Override
            public String name() {
                return "ROUND_ROBIN";
            }

            @Override
            public ServiceInstance select(ServiceRegistry source, String serviceName, String requestKey,
                                         Set<String> excludedInstanceIds) {
                return source.activeInstances(serviceName).get(0);
            }
        };
        LoadBalancer balancer = new LoadBalancer(registry, List.of(blockingRoundRobin));

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<String>> futures = new ArrayList<>();
        for (int index = 0; index < threadCount; index++) {
            futures.add(executor.submit(() -> balancer.select("orders", "ROUND_ROBIN", null).instanceId()));
        }

        assertTrue(selectionInsideSnapshot.await(5, TimeUnit.SECONDS));
        registry.deregister("orders", "b");
        allowRead.countDown();

        Set<String> selected = new HashSet<>();
        for (Future<String> future : futures) {
            selected.add(future.get(5, TimeUnit.SECONDS));
        }
        executor.shutdownNow();

        assertEquals(Set.of("a"), selected);
    }

    private static LoadBalancer newLoadBalancer(ServiceRegistry registry) {
        return new LoadBalancer(registry, List.of(
                new RoundRobinStrategy(),
                new ConsistentHashStrategy()
        ));
    }
}
