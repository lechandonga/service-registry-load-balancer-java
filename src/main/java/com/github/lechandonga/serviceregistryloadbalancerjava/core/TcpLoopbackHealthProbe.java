package com.github.lechandonga.serviceregistryloadbalancerjava.core;

import com.github.lechandonga.serviceregistryloadbalancerjava.model.ServiceInstance;

import java.net.InetSocketAddress;
import java.net.Socket;

public class TcpLoopbackHealthProbe implements HealthProbe {
    private final int connectTimeoutMillis;

    public TcpLoopbackHealthProbe(int connectTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    @Override
    public boolean isHealthy(ServiceInstance instance) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(instance.host(), instance.port()), connectTimeoutMillis);
            return socket.isConnected();
        } catch (Exception ex) {
            return false;
        }
    }
}
