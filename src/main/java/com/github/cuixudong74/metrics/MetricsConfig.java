package com.github.cuixudong74.metrics;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable collector configuration.
 *
 * <p>A replacement configuration is applied as an asynchronous barrier. It affects only events
 * admitted and aggregation performed after application; previously returned snapshots are immutable.
 *
 * @param enabled                   whether new measurements are sampled
 * @param sampleRate                probability in the inclusive range [0, 1] of recording an event
 * @param windowLength              sliding-window length; must be a positive multiple of bucketLength
 * @param bucketLength              aggregation bucket length
 * @param maxGroupsPerBucket        maximum distinct dimension groups retained by each bucket
 * @param maxDimensionsPerRecord    maximum number of dimensions accepted on one record call
 * @param cardinalityPolicy         policy used after the group limit is reached
 * @param queueCapacity             bounded capacity between business threads and the aggregation thread
 * @param degradedDimensionName     name used by the optional degraded group marker
 * @param degradedDimensionValue    value used by the optional degraded group marker
 */
public record MetricsConfig(
        boolean enabled,
        double sampleRate,
        Duration windowLength,
        Duration bucketLength,
        int maxGroupsPerBucket,
        int maxDimensionsPerRecord,
        CardinalityPolicy cardinalityPolicy,
        int queueCapacity,
        String degradedDimensionName,
        String degradedDimensionValue
) {
    public MetricsConfig {
        if (!Double.isFinite(sampleRate) || sampleRate < 0.0d || sampleRate > 1.0d) {
            throw new IllegalArgumentException("sampleRate must be in [0, 1]");
        }
        Objects.requireNonNull(windowLength, "windowLength");
        Objects.requireNonNull(bucketLength, "bucketLength");
        Objects.requireNonNull(cardinalityPolicy, "cardinalityPolicy");
        Objects.requireNonNull(degradedDimensionName, "degradedDimensionName");
        Objects.requireNonNull(degradedDimensionValue, "degradedDimensionValue");
        if (windowLength.isZero() || windowLength.isNegative()) {
            throw new IllegalArgumentException("windowLength must be positive");
        }
        if (bucketLength.isZero() || bucketLength.isNegative()) {
            throw new IllegalArgumentException("bucketLength must be positive");
        }
        if (bucketLength.compareTo(windowLength) > 0) {
            throw new IllegalArgumentException("bucketLength must not exceed windowLength");
        }
        long windowMillis = windowLength.toMillis();
        long bucketMillis = bucketLength.toMillis();
        if (windowMillis % bucketMillis != 0L) {
            throw new IllegalArgumentException("windowLength must be an exact multiple of bucketLength");
        }
        if (maxGroupsPerBucket < 1) {
            throw new IllegalArgumentException("maxGroupsPerBucket must be at least 1");
        }
        if (maxDimensionsPerRecord < 0) {
            throw new IllegalArgumentException("maxDimensionsPerRecord must be at least 0");
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be at least 1");
        }
        if (degradedDimensionName.isBlank()) {
            throw new IllegalArgumentException("degradedDimensionName must not be blank");
        }
        if (degradedDimensionValue.isBlank()) {
            throw new IllegalArgumentException("degradedDimensionValue must not be blank");
        }
    }

    public static MetricsConfig defaults() {
        return new MetricsConfig(
                true,
                1.0d,
                Duration.ofMinutes(1),
                Duration.ofSeconds(1),
                1_000,
                16,
                CardinalityPolicy.DEGRADE,
                131_072,
                "__degraded",
                "true"
        );
    }

    public int bucketCount() {
        return Math.toIntExact(windowLength.toMillis() / bucketLength.toMillis());
    }
}
