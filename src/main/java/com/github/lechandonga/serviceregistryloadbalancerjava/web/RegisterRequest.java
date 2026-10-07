package com.github.lechandonga.serviceregistryloadbalancerjava.web;

/** 注册 / 重新注册请求体。ttlMillis 省略时使用服务端默认租约。 */
public record RegisterRequest(String instanceId, String host, Integer port,
                              Integer weight, Long ttlMillis) {
}
