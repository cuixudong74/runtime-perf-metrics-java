package com.github.cuixudong74.metrics;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Fixed-bucket sliding window.
 *
 * <p>The single collector worker calls every mutating and snapshot method while holding this
 * window's monitor. Buckets are advanced to the current time before either writing or reading,
 * therefore a snapshot cannot include a partially rotated bucket.
 */
final class SlidingWindow {

    private MetricsConfig config;
    private final long bucketLengthMillis;
    private final int bucketCount;
    private final WindowBucket[] buckets;
    private long newestStartEpochMillis;

    SlidingWindow(MetricsConfig config, long nowEpochMillis) {
        this.config = config;
        this.bucketLengthMillis = config.bucketLength().toMillis();
        this.bucketCount = config.bucketCount();
        this.buckets = new WindowBucket[bucketCount];
        this.newestStartEpochMillis = floorToBucket(nowEpochMillis);
        for (int i = 0; i < bucketCount; i++) {
            long start = newestStartEpochMillis - (long) (bucketCount - 1 - i) * bucketLengthMillis;
            buckets[i] = new WindowBucket(this.config, start);
        }
    }

    synchronized boolean updatePolicyIfStructureUnchanged(MetricsConfig next) {
        if (next.bucketLength().toMillis() != bucketLengthMillis || next.bucketCount() != bucketCount) {
            return false;
        }
        config = next;
        for (WindowBucket bucket : buckets) {
            bucket.updateConfig(next);
        }
        return true;
    }

    synchronized Admission add(MetricEvent event, long nowEpochMillis) {
        advance(nowEpochMillis);
        long eventStart = floorToBucket(event.eventTimeEpochMillis());
        long oldestStart = oldestStartEpochMillis();
        if (eventStart < oldestStart) {
            return Admission.EXPIRED;
        }
        if (eventStart > newestStartEpochMillis) {
            return Admission.EXPIRED;
        }
        int slot = slotForStart(eventStart);
        return buckets[slot].add(event);
    }

    synchronized MetricSnapshot snapshot(Instant capturedAt,
                                          Supplier<Map<Admission, Long>> droppedSupplier) {
        advance(capturedAt.toEpochMilli());
        Map<Admission, Long> dropped = droppedSupplier.get();
        long oldestStart = oldestStartEpochMillis();
        Aggregate total = new Aggregate();
        Map<Map<String, String>, Aggregate> mergedGroups = new LinkedHashMap<>();

        for (WindowBucket bucket : buckets) {
            bucket.allAggregate().addInto(total);
            for (Map.Entry<Map<String, String>, Aggregate> entry : bucket.groups().entrySet()) {
                Aggregate merged = mergedGroups.computeIfAbsent(
                        entry.getKey(), ignored -> new Aggregate());
                entry.getValue().addInto(merged);
            }
        }

        Map<Map<String, String>, GroupSnapshot> groupSnapshots = new LinkedHashMap<>();
        mergedGroups.forEach((key, aggregate) -> groupSnapshots.put(
                Collections.unmodifiableMap(new LinkedHashMap<>(key)),
                aggregate.toSnapshot()));

        GroupSnapshot totalSnapshot = total.toSnapshot();
        return new MetricSnapshot(
                Instant.ofEpochMilli(oldestStart),
                Instant.ofEpochMilli(oldestStart + (long) bucketCount * bucketLengthMillis),
                capturedAt,
                totalSnapshot.sampleCount(),
                totalSnapshot.estimatedCount(),
                totalSnapshot.totalDurationNanos(),
                totalSnapshot.minDurationNanos(),
                totalSnapshot.maxDurationNanos(),
                totalSnapshot.sampleStatusCounts(),
                totalSnapshot.estimatedStatusCounts(),
                totalSnapshot.quantilesNanos(),
                Collections.unmodifiableMap(groupSnapshots),
                Collections.unmodifiableMap(new LinkedHashMap<>(dropped))
        );
    }

    private void advance(long nowEpochMillis) {
        long latestStart = floorToBucket(nowEpochMillis);
        while (newestStartEpochMillis < latestStart) {
            newestStartEpochMillis += bucketLengthMillis;
            int slot = slotForStart(newestStartEpochMillis);
            buckets[slot].reset(newestStartEpochMillis);
        }
    }

    private long oldestStartEpochMillis() {
        return newestStartEpochMillis - (long) (bucketCount - 1) * bucketLengthMillis;
    }

    private int slotForStart(long startEpochMillis) {
        int slot = (int) Math.floorMod(startEpochMillis / bucketLengthMillis, (long) bucketCount);
        return slot;
    }

    private long floorToBucket(long epochMillis) {
        return Math.floorDiv(epochMillis, bucketLengthMillis) * bucketLengthMillis;
    }
}
