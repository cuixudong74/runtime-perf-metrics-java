package com.github.cuixudong74.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ConfigurationChangeTest {

    @Test
    void sampleRateChangeAffectsOnlyLaterRecordsAndPriorSnapshotsRemainStable() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        try (SlidingWindowMetricCollector collector =
                     new SlidingWindowMetricCollector(MetricsConfig.defaults(), clock)) {
            assertThat(collector.recordLatency(10L, RequestStatus.SUCCESS))
                    .isEqualTo(Admission.ACCEPTED);
            collector.awaitProcessed(Duration.ofSeconds(2));
            MetricSnapshot before = collector.snapshot();
            assertThat(before.sampleCount()).isOne();

            MetricsConfig sampled = new MetricsConfig(
                    true, 0.10d, Duration.ofMinutes(1), Duration.ofSeconds(1),
                    100, 8, CardinalityPolicy.DEGRADE, 1_024, "__degraded", "true");
            assertThat(collector.updateConfig(sampled)).isEqualTo(Admission.ACCEPTED);
            collector.awaitProcessed(Duration.ofSeconds(2));
            assertThat(collector.currentConfig().sampleRate()).isEqualTo(0.10d);

            for (int i = 0; i < 10_000; i++) {
                Admission admission = collector.recordLatency(20L, RequestStatus.SUCCESS);
                assertThat(admission == Admission.ACCEPTED || admission == Admission.SAMPLED_OUT)
                        .isTrue();
            }
            collector.awaitProcessed(Duration.ofSeconds(5));

            MetricSnapshot after = collector.snapshot();
            assertThat(after.sampleCount()).isBetween(700L, 1_300L);
            assertThat(after.estimatedCount()).isCloseTo(10_001.0d, within(1_200.0d));
            assertThat(before.sampleCount()).isOne();
            assertThat(before.estimatedCount()).isEqualTo(1.0d);
        }
    }

    @Test
    void rejectsInvalidConfigurationWithoutChangingCurrentConfiguration() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        try (SlidingWindowMetricCollector collector =
                     new SlidingWindowMetricCollector(MetricsConfig.defaults(), clock)) {
            assertThat(collector.updateConfig(null)).isEqualTo(Admission.INVALID_ARGUMENT);
            assertThat(collector.currentConfig().sampleRate()).isEqualTo(1.0d);
        }
    }

    @Test
    void nonStructuralPolicyChangeDoesNotResetExistingWindow() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        try (SlidingWindowMetricCollector collector =
                     new SlidingWindowMetricCollector(MetricsConfig.defaults(), clock)) {
            collector.recordLatency(10L, RequestStatus.SUCCESS);
            collector.awaitProcessed(Duration.ofSeconds(2));

            MetricsConfig rejectCardinality = new MetricsConfig(
                    true, 0.50d, Duration.ofMinutes(1), Duration.ofSeconds(1),
                    100, 8, CardinalityPolicy.REJECT, 1_024, "__degraded", "true");
            assertThat(collector.updateConfig(rejectCardinality)).isEqualTo(Admission.ACCEPTED);
            collector.awaitProcessed(Duration.ofSeconds(2));

            MetricSnapshot snapshot = collector.snapshot();
            assertThat(snapshot.sampleCount()).isOne();
            assertThat(collector.currentConfig().cardinalityPolicy())
                    .isEqualTo(CardinalityPolicy.REJECT);
            assertThat(collector.currentConfig().sampleRate()).isEqualTo(0.50d);
        }
    }
}
