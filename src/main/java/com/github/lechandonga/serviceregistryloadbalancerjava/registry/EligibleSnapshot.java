package com.github.lechandonga.serviceregistryloadbalancerjava.registry;

import java.util.List;

/**
 * 某个服务在某一时刻的可路由实例不可变快照。
 * <p>每当可路由集合（实例增删、健康摘除/恢复、权重调整）变化时 version 递增；
 * 选路方持有快照期间看到的实例集合永远一致，不可能选到已摘除的实例。</p>
 */
public record EligibleSnapshot(long version, List<InstanceView> instances) {

    public EligibleSnapshot {
        instances = List.copyOf(instances);
    }

    public static EligibleSnapshot empty(long version) {
        return new EligibleSnapshot(version, List.of());
    }
}
