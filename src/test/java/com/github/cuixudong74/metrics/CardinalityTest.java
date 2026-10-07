package com.github.cuixudong74.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class CardinalityTest {

    @Test
    void degradesExtraDimensionGroupsIntoBoundedGroup() {
        MetricsConfig config = new MetricsConfig(
                true, 1.0d, Duration.ofSeconds(2), Duration.ofSeconds(1),
                2, 8, CardinalityPolicy.DEGRADE, 1_024, "__degraded", "true");
        try (SlidingWindowMetricCollector collector = new SlidingWindowMetricCollector(
                config, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))) {
            assertThat(collector.recordLatency(1L, RequestStatus.SUCCESS, Map.of("a", "1")))
                    .isEqualTo(Admission.ACCEPTED);
            assertThat(collector.recordLatency(2L, RequestStatus.SUCCESS, Map.of("a", "2")))
                    .isEqualTo(Admission.ACCEPTED);
            assertThat(collector.recordLatency(3L, RequestStatus.SUCCESS, Map.of("a", "3")))
                    .isEqualTo(Admission.ACCEPTED);
            collector.awaitProcessed(Duration.ofSeconds(2));

            MetricSnapshot snapshot = collector.snapshot();
            assertThat(snapshot.groups()).hasSize(2);
            assertThat(snapshot.groups().get(Map.of("__degraded", "true")).sampleCount())
                    .isEqualTo(2L);
            assertThat(snapshot.dropped()).doesNotContainKey(Admission.DIMENSION_CARDINALITY_EXCEEDED);
        }
    }

    @Test
    void rejectsExtraDimensionGroupsWithDistinguishableReason() {
        MetricsConfig config = new MetricsConfig(
                true, 1.0d, Duration.ofSeconds(2), Duration.ofSeconds(1),
                1, 8, CardinalityPolicy.REJECT, 1_024, "__degraded", "true");
        try (SlidingWindowMetricCollector collector = new SlidingWindowMetricCollector(
                config, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))) {
            assertThat(collector.recordLatency(1L, RequestStatus.SUCCESS, Map.of("a", "1")))
                    .isEqualTo(Admission.ACCEPTED);
            assertThat(collector.recordLatency(2L, RequestStatus.SUCCESS, Map.of("a", "2")))
                    .isEqualTo(Admission.ACCEPTED);
            collector.awaitProcessed(Duration.ofSeconds(2));

            MetricSnapshot snapshot = collector.snapshot();
            assertThat(snapshot.groups()).containsOnlyKeys(Map.of("a", "1"));
            assertThat(snapshot.dropped())
                    .containsEntry(Admission.DIMENSION_CARDINALITY_EXCEEDED, 1L);
        }
    }
}
