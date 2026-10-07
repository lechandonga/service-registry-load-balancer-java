package com.github.lechandonga.serviceregistryloadbalancerjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "local-registry")
public class RegistryProperties {
    private long defaultTtlMillis = 15_000;
    private long leaseSweepIntervalMillis = 500;
    private long probeIntervalMillis = 1_000;
    private int probeConnectTimeoutMillis = 200;
    private int failureThreshold = 3;
    private int recoveryThreshold = 2;

    public long getDefaultTtlMillis() {
        return defaultTtlMillis;
    }

    public void setDefaultTtlMillis(long defaultTtlMillis) {
        this.defaultTtlMillis = defaultTtlMillis;
    }

    public long getLeaseSweepIntervalMillis() {
        return leaseSweepIntervalMillis;
    }

    public void setLeaseSweepIntervalMillis(long leaseSweepIntervalMillis) {
        this.leaseSweepIntervalMillis = leaseSweepIntervalMillis;
    }

    public long getProbeIntervalMillis() {
        return probeIntervalMillis;
    }

    public void setProbeIntervalMillis(long probeIntervalMillis) {
        this.probeIntervalMillis = probeIntervalMillis;
    }

    public int getProbeConnectTimeoutMillis() {
        return probeConnectTimeoutMillis;
    }

    public void setProbeConnectTimeoutMillis(int probeConnectTimeoutMillis) {
        this.probeConnectTimeoutMillis = probeConnectTimeoutMillis;
    }

    public int getFailureThreshold() {
        return failureThreshold;
    }

    public void setFailureThreshold(int failureThreshold) {
        this.failureThreshold = failureThreshold;
    }

    public int getRecoveryThreshold() {
        return recoveryThreshold;
    }

    public void setRecoveryThreshold(int recoveryThreshold) {
        this.recoveryThreshold = recoveryThreshold;
    }
}
