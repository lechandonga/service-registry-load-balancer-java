package com.github.lechandonga.serviceregistryloadbalancerjava.registry;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个服务实例的完整状态记录。一个实例由 (serviceName, instanceId) 唯一确定。
 * 所有对该记录的读写都必须在 ServiceRegistry 的实例锁保护下进行。
 */
public final class InstanceRecord {

    private final String serviceName;
    private final String instanceId;
    private final String host;
    private final int port;

    private int weight;
    private long registeredAt;
    private long lastHeartbeat;
    private long leaseTtlMillis;
    private InstanceState state;

    /** 是否可对外服务。健康探测连续失败到阈值时置 false，连续成功到阈值后恢复 true。 */
    private boolean serving;

    private int consecutiveFailures;
    private int consecutiveSuccesses;

    private final List<InstanceEvent> history = new ArrayList<>();

    public InstanceRecord(String serviceName, String instanceId, String host, int port,
                          int weight, long now, long leaseTtlMillis) {
        this.serviceName = serviceName;
        this.instanceId = instanceId;
        this.host = host;
        this.port = port;
        this.weight = weight;
        this.registeredAt = now;
        this.lastHeartbeat = now;
        this.leaseTtlMillis = leaseTtlMillis;
        this.state = InstanceState.ONLINE;
        this.serving = true;
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public int getWeight() {
        return weight;
    }

    void setWeight(int weight) {
        this.weight = weight;
    }

    public long getRegisteredAt() {
        return registeredAt;
    }

    void setRegisteredAt(long registeredAt) {
        this.registeredAt = registeredAt;
    }

    public long getLastHeartbeat() {
        return lastHeartbeat;
    }

    void setLastHeartbeat(long lastHeartbeat) {
        this.lastHeartbeat = lastHeartbeat;
    }

    public long getLeaseTtlMillis() {
        return leaseTtlMillis;
    }

    void setLeaseTtlMillis(long leaseTtlMillis) {
        this.leaseTtlMillis = leaseTtlMillis;
    }

    public InstanceState getState() {
        return state;
    }

    void setState(InstanceState state) {
        this.state = state;
    }

    public boolean isServing() {
        return serving;
    }

    void setServing(boolean serving) {
        this.serving = serving;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    void setConsecutiveFailures(int consecutiveFailures) {
        this.consecutiveFailures = consecutiveFailures;
    }

    public int getConsecutiveSuccesses() {
        return consecutiveSuccesses;
    }

    void setConsecutiveSuccesses(int consecutiveSuccesses) {
        this.consecutiveSuccesses = consecutiveSuccesses;
    }

    public List<InstanceEvent> getHistory() {
        return history;
    }

    void appendEvent(long now, InstanceEventType type, String detail) {
        history.add(new InstanceEvent(now, type, detail));
    }

    /** 租约是否已经过期。 */
    boolean leaseExpired(long now) {
        return now - lastHeartbeat > leaseTtlMillis;
    }
}
