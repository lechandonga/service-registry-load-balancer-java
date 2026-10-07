package com.github.lechandonga.serviceregistryloadbalancerjava.health;

import com.github.lechandonga.serviceregistryloadbalancerjava.registry.InstanceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 通过本地回环端口做 TCP 连通性探测：在实例端口上建立短连接，能连上即健康。
 * 用 {@code ServerSocket} 在回环端口起一个监听，就可以完整模拟实例上下线。
 */
public class TcpLoopbackProbeChecker implements ProbeChecker {

    private static final Logger log = LoggerFactory.getLogger(TcpLoopbackProbeChecker.class);

    private final int connectTimeoutMillis;

    public TcpLoopbackProbeChecker(int connectTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    @Override
    public boolean isHealthy(InstanceRecord record) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(record.getHost(), record.getPort()),
                    connectTimeoutMillis);
            return true;
        } catch (Exception e) {
            log.debug("tcp probe failed instance={} address={}:{} reason={}",
                    record.getInstanceId(), record.getHost(), record.getPort(), e.toString());
            return false;
        }
    }
}
