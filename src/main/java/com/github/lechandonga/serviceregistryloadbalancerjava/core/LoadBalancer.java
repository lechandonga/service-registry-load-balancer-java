package com.github.lechandonga.serviceregistryloadbalancerjava.core;

import com.github.lechandonga.serviceregistryloadbalancerjava.dto.RouteAttempt;
import com.github.lechandonga.serviceregistryloadbalancerjava.dto.RouteResponse;
import com.github.lechandonga.serviceregistryloadbalancerjava.error.RegistryException;
import com.github.lechandonga.serviceregistryloadbalancerjava.model.ReasonCode;
import com.github.lechandonga.serviceregistryloadbalancerjava.model.ServiceInstance;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class LoadBalancer {
    private final ServiceRegistry registry;
    private final Map<String, SelectionStrategy> strategies;

    public LoadBalancer(ServiceRegistry registry, List<SelectionStrategy> strategies) {
        this.registry = registry;
        this.strategies = strategies.stream()
                .map(strategy -> Map.entry(strategy.name(), strategy))
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, Map.Entry::getValue));
    }

    public ServiceInstance select(String serviceName, String strategyName, String requestKey) {
        return strategy(strategyName).select(registry, serviceName, requestKey, Set.of());
    }

    public RouteResponse routeWithFailover(
            String serviceName,
            String strategyName,
            String requestKey,
            Set<String> failedInstanceIds,
            int maxAttempts
    ) {
        if (maxAttempts < 1) {
            throw new RegistryException(ReasonCode.INVALID_REQUEST, "maxAttempts must be positive");
        }

        SelectionStrategy strategy = strategy(strategyName);
        Set<String> excluded = new LinkedHashSet<>(failedInstanceIds);
        List<RouteAttempt> attempts = new ArrayList<>();
        boolean hasCandidate = registry.activeInstances(serviceName).stream()
                .anyMatch(instance -> !excluded.contains(instance.instanceId()));
        if (!hasCandidate || maxAttempts <= excluded.size()) {
            attempts.add(new RouteAttempt(1, null, "LIMITED",
                    "remaining candidate would exceed maxAttempts or all active candidates were excluded"));
            throw new RegistryException(ReasonCode.RETRY_LIMIT_EXCEEDED,
                    "failover stopped at " + maxAttempts + " total attempt(s)");
        }
        ServiceInstance selected = strategy.select(registry, serviceName, requestKey, excluded);
        attempts.add(new RouteAttempt(1, selected.instanceId(), "SELECTED",
                "active instance selected after " + excluded.size() + " known failure(s); "
                        + maxAttempts + " new attempt(s) allowed"));
        return new RouteResponse(serviceName, strategy.name(), requestKey, selected,
                List.copyOf(attempts), false);
    }

    private SelectionStrategy strategy(String strategyName) {
        String normalized = strategyName == null ? "ROUND_ROBIN" : strategyName.trim().toUpperCase();
        SelectionStrategy strategy = strategies.get(normalized);
        if (strategy == null) {
            throw new RegistryException(ReasonCode.UNSUPPORTED_STRATEGY,
                    "unsupported strategy: " + strategyName);
        }
        return strategy;
    }
}
