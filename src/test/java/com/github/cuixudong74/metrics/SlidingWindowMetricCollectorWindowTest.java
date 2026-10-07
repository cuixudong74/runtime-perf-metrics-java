package com.github.cuixudong74.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SlidingWindowMetricCollectorWindowTest {

    @Test
    void rotatesBucketsAndEvictsExpiredValuesWithoutUnboundedRetention() {
        SlidingWindowMetricCollectorTest.ManualClock clock =
                new SlidingWindowMetricCollectorTest.ManualClock(
                        Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        MetricsConfig config = new MetricsConfig(
                true, 1.0d, Duration.ofSeconds(4), Duration.ofSeconds(1),
                100, 8, CardinalityPolicy.DEGRADE, 1_024, "__degraded", "true");

        try (SlidingWindowMetricCollector collector =
                     new SlidingWindowMetricCollector(config, clock)) {
            assertThat(collector.recordLatency(10L, RequestStatus.SUCCESS))
                    .isEqualTo(Admission.ACCEPTED);
            collector.awaitProcessed(Duration.ofSeconds(2));
            MetricSnapshot atZero = collector.snapshot();
            assertThat(atZero.sampleCount()).isOne();

            clock.advance(Duration.ofSeconds(2));
            assertThat(collector.recordLatency(20L, RequestStatus.SUCCESS))
                    .isEqualTo(Admission.ACCEPTED);
            collector.awaitProcessed(Duration.ofSeconds(2));
            assertThat(collector.snapshot().sampleCount()).isEqualTo(2L);

            // Advance beyond the four-second window. The first sample is in bucket [0s,1s);
            // at 5s the retained buckets start at 2s, so it must have been evicted.
            clock.advance(Duration.ofSeconds(3));
            collector.awaitProcessed(Duration.ofSeconds(2));
            MetricSnapshot afterEviction = collector.snapshot();
            assertThat(afterEviction.sampleCount()).isOne();
            assertThat(afterEviction.minDurationNanos()).isEqualTo(20L);
            assertThat(afterEviction.windowStart()).isEqualTo(Instant.parse("2026-01-01T00:00:02Z"));
            assertThat(afterEviction.windowEnd()).isEqualTo(Instant.parse("2026-01-01T00:00:06Z"));

            // Previously returned data remains unchanged despite later rotation.
            assertThat(atZero.sampleCount()).isOne();
            assertThat(atZero.minDurationNanos()).isEqualTo(10L);
        }
    }

    @Test
    void snapshotsRemainConsistentWhileReadersCrossRotationBoundaries() throws Exception {
        SlidingWindowMetricCollectorTest.ManualClock clock =
                new SlidingWindowMetricCollectorTest.ManualClock(
                        Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        MetricsConfig config = new MetricsConfig(
                true, 1.0d, Duration.ofSeconds(4), Duration.ofMillis(100),
                16, 4, CardinalityPolicy.DEGRADE, 4_096, "__degraded", "true");

        try (SlidingWindowMetricCollector collector =
                     new SlidingWindowMetricCollector(config, clock)) {
            ExecutorService pool = Executors.newFixedThreadPool(3);
            CountDownLatch stop = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            for (int reader = 0; reader < 2; reader++) {
                pool.submit(() -> {
                    while (stop.getCount() > 0) {
                        try {
                            MetricSnapshot snapshot = collector.snapshot();
                            assertThat(snapshot.windowEnd())
                                    .isEqualTo(snapshot.windowStart().plusSeconds(4));
                            assertThat(snapshot.quantilesNanos()).hasSize(4);
                            assertThat(snapshot.sampleStatusCounts()).isNotNull();
                            assertThat(snapshot.groups()).isNotNull();
                            assertThat(snapshot.dropped()).isNotNull();
                        } catch (RuntimeException exception) {
                            failure.compareAndSet(null, exception);
                            stop.countDown();
                        }
                    }
                });
            }

            pool.submit(() -> {
                for (int i = 0; i < 100 && stop.getCount() > 0; i++) {
                    clock.advance(Duration.ofMillis(25));
                    collector.recordLatency(i + 1L, RequestStatus.SUCCESS,
                            java.util.Map.of("bucket", String.valueOf(i % 8)));
                }
                stop.countDown();
            });

            assertThat(stop.await(5, TimeUnit.SECONDS)).isTrue();
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            assertThat(failure.get()).isNull();
            collector.awaitProcessed(Duration.ofSeconds(2));
        }
    }
}
