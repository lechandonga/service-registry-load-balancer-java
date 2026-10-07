package com.github.lechandonga.serviceregistryloadbalancerjava.lb;

import com.github.lechandonga.serviceregistryloadbalancerjava.registry.InstanceView;
import java.util.List;

/**
 * 选路结论：最终命中实例（可能为空）、全部尝试轨迹、以及人类可读的解释。
 * 轨迹让「为什么选中它 / 为什么转移 / 为什么失败」完全可解释。
 */
public record RoutingDecision(
        String serviceName,
        Strategy strategy,
        String requestKey,
        long snapshotVersion,
        List<InstanceView> eligibleAtDecision,
        List<RoutingAttempt> attempts,
        InstanceView selected,
        String explanation) {

    public boolean available() {
        return selected != null;
    }
}
