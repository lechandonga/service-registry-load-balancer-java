package com.github.lechandonga.serviceregistryloadbalancerjava.lb;

import com.github.lechandonga.serviceregistryloadbalancerjava.registry.InstanceView;

/**
 * 一次选路尝试的解释记录：尝试了哪个实例、结果如何。
 *
 * @param view     被尝试的实例
 * @param accepted 是否被调用方接受（true=最终命中；false=该实例不健康，发生转移）
 * @param reason   被跳过/接受的原因
 */
public record RoutingAttempt(InstanceView view, boolean accepted, String reason) {
}
