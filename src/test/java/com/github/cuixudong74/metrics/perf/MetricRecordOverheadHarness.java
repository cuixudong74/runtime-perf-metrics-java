package com.github.cuixudong74.metrics.perf;

import com.github.cuixudong74.metrics.MetricsConfig;
import com.github.cuixudong74.metrics.RequestStatus;
import com.github.cuixudong74.metrics.SlidingWindowMetricCollector;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * Manual, dependency-free overhead measurement harness.
 *
 * <p>It is intentionally not annotated with {@code @Test}: wall-clock assertions would be
 * environment-dependent. Run it from an IDE or {@code mvn exec:java} configured for test
 * classpath when a local measurement is needed.
 */
final class MetricRecordOverheadHarness {

    private MetricRecordOverheadHarness() {
    }

    public static void main(String[] args) throws Exception {
        int warmup = 200_000;
        int iterations = 1_000_000;
        try (SlidingWindowMetricCollector collector = new SlidingWindowMetricCollector(
                MetricsConfig.defaults(),
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))) {
            run(collector, warmup);
            long start = System.nanoTime();
            run(collector, iterations);
            long elapsed = System.nanoTime() - start;
            double nanosPerRecord = (double) elapsed / iterations;
            System.out.printf("recordLatency average business-thread overhead: %.1f ns/op over %d ops%n",
                    nanosPerRecord, iterations);
        }
    }

    private static void run(SlidingWindowMetricCollector collector, int iterations) {
        Map<String, String> dimensions = Map.of("route", "/orders", "method", "GET");
        for (int i = 0; i < iterations; i++) {
            collector.recordLatency(1_000L + (i % 1_000), RequestStatus.SUCCESS, dimensions);
        }
    }
}
