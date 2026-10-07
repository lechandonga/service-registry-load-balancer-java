# 本机服务注册与负载均衡

这是一个只依赖 Spring Boot、内存数据结构和本机回环端口的服务注册、健康探测和选路示例。所有实例状态保存在单进程内存中，不使用数据库、Redis、注册中心集群或其他外部中间件。

## 领域状态

实例在注册中心内只有四种互斥状态：

- `ACTIVE`：租约有效且可被选路。
- `OUT_OF_SERVICE`：连续健康探测失败达到阈值，暂时摘除，不参与选路；恢复阈值满足后回到 `ACTIVE`。
- `EXPIRED`：超过 TTL 未续约，进入终态，不能续约，也不能用同一个实例 ID 重新注册。
- `DEREGISTERED`：显式注销后的终态，不参与选路，不能续约或重新注册同 ID 实例。

状态和最近 500 条事件都保存在 `ServiceRegistry` 中。注册、续约、注销、租约过期、探测摘除和恢复都通过同一把读写写锁原子更新；查询和选路读取的是锁保护下的不可变 `ServiceInstance` 快照。

## 注册与续约规则

- 只允许注册回环地址：`127.0.0.1`、`localhost` 或解析为本机 loopback 的地址。
- `serviceName`、`instanceId`、`host`、`port` 必填；`weight >= 1`，TTL 必须为正数。
- 重复注册返回 `INSTANCE_ALREADY_REGISTERED`。
- 操作不存在的实例返回 `INSTANCE_NOT_FOUND`，可与终态错误区分。
- 对 `EXPIRED` 实例续约返回 `INSTANCE_EXPIRED`。
- 对 `DEREGISTERED` 实例续约或再次注销返回 `INSTANCE_DEREGISTERED`。
- 续约只延长当前租约，不改变实例状态、权重或探测计数。
- 后台任务按固定周期扫描租约；超过 `lastHeartbeatAt + ttlMillis` 的 `ACTIVE` 或 `OUT_OF_SERVICE` 实例会被标记为 `EXPIRED`。
- 终态记录仍可通过实例查询和事件列表查看，但任何选路入口都无法选中。

## 选路策略语义

提供两种策略：

- `ROUND_ROBIN`：严格等权轮询。每个服务维护一个单调游标；每次只从当前 `ACTIVE` 快照中取候选。一个完整周期内每个可用实例恰好命中一次，不会跳过可用实例；摘除或恢复后，游标在新的候选集合上继续取模。权重不影响该策略。
- `CONSISTENT_HASH`：按请求键一致性哈希。每个实例根据权重生成 `weight * 32` 个 FNV-1a 虚拟节点并放入有序环，请求键顺时针找最近节点。实例少量增减时，没有落到变动节点区间的键保持原归属；权重越大，虚拟节点越多。

`CONSISTENT_HASH` 必须提供非空 `requestKey`，否则返回 `REQUEST_KEY_REQUIRED`。没有可用实例时返回 `NO_AVAILABLE_INSTANCE`。

## 故障判定与转移上限

后台探测使用 TCP 连接本机 `host:port`：

- `ACTIVE` 实例每次失败增加连续失败计数。
- 连续失败次数达到 `local-registry.failure-threshold` 才转为 `OUT_OF_SERVICE`。
- 单次或短时间失败后再来一次成功，会清空失败计数，不会因抖动反复上下线。
- `OUT_OF_SERVICE` 实例每次成功增加连续成功计数。
- 连续成功次数达到 `local-registry.recovery-threshold` 才恢复为 `ACTIVE`。
- 恢复期间任何一次失败都会把连续成功计数重置为 0。

带故障转移的路由请求可以传入 `failedInstanceIds` 和总尝试上限 `maxAttempts`。注册中心会先排除已失败实例，再从剩余 `ACTIVE` 实例中选路；如果没有剩余候选，或“已失败次数 + 1 次新尝试”会超过 `maxAttempts`，返回 `RETRY_LIMIT_EXCEEDED`。因此调用方不会围绕同一个故障实例无限重试。

## REST API

基础路径：`/api/v1`。

### 注册

```bash
curl -s -X POST http://127.0.0.1:8080/api/v1/services/orders/instances \
  -H 'Content-Type: application/json' \
  -d '{"instanceId":"orders-1","host":"127.0.0.1","port":18081,"weight":1,"ttlMillis":15000}'
```

### 续约

```bash
curl -s -X POST http://127.0.0.1:8080/api/v1/services/orders/instances/orders-1/heartbeat
```

### 注销

```bash
curl -s -X DELETE http://127.0.0.1:8080/api/v1/services/orders/instances/orders-1
```

### 调整权重

```bash
curl -s -X PUT http://127.0.0.1:8080/api/v1/services/orders/instances/orders-1/weight \
  -H 'Content-Type: application/json' \
  -d '{"weight":3}'
```

### 查询状态

```bash
curl -s http://127.0.0.1:8080/api/v1/services/orders/instances
curl -s http://127.0.0.1:8080/api/v1/services/orders/instances/orders-1
curl -s http://127.0.0.1:8080/api/v1/instances
curl -s http://127.0.0.1:8080/api/v1/events
```

### 普通选路

```bash
curl -s -X POST http://127.0.0.1:8080/api/v1/services/orders/routes \
  -H 'Content-Type: application/json' \
  -d '{"strategy":"ROUND_ROBIN"}'

curl -s -X POST http://127.0.0.1:8080/api/v1/services/orders/routes \
  -H 'Content-Type: application/json' \
  -d '{"strategy":"CONSISTENT_HASH","requestKey":"user-42"}'
```

### 有上限故障转移

```bash
curl -s -X POST http://127.0.0.1:8080/api/v1/services/orders/routes \
  -H 'Content-Type: application/json' \
  -d '{"strategy":"CONSISTENT_HASH","requestKey":"user-42","failedInstanceIds":["orders-1"],"maxAttempts":2}'
```

## 本地验证

运行单元测试：

```bash
mvn test
```

控制台会按业务输出覆盖日志：

- `[业务=注册生命周期]`：注册、查询、续约、TTL、注销、重复和不存在等错误原因。
- `[业务=并发选路与策略语义]`：严格轮询、一致性哈希稳定、有限故障转移、注销与并发选路互斥。
- `[业务=探测抖动摘除与恢复]`：短抖动不摘除、连续失败摘除、连续成功恢复、恢复期间失败重置。

当前测试汇总为 11 个用例全部通过。核心测试直接使用 JDK `ServerSocket(0)` 申请空闲回环端口，不需要手工启动外部服务。

启动应用：

```bash
mvn spring-boot:run
```

默认配置：

- TTL：15000ms。
- 租约扫描：500ms。
- TCP 探测：1000ms 一次，连接超时 200ms。
- 摘除阈值：连续失败 3 次。
- 恢复阈值：连续成功 2 次。
