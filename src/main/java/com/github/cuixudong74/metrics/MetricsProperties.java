package com.github.cuixudong74.metrics;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "perf.metrics")
public class MetricsProperties {
    private boolean enabled = true;
    private double sampleRate = 1.0d;
    private Duration windowLength = Duration.ofMinutes(1);
    private Duration bucketLength = Duration.ofSeconds(1);
    private int maxGroupsPerBucket = 1_000;
    private int maxDimensionsPerRecord = 16;
    private CardinalityPolicy cardinalityPolicy = CardinalityPolicy.DEGRADE;
    private int queueCapacity = 131_072;
    private String degradedDimensionName = "__degraded";
    private String degradedDimensionValue = "true";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public double getSampleRate() { return sampleRate; }
    public void setSampleRate(double sampleRate) { this.sampleRate = sampleRate; }
    public Duration getWindowLength() { return windowLength; }
    public void setWindowLength(Duration windowLength) { this.windowLength = windowLength; }
    public Duration getBucketLength() { return bucketLength; }
    public void setBucketLength(Duration bucketLength) { this.bucketLength = bucketLength; }
    public int getMaxGroupsPerBucket() { return maxGroupsPerBucket; }
    public void setMaxGroupsPerBucket(int maxGroupsPerBucket) { this.maxGroupsPerBucket = maxGroupsPerBucket; }
    public int getMaxDimensionsPerRecord() { return maxDimensionsPerRecord; }
    public void setMaxDimensionsPerRecord(int maxDimensionsPerRecord) { this.maxDimensionsPerRecord = maxDimensionsPerRecord; }
    public CardinalityPolicy getCardinalityPolicy() { return cardinalityPolicy; }
    public void setCardinalityPolicy(CardinalityPolicy cardinalityPolicy) { this.cardinalityPolicy = cardinalityPolicy; }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
    public String getDegradedDimensionName() { return degradedDimensionName; }
    public void setDegradedDimensionName(String degradedDimensionName) { this.degradedDimensionName = degradedDimensionName; }
    public String getDegradedDimensionValue() { return degradedDimensionValue; }
    public void setDegradedDimensionValue(String degradedDimensionValue) { this.degradedDimensionValue = degradedDimensionValue; }

    MetricsConfig toConfig() {
        return new MetricsConfig(
                enabled,
                sampleRate,
                windowLength,
                bucketLength,
                maxGroupsPerBucket,
                maxDimensionsPerRecord,
                cardinalityPolicy,
                queueCapacity,
                degradedDimensionName,
                degradedDimensionValue
        );
    }
}
