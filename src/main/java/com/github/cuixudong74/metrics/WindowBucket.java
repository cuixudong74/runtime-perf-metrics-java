package com.github.cuixudong74.metrics;

import java.util.LinkedHashMap;
import java.util.Map;

/** One fixed-length interval inside a sliding window. */
final class WindowBucket {

    private Aggregate all = new Aggregate();
    private final Map<Map<String, String>, Aggregate> groups = new LinkedHashMap<>();
    private MetricsConfig config;
    private long startEpochMillis;

    WindowBucket(MetricsConfig config, long startEpochMillis) {
        this.config = config;
        this.startEpochMillis = startEpochMillis;
    }

    long startEpochMillis() {
        return startEpochMillis;
    }

    void updateConfig(MetricsConfig next) {
        this.config = next;
    }

    /** Prepare this bucket for reuse at a new interval. */
    void reset(long newStartEpochMillis) {
        startEpochMillis = newStartEpochMillis;
        // The old aggregate graph is released here; buckets themselves are reused.
        all = new Aggregate();
        groups.clear();
    }

    Admission add(MetricEvent event) {
        Map<String, String> dimensions = event.dimensions();
        if (dimensions.isEmpty()) {
            all.add(event);
            return Admission.ACCEPTED;
        }

        Aggregate group = groups.get(dimensions);
        if (group == null && groups.size() >= config.maxGroupsPerBucket()) {
            if (config.cardinalityPolicy() == CardinalityPolicy.REJECT) {
                return Admission.DIMENSION_CARDINALITY_EXCEEDED;
            }
            return addWithDegradation(event);
        }

        if (group == null) {
            group = new Aggregate();
            groups.put(dimensions, group);
        }

        group.add(event);
        all.add(event);
        return Admission.ACCEPTED;
    }

    private Admission addWithDegradation(MetricEvent event) {
        Map<String, String> degradedKey = Map.of(
                config.degradedDimensionName(), config.degradedDimensionValue());
        Aggregate degraded = groups.get(degradedKey);
        if (degraded == null) {
            // Keep the total number of groups <= maxGroupsPerBucket. The last normal group
            // becomes part of the degraded aggregate rather than growing the map by one.
            var lastEntry = groups.entrySet().iterator().next();
            degraded = new Aggregate();
            lastEntry.getValue().addInto(degraded);
            groups.remove(lastEntry.getKey());
            groups.put(degradedKey, degraded);
        }
        degraded.add(event);
        all.add(event);
        return Admission.ACCEPTED;
    }

    Aggregate allAggregate() {
        return all;
    }

    Map<Map<String, String>, Aggregate> groups() {
        return groups;
    }
}
