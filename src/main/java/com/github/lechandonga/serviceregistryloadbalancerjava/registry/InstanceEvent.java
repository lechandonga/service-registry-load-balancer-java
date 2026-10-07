package com.github.lechandonga.serviceregistryloadbalancerjava.registry;

/**
 * 实例上发生的一次可审计事件。
 *
 * @param epochMillis 事件发生时间
 * @param type        事件类型
 * @param detail      人类可读的解释，例如失败次数、原因码
 */
public record InstanceEvent(long epochMillis, InstanceEventType type, String detail) {
}
