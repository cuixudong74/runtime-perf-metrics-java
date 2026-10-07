package com.github.cuixudong74.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Map;
import org.junit.jupiter.api.Test;

class LogarithmicHistogramTest {

    @Test
    void estimatesQuantilesWithBoundedRelativeError() {
        LogarithmicHistogram histogram = new LogarithmicHistogram();
        for (long value = 1L; value <= 10_000L; value++) {
            histogram.add(value, 1.0d);
        }

        Map<Double, Double> quantiles = histogram.quantiles(0.50d, 0.90d, 0.95d, 0.99d);

        assertThat(histogram.sampleCount()).isEqualTo(10_000L);
        assertThat(histogram.weightedCount()).isCloseTo(10_000.0d, within(0.000_001d));
        assertThat(quantiles.get(0.50d)).isCloseTo(5_000.0d, within(250.0d));
        assertThat(quantiles.get(0.90d)).isCloseTo(9_000.0d, within(450.0d));
        assertThat(quantiles.get(0.99d)).isCloseTo(9_900.0d, within(500.0d));
    }

    @Test
    void supportsZeroValuesAndWeights() {
        LogarithmicHistogram histogram = new LogarithmicHistogram();
        histogram.add(0L, 1.0d);
        histogram.add(100L, 9.0d);

        Map<Double, Double> quantiles = histogram.quantiles(0.0d, 0.11d, 1.0d);

        assertThat(histogram.sampleCount()).isEqualTo(2L);
        assertThat(histogram.weightedCount()).isEqualTo(10.0d);
        assertThat(quantiles.get(0.0d)).isZero();
        assertThat(quantiles.get(0.11d)).isGreaterThan(0.0d);
        assertThat(quantiles.get(1.0d)).isLessThanOrEqualTo(128.0d);
    }
}
