package com.github.lechandonga.serviceregistryloadbalancerjava.registry;

/**
 * 暴露给负载均衡器的不可变实例视图：只有生命周期在线且健康探测未摘除的实例。
 */
public record InstanceView(String serviceName, String instanceId, String host, int port, int weight) {

    /** host:port 形式的物理地址，用于本地回环探测与解释日志。 */
    public String address() {
        return host + ":" + port;
    }
}
