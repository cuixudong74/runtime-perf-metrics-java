package com.github.cuixudong74.runtimeperfmetricsjava.metrics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsCollectorConcurrencyTest {

    private static MetricsConfig config() {
        return new MetricsConfig(60_000, 60, 1.0, 10_000,
                OverflowPolicy.DEGRADE_TO_OTHER, 4096);
    }

    @Test
    void concurrentWritesAreNotLostOrDuplicated() throws Exception {
        MetricsCollector collector = new MetricsCollector(config());
        int threads = 16;
        int perThread = 5_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        collector.record("api", 10, RequestResult.SUCCESS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        WindowSnapshot.DimensionStats s = collector.snapshot().getDimensions().get("api");
        long expected = (long) threads * perThread;
        // 高并发写入下总数必须精确：不丢失、不重复。
        assertEquals(expected, s.getTotalCount());
        assertEquals(expected, s.getSuccessCount());
        assertEquals(expected * 10, s.getTotalLatencyNanos());
    }

    @Test
    void snapshotIsConsistentAcrossWindowRotation() throws Exception {
        MutableClock clock = new MutableClock(1_000_000);
        MetricsCollector collector = new MetricsCollector(config(), clock);
        int writers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < writers; t++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 2_000; i++) {
                        collector.record("api", 5, RequestResult.SUCCESS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown();

        // 写入期间反复轮转窗口并读取快照：任何时刻快照内部必须自洽。
        for (int i = 0; i < 200; i++) {
            clock.advance(1_000);
            WindowSnapshot snap = collector.snapshot();
            for (WindowSnapshot.DimensionStats s : snap.getDimensions().values()) {
                assertEquals(s.getTotalCount(), s.getSuccessCount() + s.getFailureCount(),
                        "total must equal success+failure at any instant");
                assertTrue(s.getTotalCount() >= 0);
                assertTrue(s.getMinLatencyNanos() <= s.getMaxLatencyNanos());
            }
        }
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
    }
}
