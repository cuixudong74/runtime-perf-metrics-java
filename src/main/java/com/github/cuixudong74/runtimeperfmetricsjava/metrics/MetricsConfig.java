package com.github.cuixudong74.runtimeperfmetricsjava.metrics;

/** 不可变采集配置。配置变更通过整体替换生效，只影响之后的采集与聚合。 */
public final class MetricsConfig {
    private final long windowLengthMillis;
    private final int bucketCount;
    private final double sampleRate;
    private final int maxDimensions;
    private final OverflowPolicy overflowPolicy;
    private final int maxSamplesPerBucket;

    public MetricsConfig(long windowLengthMillis, int bucketCount, double sampleRate,
                         int maxDimensions, OverflowPolicy overflowPolicy, int maxSamplesPerBucket) {
        this.windowLengthMillis = windowLengthMillis;
        this.bucketCount = bucketCount;
        this.sampleRate = sampleRate;
        this.maxDimensions = maxDimensions;
        this.overflowPolicy = overflowPolicy;
        this.maxSamplesPerBucket = maxSamplesPerBucket;
    }

    public static MetricsConfig defaults() {
        return new MetricsConfig(60_000, 60, 1.0, 1024, OverflowPolicy.DEGRADE_TO_OTHER, 4096);
    }

    public long getWindowLengthMillis() { return windowLengthMillis; }
    public int getBucketCount() { return bucketCount; }
    public double getSampleRate() { return sampleRate; }
    public int getMaxDimensions() { return maxDimensions; }
    public OverflowPolicy getOverflowPolicy() { return overflowPolicy; }
    public int getMaxSamplesPerBucket() { return maxSamplesPerBucket; }

    public long bucketLengthMillis() {
        return Math.max(1, windowLengthMillis / Math.max(1, bucketCount));
    }
}
