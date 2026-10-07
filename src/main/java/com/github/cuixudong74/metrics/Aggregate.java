package com.github.cuixudong74.metrics;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Mutable aggregate for the all-traffic group or one dimension group. */
final class Aggregate {

    private static final double[] QUANTILES = {0.50d, 0.90d, 0.95d, 0.99d};

    private long sampleCount;
    private double weightedCount;
    private double weightedDurationNanos;
    private long minDurationNanos = Long.MAX_VALUE;
    private long maxDurationNanos = Long.MIN_VALUE;
    private final LogarithmicHistogram histogram = new LogarithmicHistogram();
    private final EnumMap<RequestStatus, Long> sampleStatusCounts = new EnumMap<>(RequestStatus.class);
    private final EnumMap<RequestStatus, Double> weightedStatusCounts = new EnumMap<>(RequestStatus.class);

    Aggregate() {
    }

    void add(MetricEvent event) {
        double weight = event.weight();
        sampleCount++;
        weightedCount += weight;
        weightedDurationNanos += event.durationNanos() * weight;
        minDurationNanos = Math.min(minDurationNanos, event.durationNanos());
        maxDurationNanos = Math.max(maxDurationNanos, event.durationNanos());
        histogram.add(event.durationNanos(), weight);
        sampleStatusCounts.merge(event.status(), 1L, Long::sum);
        weightedStatusCounts.merge(event.status(), weight, Double::sum);
    }

    /** Merge this aggregate into the target. Both aggregates must be used under the same lock. */
    void addInto(Aggregate target) {
        target.sampleCount += sampleCount;
        target.weightedCount += weightedCount;
        target.weightedDurationNanos += weightedDurationNanos;
        if (sampleCount > 0L) {
            target.minDurationNanos = Math.min(target.minDurationNanos, minDurationNanos);
            target.maxDurationNanos = Math.max(target.maxDurationNanos, maxDurationNanos);
        }
        histogram.addInto(target.histogram);
        sampleStatusCounts.forEach((status, count) ->
                target.sampleStatusCounts.merge(status, count, Long::sum));
        weightedStatusCounts.forEach((status, count) ->
                target.weightedStatusCounts.merge(status, count, Double::sum));
    }

    GroupSnapshot toSnapshot() {
        Map<RequestStatus, Long> samples = Collections.unmodifiableMap(
                new LinkedHashMap<>(sampleStatusCounts));
        Map<RequestStatus, Double> estimated = Collections.unmodifiableMap(
                new LinkedHashMap<>(weightedStatusCounts));
        return new GroupSnapshot(
                sampleCount,
                weightedCount,
                weightedDurationNanos,
                sampleCount == 0L ? 0L : minDurationNanos,
                sampleCount == 0L ? 0L : maxDurationNanos,
                samples,
                estimated,
                Collections.unmodifiableMap(new LinkedHashMap<>(histogram.quantiles(QUANTILES)))
        );
    }
}
