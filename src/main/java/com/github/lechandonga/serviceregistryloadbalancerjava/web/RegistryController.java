package com.github.lechandonga.serviceregistryloadbalancerjava.web;

import com.github.lechandonga.serviceregistryloadbalancerjava.config.RegistryProperties;
import com.github.lechandonga.serviceregistryloadbalancerjava.core.LoadBalancer;
import com.github.lechandonga.serviceregistryloadbalancerjava.core.ServiceRegistry;
import com.github.lechandonga.serviceregistryloadbalancerjava.dto.RegisterInstanceRequest;
import com.github.lechandonga.serviceregistryloadbalancerjava.dto.RouteRequest;
import com.github.lechandonga.serviceregistryloadbalancerjava.dto.RouteResponse;
import com.github.lechandonga.serviceregistryloadbalancerjava.dto.UpdateWeightRequest;
import com.github.lechandonga.serviceregistryloadbalancerjava.error.RegistryException;
import com.github.lechandonga.serviceregistryloadbalancerjava.model.ReasonCode;
import com.github.lechandonga.serviceregistryloadbalancerjava.model.RegistryEvent;
import com.github.lechandonga.serviceregistryloadbalancerjava.model.ServiceInstance;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class RegistryController {
    private final ServiceRegistry registry;
    private final LoadBalancer loadBalancer;
    private final RegistryProperties properties;

    public RegistryController(
            ServiceRegistry registry,
            LoadBalancer loadBalancer,
            RegistryProperties properties
    ) {
        this.registry = registry;
        this.loadBalancer = loadBalancer;
        this.properties = properties;
    }

    @PostMapping("/services/{serviceName}/instances")
    @ResponseStatus(HttpStatus.CREATED)
    public ServiceInstance register(
            @PathVariable String serviceName,
            @RequestBody RegisterInstanceRequest request
    ) {
        validate(request != null, "request body is required");
        return registry.register(
                serviceName,
                required(request.instanceId(), "instanceId"),
                required(request.host(), "host"),
                required(request.port(), "port"),
                request.weight() == null ? 1 : request.weight(),
                request.ttlMillis() == null ? properties.getDefaultTtlMillis() : request.ttlMillis()
        );
    }

    @PostMapping("/services/{serviceName}/instances/{instanceId}/heartbeat")
    public ServiceInstance heartbeat(@PathVariable String serviceName, @PathVariable String instanceId) {
        return registry.renew(serviceName, instanceId);
    }

    @DeleteMapping("/services/{serviceName}/instances/{instanceId}")
    public ServiceInstance deregister(@PathVariable String serviceName, @PathVariable String instanceId) {
        return registry.deregister(serviceName, instanceId);
    }

    @PutMapping("/services/{serviceName}/instances/{instanceId}/weight")
    public ServiceInstance updateWeight(
            @PathVariable String serviceName,
            @PathVariable String instanceId,
            @RequestBody UpdateWeightRequest request
    ) {
        validate(request != null && request.weight() != null, "weight is required");
        return registry.updateWeight(serviceName, instanceId, request.weight());
    }

    @GetMapping("/services/{serviceName}/instances")
    public List<ServiceInstance> listService(@PathVariable String serviceName) {
        return registry.listService(serviceName);
    }

    @GetMapping("/services/{serviceName}/instances/{instanceId}")
    public ServiceInstance getInstance(@PathVariable String serviceName, @PathVariable String instanceId) {
        return registry.getInstance(serviceName, instanceId)
                .orElseThrow(() -> new RegistryException(ReasonCode.INSTANCE_NOT_FOUND,
                        "instance does not exist"));
    }

    @GetMapping("/instances")
    public List<ServiceInstance> listInstances() {
        return registry.listInstances();
    }

    @GetMapping("/events")
    public List<RegistryEvent> events() {
        return registry.recentEvents();
    }

    @PostMapping("/services/{serviceName}/routes")
    public RouteResponse route(@PathVariable String serviceName, @RequestBody(required = false) RouteRequest request) {
        RouteRequest safeRequest = request == null
                ? new RouteRequest("ROUND_ROBIN", null, List.of(), 1)
                : request;
        int maxAttempts = safeRequest.maxAttempts() == null ? 1 : safeRequest.maxAttempts();
        List<String> failedIds = safeRequest.failedInstanceIds() == null
                ? List.of()
                : safeRequest.failedInstanceIds();
        return loadBalancer.routeWithFailover(
                serviceName,
                safeRequest.strategy(),
                safeRequest.requestKey(),
                java.util.Set.copyOf(failedIds),
                maxAttempts
        );
    }

    private static String required(String value, String field) {
        validate(value != null && !value.isBlank(), field + " is required");
        return value;
    }

    private static int required(Integer value, String field) {
        validate(value != null, field + " is required");
        return value;
    }

    private static void validate(boolean condition, String message) {
        if (!condition) {
            throw new RegistryException(ReasonCode.INVALID_REQUEST, message);
        }
    }
}
