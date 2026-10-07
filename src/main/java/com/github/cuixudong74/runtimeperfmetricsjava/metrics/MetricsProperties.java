package com.github.cuixudong74.runtimeperfmetricsjava.metrics;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 外部化配置项，前缀 metrics。 */
@ConfigurationProperties(prefix = "metrics")
public class MetricsProperties {
    private long windowLengthMillis = 60_000;
    private int bucketCount = 60;
    private double sampleRate = 1.0;
    private int maxDimensions = 1024;
    private String overflowPolicy = "DEGRADE_TO_OTHER";
    private int maxSamplesPerBucket = 4096;

    public long getWindowLengthMillis() { return windowLengthMillis; }
    public void setWindowLengthMillis(long v) { this.windowLengthMillis = v; }
    public int getBucketCount() { return bucketCount; }
    public void setBucketCount(int v) { this.bucketCount = v; }
    public double getSampleRate() { return sampleRate; }
    public void setSampleRate(double v) { this.sampleRate = v; }
    public int getMaxDimensions() { return maxDimensions; }
    public void setMaxDimensions(int v) { this.maxDimensions = v; }
    public String getOverflowPolicy() { return overflowPolicy; }
    public void setOverflowPolicy(String v) { this.overflowPolicy = v; }
    public int getMaxSamplesPerBucket() { return maxSamplesPerBucket; }
    public void setMaxSamplesPerBucket(int v) { this.maxSamplesPerBucket = v; }

    public MetricsConfig toConfig() {
        return new MetricsConfig(windowLengthMillis, bucketCount, sampleRate,
                maxDimensions, OverflowPolicy.valueOf(overflowPolicy), maxSamplesPerBucket);
    }
}
