package com.github.cuixudong74.runtimeperfmetricsjava.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SlidingWindowTest {

    private static MetricsConfig config(long windowMillis, int buckets) {
        return new MetricsConfig(windowMillis, buckets, 1.0, 100,
                OverflowPolicy.DEGRADE_TO_OTHER, 4096);
    }

    @Test
    void aggregatesWithinWindow() {
        MutableClock clock = new MutableClock(1_000_000);
        SlidingWindow w = new SlidingWindow(config(60_000, 60), clock);
        w.record(100, RequestResult.SUCCESS);
        w.record(200, RequestResult.FAILURE);
        w.record(300, RequestResult.SUCCESS);

        WindowSnapshot.DimensionStats s = w.snapshot();
        assertEquals(3, s.getTotalCount());
        assertEquals(2, s.getSuccessCount());
        assertEquals(1, s.getFailureCount());
        assertEquals(600, s.getTotalLatencyNanos());
        assertEquals(100, s.getMinLatencyNanos());
        assertEquals(300, s.getMaxLatencyNanos());
    }

    @Test
    void rotationEvictsExpiredBuckets() {
        MutableClock clock = new MutableClock(1_000_000);
        // 窗口 10s，10 个桶，每桶 1s。
        SlidingWindow w = new SlidingWindow(config(10_000, 10), clock);
        w.record(100, RequestResult.SUCCESS);
        assertEquals(1, w.snapshot().getTotalCount());

        // 前进 5s：旧数据仍在窗口内。
        clock.advance(5_000);
        w.record(200, RequestResult.SUCCESS);
        assertEquals(2, w.snapshot().getTotalCount());

        // 前进超过窗口长度：旧数据被淘汰，不出现无界累积。
        clock.advance(11_000);
        w.record(300, RequestResult.SUCCESS);
        WindowSnapshot.DimensionStats s = w.snapshot();
        assertEquals(1, s.getTotalCount());
        assertEquals(300, s.getTotalLatencyNanos());
    }

    @Test
    void quantilesAreComputedFromSamples() {
        MutableClock clock = new MutableClock(1_000_000);
        SlidingWindow w = new SlidingWindow(config(60_000, 60), clock);
        for (long i = 1; i <= 100; i++) {
            w.record(i, RequestResult.SUCCESS);
        }
        WindowSnapshot.DimensionStats s = w.snapshot();
        assertEquals(100, s.getTotalCount());
        assertEquals(50.0, s.getP50Nanos());
        assertEquals(90.0, s.getP90Nanos());
        assertEquals(99.0, s.getP99Nanos());
    }

    @Test
    void emptyWindowReportsZeros() {
        SlidingWindow w = new SlidingWindow(config(60_000, 60), new MutableClock(0));
        WindowSnapshot.DimensionStats s = w.snapshot();
        assertEquals(0, s.getTotalCount());
        assertEquals(0, s.getMinLatencyNanos());
        assertEquals(0, s.getMaxLatencyNanos());
        assertEquals(0.0, s.getP99Nanos());
    }
}
