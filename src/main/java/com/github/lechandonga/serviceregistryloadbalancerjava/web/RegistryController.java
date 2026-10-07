package com.github.lechandonga.serviceregistryloadbalancerjava.web;

import com.github.lechandonga.serviceregistryloadbalancerjava.lb.RoutingDecision;
import com.github.lechandonga.serviceregistryloadbalancerjava.lb.Strategy;
import com.github.lechandonga.serviceregistryloadbalancerjava.registry.InstanceRecord;
import com.github.lechandonga.serviceregistryloadbalancerjava.registry.RegistryErrorCode;
import com.github.lechandonga.serviceregistryloadbalancerjava.registry.RegistryException;
import com.github.lechandonga.serviceregistryloadbalancerjava.registry.ServiceRegistry;
import com.github.lechandonga.serviceregistryloadbalancerjava.lb.Router;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 注册 / 查询 / 选路的 HTTP 入口。所有结论都来自同一个内存注册中心，
 * 错误响应带可区分的 errorCode，选路响应带完整尝试轨迹与解释。
 */
@RestController
@RequestMapping("/api")
public class RegistryController {

    private final ServiceRegistry registry;
    private final Router router;

    public RegistryController(ServiceRegistry registry, Router router) {
        this.registry = registry;
        this.router = router;
    }

    /** 注册实例。重复注册返回 409 ALREADY_REGISTERED。 */
    @PostMapping("/services/{serviceName}/instances")
    public Map<String, Object> register(@PathVariable String serviceName,
                                        @RequestBody RegisterRequest request) {
        requireBody(request, "request body");
        requireText(request.instanceId(), "instanceId");
        requireText(request.host(), "host");
        if (request.port() == null) {
            throw new RegistryException(RegistryErrorCode.INVALID_REQUEST, "port is required");
        }
        int weight = request.weight() == null ? 1 : request.weight();
        long ttl = registry.register(serviceName, request.instanceId(), request.host(),
                request.port(), weight, request.ttlMillis());
        return Map.of("serviceName", serviceName, "instanceId", request.instanceId(),
                "registered", true, "leaseTtlMillis", ttl);
    }

    /** 周期续约。不存在返回 404 INSTANCE_NOT_FOUND，已过期返回 410 LEASE_EXPIRED。 */
    @PostMapping("/services/{serviceName}/instances/{instanceId}/renew")
    public Map<String, Object> renew(@PathVariable String serviceName,
                                     @PathVariable String instanceId,
                                     @RequestBody(required = false) RenewRequest ignored) {
        registry.renew(serviceName, instanceId);
        return Map.of("serviceName", serviceName, "instanceId", instanceId, "renewed", true);
    }

    /** 主动注销。不存在返回 404 INSTANCE_NOT_FOUND。 */
    @PostMapping("/services/{serviceName}/instances/{instanceId}/deregister")
    public Map<String, Object> deregister(@PathVariable String serviceName,
                                          @PathVariable String instanceId) {
        registry.deregister(serviceName, instanceId);
        return Map.of("serviceName", serviceName, "instanceId", instanceId, "deregistered", true);
    }

    /** 调整在线实例权重。 */
    @PostMapping("/services/{serviceName}/instances/{instanceId}/weight")
    public Map<String, Object> weight(@PathVariable String serviceName,
                                      @PathVariable String instanceId,
                                      @RequestBody WeightRequest request) {
        requireBody(request, "request body");
        if (request.weight() == null) {
            throw new RegistryException(RegistryErrorCode.INVALID_REQUEST, "weight is required");
        }
        registry.setWeight(serviceName, instanceId, request.weight());
        return Map.of("serviceName", serviceName, "instanceId", instanceId,
                "weight", request.weight());
    }

    /** 查询服务当前可路由实例（选路口径，与快照一致）。 */
    @GetMapping("/services/{serviceName}/instances")
    public Map<String, Object> list(@PathVariable String serviceName) {
        var snapshot = registry.snapshot(serviceName);
        return Map.of("serviceName", serviceName, "snapshotVersion", snapshot.version(),
                "eligibleInstances", snapshot.instances());
    }

    /** 查询单个实例的生命周期与事件历史。 */
    @GetMapping("/services/{serviceName}/instances/{instanceId}")
    public InstanceStatusResponse status(@PathVariable String serviceName,
                                         @PathVariable String instanceId) {
        InstanceRecord record = registry.findRecord(serviceName, instanceId);
        if (record == null) {
            throw new RegistryException(RegistryErrorCode.INSTANCE_NOT_FOUND,
                    "no instance '" + instanceId + "' of service '" + serviceName + "'");
        }
        return InstanceStatusResponse.from(record);
    }

    /** 选路：默认不传健康探测，仅按策略从当前快照选实例并返回解释。 */
    @PostMapping("/services/{serviceName}/route")
    public RoutingDecision route(@PathVariable String serviceName,
                                 @RequestBody(required = false) RouteRequest request) {
        Strategy strategy = Strategy.ROUND_ROBIN;
        String key = null;
        int maxAttempts = 3;
        if (request != null) {
            if (request.strategy() != null) {
                try {
                    strategy = Strategy.valueOf(request.strategy().trim().toUpperCase());
                } catch (IllegalArgumentException e) {
                    throw new RegistryException(RegistryErrorCode.INVALID_REQUEST,
                            "unknown strategy '" + request.strategy()
                                    + "', expected ROUND_ROBIN or CONSISTENT_HASH");
                }
            }
            key = request.key();
            if (request.maxAttempts() != null) {
                maxAttempts = request.maxAttempts();
            }
        }
        RoutingDecision decision = router.route(serviceName, strategy, key, null, maxAttempts);
        return decision;
    }

    @ExceptionHandler(RegistryException.class)
    public ResponseEntity<Map<String, Object>> handle(RegistryException ex) {
        HttpStatus status = switch (ex.getErrorCode()) {
            case ALREADY_REGISTERED -> HttpStatus.CONFLICT;
            case INSTANCE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case LEASE_EXPIRED -> HttpStatus.GONE;
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
        };
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errorCode", ex.getErrorCode().name());
        body.put("message", ex.getMessage());
        return ResponseEntity.status(status).body(body);
    }

    private static void requireBody(Object body, String what) {
        if (body == null) {
            throw new RegistryException(RegistryErrorCode.INVALID_REQUEST, what + " is required");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new RegistryException(RegistryErrorCode.INVALID_REQUEST,
                    field + " must not be blank");
        }
    }
}
