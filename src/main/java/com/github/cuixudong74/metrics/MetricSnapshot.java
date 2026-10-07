package com.github.cuixudong74.metrics;

import java.time.Instant;
import java.util.Map;

/** Immutable, point-in-time aggregate snapshot. */
public record MetricSnapshot(
        Instant windowStart,
        Instant windowEnd,
        Instant capturedAt,
        long sampleCount,
        double estimatedCount,
        double totalDurationNanos,
        long minDurationNanos,
        long maxDurationNanos,
        Map<RequestStatus, Long> sampleStatusCounts,
        Map<RequestStatus, Double> estimatedStatusCounts,
        Map<Double, Double> quantilesNanos,
        Map<Map<String, String>, GroupSnapshot> groups,
        Map<Admission, Long> dropped
) {
}
