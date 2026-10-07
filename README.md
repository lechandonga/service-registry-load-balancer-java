# 本机服务注册与负载均衡（纯内存 + 本地回环）

一个完全运行在本机 JVM 内的服务注册中心与负载均衡器：用内存状态保存实例注册表，
用本地回环端口（`127.0.0.1:<port>` 的 TCP 连通性）模拟实例健康，
不依赖任何数据库、注册中心、消息队列等外部服务或中间件。

## 能力总览

- 实例注册、注销、周期续约（心跳租约），拒绝原因可区分、状态全程可审计。
- 两种选路策略：平滑加权轮询（ROUND_ROBIN）、按请求键一致性哈希（CONSISTENT_HASH）。
- 后台健康探测：连续失败达阈值摘除、连续成功达阈值恢复，短暂抖动不抖动上下线。
- 有上限的故障转移：对不健康实例重试/转移不超过预算，选路结论带完整解释轨迹。
- 注册、查询、选路三个入口结论线性一致：摘除/注销生效后任何选路都拿不到该实例。

## 模块结构

| 包 | 关键类 | 职责 |
| --- | --- | --- |
| `registry` | `ServiceRegistry` | 内存注册表：注册/续约/注销/租约扫描/探测落账，按服务名加锁并维护不可变快照 |
| `registry` | `InstanceRecord` / `InstanceEvent` | 实例状态与从注册到注销的完整事件历史 |
| `registry` | `EligibleSnapshot` / `InstanceView` | 某时刻可路由实例的不可变快照 |
| `registry` | `LeaseReaper` | 周期扫描过期租约 |
| `lb` | `Router` | 轮询 / 一致性哈希选路 + 有界故障转移 + 可解释决策 |
| `health` | `HealthProbeService` / `TcpLoopbackProbeChecker` | 周期探测与迟滞阈值判定；回环 TCP 探测 |
| `web` | `RegistryController` | 注册/查询/续约/注销/权重/选路 HTTP 入口 |
| `config` | `SlbConfiguration` / `SlbProperties` | Spring 装配与参数（前缀 `slb`） |

## 注册与续约规则

1. **注册** `POST /api/services/{service}/instances`
   - 请求体：`instanceId`、`host`、`port`（1-65535）、可选 `weight`（≥1，默认 1）、可选 `ttlMillis`（默认 `slb.lease-ttl-millis`）。
   - 对租约仍有效的同名实例重复注册 → `409 ALREADY_REGISTERED`。
   - 已过期（`EXPIRED`）或已注销（`DEREGISTERED`）的同名实例允许重新注册（状态翻回 `ONLINE`，历史保留）。
2. **续约** `POST /api/services/{service}/instances/{id}/renew`
   - 刷新 `lastHeartbeat`。实例不存在或已注销 → `404 INSTANCE_NOT_FOUND`；
     租约已过期再续约 → `410 LEASE_EXPIRED`（必须重新注册）。
3. **注销** `POST /api/services/{service}/instances/{id}/deregister`
   - 立即从可路由快照摘除；重复注销 / 注销不存在实例 → `404 INSTANCE_NOT_FOUND`。
4. **租约**：`LeaseReaper` 周期性扫描，`now - lastHeartbeat > ttl` 的 `ONLINE`
   实例被标记 `EXPIRED` 并立即摘出快照。后台扫描与显式续约/注销在同一把服务锁内串行。
5. **可查询**：
   - `GET /api/services/{service}/instances`：当前可路由实例（与选路口径一致，带 `snapshotVersion`）。
   - `GET /api/services/{service}/instances/{id}`：生命周期状态、`serving`、连续成败计数、完整事件历史。

生命周期三态 `ONLINE / EXPIRED / DEREGISTERED` 与“是否可服务”（`serving`）正交：
健康摘除只改 `serving`，不改生命周期，因此探测抖动不会污染租约语义。

## 选路策略语义

选路入口：`POST /api/services/{service}/route`，请求体
`{"strategy":"ROUND_ROBIN|CONSISTENT_HASH","key":"...","maxAttempts":3}`。
响应含 `selected`、`snapshotVersion`、`eligibleAtDecision`、`attempts`（每次尝试的实例与原因）、`explanation`。

- **平滑加权轮询（Nginx SWRR）**：每个可用实例在一轮内恰好命中与其权重相等的次数，
  顺序平滑；不会跳过可用实例，也不会在一轮内重复命中。实例上下线或权重调整使快照版本递增，
  游标按新实例集合重置，语义始终正确。
- **一致性哈希**：FNV-1a 64 位哈希，每实例 `weight × 160` 个虚拟节点分布在环上；
  相同请求键稳定路由到同一实例。摘除/加入少量实例时，只有受影响键段发生迁移，其余键归属保持不变
  （单测验证 8 节点摘除 1 个时迁移比例约 1/8，远小于全量洗牌）。
- **故障转移**：调用方可提供实例健康判定（如真实连接），返回不健康时按策略继续选下一个候选，
  尝试次数（含首次）不超过 `maxAttempts`（且不超过候选数），超过后返回空结论并在 `explanation`
  中说明 `failover budget exhausted`，不会在故障实例上无限打转。

## 一致性保证

- 每个服务名一把锁；注册、续约、注销、租约扫描、探测落账、快照重建都在该锁内完成。
- 选路通过 `ServiceRegistry.withServiceLock(...)` 在同一把锁内“读最新快照 → 选择 → 返回”，
  与摘除/注销互斥；快照本身不可变。因此选路返回的实例必然仍在当前可路由集合中，
  并发探测/注销时不可能选中已摘除实例（单测 `concurrentRoutingNeverPicksEvictedInstance` 验证）。

## 故障判定方式（探测迟滞）

- 探测目标：所有 `ONLINE` 实例（包括已被健康摘除、等待恢复的实例）。
- 默认 `TcpLoopbackProbeChecker` 对 `host:port` 建立短 TCP 连接，连通即健康；
  用 `new ServerSocket(port, backlog, InetAddress.getByName("127.0.0.1"))` 即可模拟实例上线，关闭即下线。
- **摘除**：连续失败次数 ≥ `slb.failure-threshold`（默认 3）才置 `serving=false`；中途一次成功清零。
- **恢复**：摘除后连续成功次数 ≥ `slb.success-threshold`（默认 2）才置 `serving=true`；中途一次失败清零。
- 因此单次或少量抖动不会导致实例反复上下线；摘除/恢复事件都会写入实例历史（`HEALTH_SUSPENDED` / `HEALTH_RESUMED`）。

## 参数（`application.properties`）

| 配置 | 默认值 | 含义 |
| --- | --- | --- |
| `slb.lease-ttl-millis` | 15000 | 默认租约 TTL |
| `slb.lease-sweep-millis` | 2000 | 租约扫描间隔 |
| `slb.probe-interval-millis` | 1000 | 健康探测间隔 |
| `slb.failure-threshold` | 3 | 连续失败摘除阈值 |
| `slb.success-threshold` | 2 | 连续成功恢复阈值 |
| `slb.probe-connect-timeout-millis` | 300 | 回环 TCP 探测超时 |

## 本地验证步骤

```bash
# 1. 运行全部单测（需要 JDK 21+；已在 JDK 上验证）
mvn test

# 2. 启动应用（HTTP 端口随机，见启动日志 Tomcat started on port ...）
mvn spring-boot:run
```

手工验证（端口以实际为准，可临时设 server.port=8080）：

```bash
# 注册两个监听在回环端口的实例
curl -s -XPOST localhost:8080/api/services/demo/instances -H 'Content-Type: application/json' \
  -d '{"instanceId":"n1","host":"127.0.0.1","port":20001,"weight":1}'
curl -s -XPOST localhost:8080/api/services/demo/instances -H 'Content-Type: application/json' \
  -d '{"instanceId":"n2","host":"127.0.0.1","port":20002,"weight":2}'

# 轮询 / 一致性哈希选路
curl -s -XPOST localhost:8080/api/services/demo/route -H 'Content-Type: application/json' \
  -d '{"strategy":"ROUND_ROBIN"}'
curl -s -XPOST localhost:8080/api/services/demo/route -H 'Content-Type: application/json' \
  -d '{"strategy":"CONSISTENT_HASH","key":"user-1","maxAttempts":3}'

# 续约、查询状态/历史、注销
curl -s -XPOST localhost:8080/api/services/demo/instances/n1/renew -d '{}'
curl -s localhost:8080/api/services/demo/instances/n1
curl -s -XPOST localhost:8080/api/services/demo/instances/n1/deregister
```

模拟实例上下线：在本机用 `nc -l 127.0.0.1 20001`（或任意 TCP 监听）占用端口即“健康”，
停止监听后约 `failure-threshold × probe-interval` 被摘除；重新监听后约
`success-threshold × probe-interval` 恢复。

## 测试覆盖与日志

`mvn test` 共 19 个用例，按业务分为四组，日志以统一横幅打印每个业务用例的名称与结果
（`TEST-COVERAGE` logger，见 `TestLogExtension`），并同时输出注册/续约/摘除/选路的业务事件：

- `RegistryLifecycleTest`（6）：注册生命周期——正常注册、重复注册拒绝、续约、过期续约拒绝、
  注销规则与注销后不可选路、租约自动摘除、非法参数。
- `LoadBalancerTest`（7）：轮询不跳过/不重复、加权比例、权重热更新、
  一致性哈希稳定与最小迁移、16 线程并发选路与摘除、故障转移上限与解释。
- `HealthProbeTest`（4）：短暂抖动不摘除、连续失败阈值摘除、偶发成功不恢复、
  连续成功阈值恢复，以及真实回环 TCP 端口的端到端摘除/恢复。
- `RegistryApiIntegrationTest`（1，Spring + MockMvc）：HTTP 全链路与错误原因码。
