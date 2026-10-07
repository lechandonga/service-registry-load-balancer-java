package com.github.lechandonga.serviceregistryloadbalancerjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 本机注册中心的可调参数，前缀 slb。 */
@ConfigurationProperties(prefix = "slb")
public class SlbProperties {

    /** 默认实例租约 TTL（毫秒）。 */
    private long leaseTtlMillis = 15_000;

    /** 租约扫描间隔（毫秒）。 */
    private long leaseSweepMillis = 2_000;

    /** 健康探测间隔（毫秒）。 */
    private long probeIntervalMillis = 1_000;

    /** 连续失败多少次摘除。 */
    private int failureThreshold = 3;

    /** 摘除后连续成功多少次恢复。 */
    private int successThreshold = 2;

    /** TCP 回环探测连接超时（毫秒）。 */
    private int probeConnectTimeoutMillis = 300;

    public long getLeaseTtlMillis() {
        return leaseTtlMillis;
    }

    public void setLeaseTtlMillis(long leaseTtlMillis) {
        this.leaseTtlMillis = leaseTtlMillis;
    }

    public long getLeaseSweepMillis() {
        return leaseSweepMillis;
    }

    public void setLeaseSweepMillis(long leaseSweepMillis) {
        this.leaseSweepMillis = leaseSweepMillis;
    }

    public long getProbeIntervalMillis() {
        return probeIntervalMillis;
    }

    public void setProbeIntervalMillis(long probeIntervalMillis) {
        this.probeIntervalMillis = probeIntervalMillis;
    }

    public int getFailureThreshold() {
        return failureThreshold;
    }

    public void setFailureThreshold(int failureThreshold) {
        this.failureThreshold = failureThreshold;
    }

    public int getSuccessThreshold() {
        return successThreshold;
    }

    public void setSuccessThreshold(int successThreshold) {
        this.successThreshold = successThreshold;
    }

    public int getProbeConnectTimeoutMillis() {
        return probeConnectTimeoutMillis;
    }

    public void setProbeConnectTimeoutMillis(int probeConnectTimeoutMillis) {
        this.probeConnectTimeoutMillis = probeConnectTimeoutMillis;
    }
}
