package com.github.cuixudong74.metrics;

import java.util.Map;

/** Entry point for recording request runtime performance metrics. */
public interface MetricCollector extends AutoCloseable {

    Admission recordLatency(long durationNanos, RequestStatus status, Map<String, String> dimensions);

    default Admission recordLatency(long durationNanos, RequestStatus status) {
        return recordLatency(durationNanos, status, Map.of());
    }

    MetricSnapshot snapshot();

    Admission updateConfig(MetricsConfig config);

    MetricsConfig currentConfig();

    @Override
    void close();
}
