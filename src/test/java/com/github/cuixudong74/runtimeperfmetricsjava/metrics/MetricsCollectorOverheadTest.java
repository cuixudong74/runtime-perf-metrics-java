package com.github.cuixudong74.runtimeperfmetricsjava.metrics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 采集开销可度量：高并发下单次 record 平均耗时保持稳定且有界。 */
class MetricsCollectorOverheadTest {

    @Test
    void recordOverheadIsStableAndBounded() throws Exception {
        MetricsCollector collector = new MetricsCollector(MetricsConfig.defaults());
        // 预热 JIT。
        for (int i = 0; i < 100_000; i++) {
            collector.record("warmup", 10, RequestResult.SUCCESS);
        }

        int threads = 8;
        int perThread = 50_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        long[] nanos = new long[threads];
        for (int t = 0; t < threads; t++) {
            final int idx = t;
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                long t0 = System.nanoTime();
                for (int i = 0; i < perThread; i++) {
                    collector.record("api", 10, RequestResult.SUCCESS);
                }
                nanos[idx] = System.nanoTime() - t0;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

        long totalOps = (long) threads * perThread;
        long totalNanos = 0;
        for (long n : nanos) {
            totalNanos += n;
        }
        double avgNanosPerOp = (double) totalNanos / totalOps;
        System.out.printf("avg record overhead: %.1f ns/op over %d ops on %d threads%n",
                avgNanosPerOp, totalOps, threads);
        // 宽松上界：平均单次采集 < 10 微秒（典型值远低于此），验证开销有界可度量。
        assertTrue(avgNanosPerOp < 10_000, "avg overhead too high: " + avgNanosPerOp);
    }
}
