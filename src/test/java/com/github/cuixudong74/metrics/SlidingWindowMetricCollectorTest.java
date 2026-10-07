package com.github.cuixudong74.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class SlidingWindowMetricCollectorTest {

    @Test
    void countsEveryAcceptedConcurrentWriteExactlyOnce() throws Exception {
        try (SlidingWindowMetricCollector collector = new SlidingWindowMetricCollector(
                MetricsConfig.defaults(), Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"),
                        ZoneOffset.UTC))) {
            int threads = 16;
            int writesPerThread = 1_000;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();

            for (int thread = 0; thread < threads; thread++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    for (int i = 0; i < writesPerThread; i++) {
                        assertThat(collector.recordLatency(100L + i, RequestStatus.SUCCESS,
                                Map.of("thread", Thread.currentThread().getName()))).isEqualTo(
                                Admission.ACCEPTED);
                    }
                    return null;
                }));
            }

            ready.await();
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
            pool.shutdown();
            collector.awaitProcessed(Duration.ofSeconds(5));

            MetricSnapshot snapshot = collector.snapshot();
            assertThat(snapshot.sampleCount()).isEqualTo((long) threads * writesPerThread);
            assertThat(snapshot.estimatedCount()).isEqualTo((double) threads * writesPerThread);
            assertThat(snapshot.sampleStatusCounts()).containsEntry(RequestStatus.SUCCESS,
                    (long) threads * writesPerThread);
            assertThat(snapshot.minDurationNanos()).isEqualTo(100L);
            assertThat(snapshot.maxDurationNanos()).isEqualTo(100L + writesPerThread - 1L);
            assertThat(snapshot.groups()).hasSize(threads);
        }
    }

    @Test
    void isolatesInvalidRecordsAndKeepsCollecting() {
        try (SlidingWindowMetricCollector collector = new SlidingWindowMetricCollector(
                MetricsConfig.defaults(),
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))) {
            assertThat(collector.recordLatency(-1L, RequestStatus.SUCCESS))
                    .isEqualTo(Admission.INVALID_ARGUMENT);
            assertThat(collector.recordLatency(10L, RequestStatus.SUCCESS, null))
                    .isEqualTo(Admission.INVALID_ARGUMENT);

            assertThat(collector.recordLatency(20L, RequestStatus.ERROR))
                    .isEqualTo(Admission.ACCEPTED);
            collector.awaitProcessed(Duration.ofSeconds(2));

            MetricSnapshot snapshot = collector.snapshot();
            assertThat(snapshot.sampleCount()).isOne();
            assertThat(snapshot.sampleStatusCounts()).containsEntry(RequestStatus.ERROR, 1L);
        }
    }

    static final class ManualClock extends Clock {
        private volatile Instant instant;
        private final ZoneId zone;

        ManualClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new ManualClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
