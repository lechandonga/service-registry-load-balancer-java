package com.github.lechandonga.serviceregistryloadbalancerjava.config;

import com.github.lechandonga.serviceregistryloadbalancerjava.health.HealthProbeService;
import com.github.lechandonga.serviceregistryloadbalancerjava.health.TcpLoopbackProbeChecker;
import com.github.lechandonga.serviceregistryloadbalancerjava.lb.Router;
import com.github.lechandonga.serviceregistryloadbalancerjava.registry.LeaseReaper;
import com.github.lechandonga.serviceregistryloadbalancerjava.registry.ServiceRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配纯内存注册中心、路由器、租约清理与回环端口健康探测。全部状态仅在本机 JVM 内。
 */
@Configuration
@EnableConfigurationProperties(SlbProperties.class)
public class SlbConfiguration {

    private LeaseReaper leaseReaper;
    private HealthProbeService healthProbeService;

    @Bean
    public ServiceRegistry serviceRegistry(SlbProperties properties) {
        return new ServiceRegistry(System::currentTimeMillis, properties.getLeaseTtlMillis());
    }

    @Bean
    public Router router(ServiceRegistry registry) {
        return new Router(registry);
    }

    @Bean
    public LeaseReaper leaseReaper(ServiceRegistry registry, SlbProperties properties) {
        leaseReaper = new LeaseReaper(registry, properties.getLeaseSweepMillis());
        leaseReaper.start();
        return leaseReaper;
    }

    @Bean
    public HealthProbeService healthProbeService(ServiceRegistry registry, SlbProperties properties) {
        TcpLoopbackProbeChecker checker =
                new TcpLoopbackProbeChecker(properties.getProbeConnectTimeoutMillis());
        healthProbeService = new HealthProbeService(registry, checker,
                properties.getFailureThreshold(), properties.getSuccessThreshold(),
                properties.getProbeIntervalMillis());
        healthProbeService.start();
        return healthProbeService;
    }

    @PreDestroy
    public void shutdownBackgroundTasks() {
        if (leaseReaper != null) {
            leaseReaper.shutdown();
        }
        if (healthProbeService != null) {
            healthProbeService.shutdown();
        }
    }
}
