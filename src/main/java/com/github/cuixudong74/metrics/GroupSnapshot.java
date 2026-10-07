package com.github.cuixudong74.metrics;

import java.util.Map;

/** Immutable aggregate for one dimension group. */
public record GroupSnapshot(
        long sampleCount,
        double estimatedCount,
        double totalDurationNanos,
        long minDurationNanos,
        long maxDurationNanos,
        Map<RequestStatus, Long> sampleStatusCounts,
        Map<RequestStatus, Double> estimatedStatusCounts,
        Map<Double, Double> quantilesNanos
) {
}
