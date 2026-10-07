package com.github.lechandonga.serviceregistryloadbalancerjava.config;

import com.github.lechandonga.serviceregistryloadbalancerjava.core.ConsistentHashStrategy;
import com.github.lechandonga.serviceregistryloadbalancerjava.core.HealthProbe;
import com.github.lechandonga.serviceregistryloadbalancerjava.core.LoadBalancer;
import com.github.lechandonga.serviceregistryloadbalancerjava.core.SelectionStrategy;
import com.github.lechandonga.serviceregistryloadbalancerjava.core.ServiceRegistry;
import com.github.lechandonga.serviceregistryloadbalancerjava.core.TcpLoopbackHealthProbe;
import com.github.lechandonga.serviceregistryloadbalancerjava.core.RoundRobinStrategy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@EnableConfigurationProperties(RegistryProperties.class)
public class RegistryConfiguration {
    @Bean
    public ServiceRegistry serviceRegistry() {
        return new ServiceRegistry();
    }

    @Bean
    public HealthProbe healthProbe(RegistryProperties properties) {
        return new TcpLoopbackHealthProbe(properties.getProbeConnectTimeoutMillis());
    }

    @Bean
    public LoadBalancer loadBalancer(ServiceRegistry registry) {
        List<SelectionStrategy> strategies = List.of(
                new RoundRobinStrategy(),
                new ConsistentHashStrategy()
        );
        return new LoadBalancer(registry, strategies);
    }
}
