package com.github.cuixudong74.runtimeperfmetricsjava.metrics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 单维度的滑动时间窗口聚合器。
 *
 * <p>内部为定长环形桶数组：窗口长度固定、桶数固定，窗口轮转时只重置过期桶，
 * 内存占用有界，长时间运行不会膨胀。</p>
 *
 * <p>写路径只触碰当前桶；桶内所有计数字段的更新与拷贝都在桶级监视器下原子完成，
 * 临界区仅含若干次内存加法，单次写入开销稳定。轮转与快照在窗口级锁下进行，
 * 快照读取的是逐桶自洽的状态副本，不会读到半个窗口或撕裂的统计值。</p>
 */
final class SlidingWindow {

    /**
     * 单个时间桶。所有字段的读写都在 {@code synchronized} 方法内完成，
     * 保证一次写入对一组计数器是原子的，快照拷贝不会看到更新到一半的状态。
     */
    private static final class Bucket {
        long startMillis = Long.MIN_VALUE;
        long totalCount;
        long successCount;
        long failureCount;
        long totalLatencyNanos;
        long minLatencyNanos = Long.MAX_VALUE;
        long maxLatencyNanos = Long.MIN_VALUE;
        /** 有界蓄水池样本，用于分位数计算；满了之后按蓄水池算法等概率替换。 */
        final List<Long> samples = new ArrayList<>();
        long sampleSeen;

        private void reset(long alignedStart) {
            startMillis = alignedStart;
            totalCount = 0;
            successCount = 0;
            failureCount = 0;
            totalLatencyNanos = 0;
            minLatencyNanos = Long.MAX_VALUE;
            maxLatencyNanos = Long.MIN_VALUE;
            samples.clear();
            sampleSeen = 0;
        }

        /** 若桶属于旧周期则先重置（淘汰过期数据），再原子地累加本次记录。 */
        synchronized void addForPeriod(long alignedStart, long latencyNanos,
                                       RequestResult result, int maxSamples) {
            if (startMillis != alignedStart) {
                reset(alignedStart);
            }
            totalCount++;
            if (result == RequestResult.SUCCESS) {
                successCount++;
            } else {
                failureCount++;
            }
            totalLatencyNanos += latencyNanos;
            minLatencyNanos = Math.min(minLatencyNanos, latencyNanos);
            maxLatencyNanos = Math.max(maxLatencyNanos, latencyNanos);
            sampleSeen++;
            if (samples.size() < maxSamples) {
                samples.add(latencyNanos);
            } else {
                // 蓄水池采样：第 n 个样本以 maxSamples/n 概率替换，保证有界且无偏。
                int idx = ThreadLocalRandom.current().nextInt((int) Math.min(sampleSeen, Integer.MAX_VALUE));
                if (idx < maxSamples) {
                    samples.set(idx, latencyNanos);
                }
            }
        }

        /** 在桶级监视器下拷贝全部字段，得到该桶某一时刻的自洽副本。 */
        synchronized BucketState copy() {
            return new BucketState(startMillis, totalCount, successCount, failureCount,
                    totalLatencyNanos, minLatencyNanos, maxLatencyNanos,
                    samples.stream().mapToLong(Long::longValue).toArray());
        }
    }

    /** 桶状态的不可变副本，快照在锁内拷贝、锁外聚合。 */
    private record BucketState(long startMillis, long total, long success, long failure,
                               long latencySum, long min, long max, long[] samples) {
    }

    private final Clock clock;
    private volatile MetricsConfig config;
    private volatile Bucket[] buckets;
    private final Object rotationLock = new Object();

    SlidingWindow(MetricsConfig config, Clock clock) {
        this.config = config;
        this.clock = clock;
        this.buckets = newBuckets(config.getBucketCount());
    }

    private static Bucket[] newBuckets(int count) {
        Bucket[] arr = new Bucket[Math.max(1, count)];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = new Bucket();
        }
        return arr;
    }

    private int indexFor(long alignedStart, long bucketLen, int bucketCount) {
        return (int) Math.floorMod(alignedStart / bucketLen, bucketCount);
    }

    void record(long latencyNanos, RequestResult result) {
        MetricsConfig cfg = config;
        long bucketLen = cfg.bucketLengthMillis();
        long aligned = clock.currentTimeMillis() / bucketLen * bucketLen;
        Bucket[] bs = buckets;
        Bucket b = bs[indexFor(aligned, bucketLen, bs.length)];
        // 周期检查、过期重置与写入在桶级监视器下原子完成：
        // 轮转边界上的写入要么进入旧周期末尾、要么进入新周期开头，不会丢失或写进半个桶。
        b.addForPeriod(aligned, latencyNanos, result, cfg.getMaxSamplesPerBucket());
    }

    WindowSnapshot.DimensionStats snapshot() {
        long now = clock.currentTimeMillis();
        MetricsConfig cfg = config;
        long bucketLen = cfg.bucketLengthMillis();
        long windowLen = cfg.getWindowLengthMillis();
        long windowStart = now - windowLen;

        // 逐桶拷贝自洽副本（每桶在自己的监视器下原子拷贝），拷贝过程不阻塞写线程的计数更新，
        // 只与同一桶的写入互斥且临界区极短。
        Bucket[] bs = buckets;
        BucketState[] states = new BucketState[bs.length];
        for (int i = 0; i < bs.length; i++) {
            states[i] = bs[i].copy();
        }

        long total = 0, success = 0, failure = 0, latencySum = 0;
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        List<long[]> sampleParts = new ArrayList<>();
        int sampleTotal = 0;
        for (BucketState s : states) {
            // 只统计落在当前窗口内的桶，过期桶视为已淘汰。
            if (s.startMillis() == Long.MIN_VALUE || s.startMillis() + bucketLen <= windowStart) {
                continue;
            }
            total += s.total();
            success += s.success();
            failure += s.failure();
            latencySum += s.latencySum();
            min = Math.min(min, s.min());
            max = Math.max(max, s.max());
            sampleParts.add(s.samples());
            sampleTotal += s.samples().length;
        }
        if (total == 0) {
            min = 0;
            max = 0;
        }

        double p50 = 0, p90 = 0, p99 = 0;
        if (sampleTotal > 0) {
            long[] all = new long[sampleTotal];
            int pos = 0;
            for (long[] part : sampleParts) {
                System.arraycopy(part, 0, all, pos, part.length);
                pos += part.length;
            }
            Arrays.sort(all);
            p50 = quantile(all, 0.50);
            p90 = quantile(all, 0.90);
            p99 = quantile(all, 0.99);
        }
        return new WindowSnapshot.DimensionStats(total, success, failure, latencySum,
                min, max, p50, p90, p99);
    }

    /** 最近秩法分位数，输入必须已排序。 */
    static double quantile(long[] sorted, double q) {
        if (sorted.length == 0) {
            return 0;
        }
        int rank = (int) Math.ceil(q * sorted.length);
        rank = Math.max(1, Math.min(rank, sorted.length));
        return sorted[rank - 1];
    }

    /** 配置变更只影响之后的采集：按新配置重建空窗口，历史快照不受影响。 */
    void reconfigure(MetricsConfig newConfig) {
        synchronized (rotationLock) {
            this.config = newConfig;
            this.buckets = newBuckets(newConfig.getBucketCount());
        }
    }
}
