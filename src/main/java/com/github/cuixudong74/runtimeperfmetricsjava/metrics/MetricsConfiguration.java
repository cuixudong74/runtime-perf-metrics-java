package com.github.cuixudong74.runtimeperfmetricsjava.metrics;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MetricsProperties.class)
public class MetricsConfiguration {
    @Bean
    public MetricsCollector metricsCollector(MetricsProperties properties) {
        return new MetricsCollector(properties.toConfig());
    }
}
