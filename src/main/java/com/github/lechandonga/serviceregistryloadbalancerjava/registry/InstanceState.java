package com.github.lechandonga.serviceregistryloadbalancerjava.registry;

/**
 * 实例的生命周期状态。
 * <ul>
 *     <li>{@link #ONLINE}：已注册、租约有效，可能正在服务或被探测摘除；</li>
 *     <li>{@link #EXPIRED}：超过租约未续约，被注册中心摘除；</li>
 *     <li>{@link #DEREGISTERED}：实例主动注销。</li>
 * </ul>
 * 注意：健康探测摘除不改变生命周期，只改变 {@code serving} 标志。
 */
public enum InstanceState {
    ONLINE,
    EXPIRED,
    DEREGISTERED
}
