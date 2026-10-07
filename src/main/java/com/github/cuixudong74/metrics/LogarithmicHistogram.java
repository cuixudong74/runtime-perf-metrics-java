package com.github.cuixudong74.metrics;

import java.util.Arrays;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.TreeMap;

/**
 * Bounded sparse logarithmic histogram used for memory-stable quantile estimation.
 *
 * <p>The estimator uses 32 bins per binary power of two. Positive non-negative durations are
 * supported. Bins are sparse, while the number of possible bins is bounded by the {@code long}
 * value range. Each sample carries a sampling weight, allowing sampled and unsampled estimates to
 * share the same representation.
 */
final class LogarithmicHistogram {

    private static final int BINS_PER_POWER = 32;
    private static final double LN_2 = Math.log(2.0d);

    private long sampleCount;
    private double weightedCount;
    private double zeroWeight;
    private final TreeMap<Integer, Double> bins = new TreeMap<>();

    LogarithmicHistogram() {
    }

    void add(long value, double weight) {
        if (weight <= 0.0d || !Double.isFinite(weight)) {
            throw new IllegalArgumentException("weight must be finite and positive");
        }
        if (value < 0L) {
            throw new IllegalArgumentException("duration must be non-negative");
        }
        sampleCount++;
        weightedCount += weight;
        if (value == 0L) {
            zeroWeight += weight;
            return;
        }
        int index = (int) Math.floor(BINS_PER_POWER * (Math.log((double) value) / LN_2));
        bins.merge(index, weight, Double::sum);
    }

    /** Add all bins in this histogram to the destination without modifying this histogram. */
    void addInto(LogarithmicHistogram target) {
        target.sampleCount += sampleCount;
        target.weightedCount += weightedCount;
        target.zeroWeight += zeroWeight;
        bins.forEach((index, weight) -> target.bins.merge(index, weight, Double::sum));
    }

    long sampleCount() {
        return sampleCount;
    }

    double weightedCount() {
        return weightedCount;
    }

    /**
     * Calculate weighted quantiles. Values are interpolated logarithmically inside the bin that
     * contains the requested cumulative weight, making the relative error small without retaining
     * every sample.
     */
    Map<Double, Double> quantiles(double... quantiles) {
        double[] sorted = quantiles.clone();
        Arrays.sort(sorted);
        for (double quantile : sorted) {
            if (!Double.isFinite(quantile) || quantile < 0.0d || quantile > 1.0d) {
                throw new IllegalArgumentException("quantile must be in [0, 1]");
            }
        }

        Map<Double, Double> result = new LinkedHashMap<>();
        double total = weightedCount;
        if (total <= 0.0d) {
            for (double quantile : quantiles) {
                result.put(quantile, 0.0d);
            }
            return result;
        }

        double cumulative = 0.0d;
        if (zeroWeight > 0.0d) {
            cumulative += zeroWeight;
        }

        int next = 0;
        while (next < sorted.length && sorted[next] * total <= cumulative) {
            result.put(sorted[next], 0.0d);
            next++;
        }

        for (Map.Entry<Integer, Double> entry : bins.entrySet()) {
            double before = cumulative;
            double weight = entry.getValue();
            cumulative += weight;
            while (next < sorted.length) {
                double quantile = sorted[next];
                double targetWeight = quantile * total;
                if (targetWeight > cumulative && quantile < 1.0d) {
                    break;
                }
                result.put(quantile, interpolate(entry.getKey(), before, weight, targetWeight));
                next++;
            }
            if (next == sorted.length) {
                break;
            }
        }

        while (next < sorted.length) {
            result.put(sorted[next], bins.isEmpty() ? 0.0d
                    : Math.pow(2.0d, (bins.lastKey() + 1) / (double) BINS_PER_POWER));
            next++;
        }

        // Preserve caller-facing values even when duplicate quantiles were supplied.
        Map<Double, Double> ordered = new LinkedHashMap<>();
        for (double quantile : quantiles) {
            ordered.put(quantile, result.get(quantile));
        }
        return ordered;
    }

    private double interpolate(int index, double before, double weight, double targetWeight) {
        double lower = Math.pow(2.0d, index / (double) BINS_PER_POWER);
        double upper = Math.pow(2.0d, (index + 1) / (double) BINS_PER_POWER);
        double fraction = weight == 0.0d ? 0.0d : (targetWeight - before) / weight;
        fraction = Math.max(0.0d, Math.min(1.0d, fraction));
        return lower * Math.pow(upper / lower, fraction);
    }

}
