package com.github.lechandonga.serviceregistryloadbalancerjava.lb;

/** 选路策略。 */
public enum Strategy {
    /** 平滑加权轮询（Nginx SWRR）：不跳过可用实例、不重复命中，权重决定流量比例。 */
    ROUND_ROBIN,
    /** 按请求键的一致性哈希：相同键稳定落到同一实例，少量实例变动尽量保持归属。 */
    CONSISTENT_HASH
}
