package com.github.lechandonga.serviceregistryloadbalancerjava.web;

/**
 * 选路请求体。
 *
 * @param strategy    ROUND_ROBIN 或 CONSISTENT_HASH
 * @param key         CONSISTENT_HASH 的请求键
 * @param maxAttempts 故障转移最大尝试次数（含首次），默认 3
 */
public record RouteRequest(String strategy, String key, Integer maxAttempts) {
}
