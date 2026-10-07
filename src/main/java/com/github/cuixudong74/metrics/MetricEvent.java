package com.github.cuixudong74.metrics;

import java.util.Map;

/** Internal queued measurement. */
record MetricEvent(
        long eventTimeEpochMillis,
        long durationNanos,
        RequestStatus status,
        Map<String, String> dimensions,
        double weight
) {
}
