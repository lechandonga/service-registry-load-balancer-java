package com.github.lechandonga.serviceregistryloadbalancerjava.health;

import com.github.lechandonga.serviceregistryloadbalancerjava.registry.InstanceRecord;

/**
 * 实例可达性探测抽象。默认实现走本地回环 TCP 连接，测试可注入内存假实现，
 * 整个系统不依赖任何外部服务或中间件。
 */
@FunctionalInterface
public interface ProbeChecker {

    /** @return true 表示实例当前可连通（健康）。 */
    boolean isHealthy(InstanceRecord record);
}
