package com.github.cuixudong74.runtimeperfmetricsjava.metrics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 对外采集入口。
 *
 * <p>采集动作在调用线程内完成但只做无锁/短临界区的内存操作，不执行 IO、
 * 不阻塞业务线程；单条记录的任何异常都被捕获隔离，绝不逃逸到业务链路。</p>
 *
 * <p>配置通过 {@link #reconfigure(MetricsConfig)} 整体替换（volatile 语义），
 * 只影响之后的采集与聚合；快照为不可变副本，读取结果对应某一时刻的自洽状态。</p>
 */
public class MetricsCollector {

    private final Clock clock;
    private final MetricsRegistry registry;
    private volatile MetricsConfig config;

    public MetricsCollector(MetricsConfig config) {
        this(config, Clock.system());
    }

    MetricsCollector(MetricsConfig config, Clock clock) {
        this.clock = clock;
        this.config = config;
        this.registry = new MetricsRegistry(config, clock);
    }

    /**
     * 记录一次请求的耗时与结果状态。
     *
     * <p>非阻塞、异常隔离：任何内部失败都转化为 {@link RecordOutcome#FAILED} 返回，
     * 不影响其他记录，也不向业务线程抛异常。</p>
     *
     * @return 本次采集的结果（含降级/拒绝原因），便于调用方观测采集健康度。
     */
    public RecordOutcome record(String dimension, long latencyNanos, RequestResult result) {
        try {
            if (dimension == null || result == null || latencyNanos < 0) {
                return RecordOutcome.FAILED;
            }
            MetricsConfig cfg = config;
            double rate = cfg.getSampleRate();
            if (rate < 1.0 && (rate <= 0.0 || ThreadLocalRandom.current().nextDouble() >= rate)) {
                return RecordOutcome.SAMPLED_OUT;
            }
            RecordOutcome[] holder = new RecordOutcome[1];
            SlidingWindow window = registry.windowFor(dimension, holder);
            if (window == null) {
                return holder[0];
            }
            window.record(latencyNanos, result);
            return holder[0];
        } catch (Throwable t) {
            // 异常隔离：吞掉并标记失败，绝不影响业务请求链路。
            return RecordOutcome.FAILED;
        }
    }

    /** 便捷计时：返回起始时间戳，配合 {@link #record} 使用。 */
    public long startNanos() {
        return System.nanoTime();
    }

    /** 获取当前时刻的自洽快照；读取失败时返回空快照而非抛异常。 */
    public WindowSnapshot snapshot() {
        try {
            long now = clock.currentTimeMillis();
            MetricsConfig cfg = config;
            Map<String, WindowSnapshot.DimensionStats> dims = new LinkedHashMap<>();
            for (Map.Entry<String, SlidingWindow> e : registry.windows().entrySet()) {
                try {
                    dims.put(e.getKey(), e.getValue().snapshot());
                } catch (Throwable t) {
                    // 单个维度聚合失败不影响其他维度。
                }
            }
            return new WindowSnapshot(now, now - cfg.getWindowLengthMillis(), now, dims);
        } catch (Throwable t) {
            return new WindowSnapshot(0, 0, 0, Map.of());
        }
    }

    /** 热更新配置：仅影响之后的采集与聚合，已有快照不被追溯改写。 */
    public void reconfigure(MetricsConfig newConfig) {
        if (newConfig == null) {
            return;
        }
        this.config = newConfig;
        registry.reconfigure(newConfig);
    }

    public MetricsConfig currentConfig() {
        return config;
    }
}
