package com.github.cuixudong74.metrics;

import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(MetricsProperties.class)
public class MetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock metricsClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    MetricCollector metricCollector(MetricsProperties properties, Clock metricsClock) {
        return new SlidingWindowMetricCollector(properties.toConfig(), metricsClock);
    }

    @Bean
    @ConditionalOnMissingBean
    MetricsController metricsController(MetricCollector metricCollector) {
        return new MetricsController(metricCollector);
    }
}
