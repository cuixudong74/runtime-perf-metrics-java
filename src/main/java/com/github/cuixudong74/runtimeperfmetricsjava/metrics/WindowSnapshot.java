package com.github.cuixudong74.runtimeperfmetricsjava.metrics;

import java.util.Collections;
import java.util.Map;

/** 某一时刻的不可变指标快照，读取结果自洽，不随后续写入变化。 */
public final class WindowSnapshot {
    private final long capturedAtMillis;
    private final long windowStartMillis;
    private final long windowEndMillis;
    private final Map<String, DimensionStats> dimensions;

    public WindowSnapshot(long capturedAtMillis, long windowStartMillis, long windowEndMillis,
                          Map<String, DimensionStats> dimensions) {
        this.capturedAtMillis = capturedAtMillis;
        this.windowStartMillis = windowStartMillis;
        this.windowEndMillis = windowEndMillis;
        this.dimensions = Collections.unmodifiableMap(dimensions);
    }

    public long getCapturedAtMillis() { return capturedAtMillis; }
    public long getWindowStartMillis() { return windowStartMillis; }
    public long getWindowEndMillis() { return windowEndMillis; }
    public Map<String, DimensionStats> getDimensions() { return dimensions; }

    /** 单个维度的聚合统计。 */
    public static final class DimensionStats {
        private final long totalCount;
        private final long successCount;
        private final long failureCount;
        private final long totalLatencyNanos;
        private final long minLatencyNanos;
        private final long maxLatencyNanos;
        private final double p50Nanos;
        private final double p90Nanos;
        private final double p99Nanos;

        public DimensionStats(long totalCount, long successCount, long failureCount,
                              long totalLatencyNanos, long minLatencyNanos, long maxLatencyNanos,
                              double p50Nanos, double p90Nanos, double p99Nanos) {
            this.totalCount = totalCount;
            this.successCount = successCount;
            this.failureCount = failureCount;
            this.totalLatencyNanos = totalLatencyNanos;
            this.minLatencyNanos = minLatencyNanos;
            this.maxLatencyNanos = maxLatencyNanos;
            this.p50Nanos = p50Nanos;
            this.p90Nanos = p90Nanos;
            this.p99Nanos = p99Nanos;
        }

        public long getTotalCount() { return totalCount; }
        public long getSuccessCount() { return successCount; }
        public long getFailureCount() { return failureCount; }
        public long getTotalLatencyNanos() { return totalLatencyNanos; }
        public long getMinLatencyNanos() { return minLatencyNanos; }
        public long getMaxLatencyNanos() { return maxLatencyNanos; }
        public double getP50Nanos() { return p50Nanos; }
        public double getP90Nanos() { return p90Nanos; }
        public double getP99Nanos() { return p99Nanos; }

        public double getMeanLatencyNanos() {
            return totalCount == 0 ? 0.0 : (double) totalLatencyNanos / totalCount;
        }
    }
}
