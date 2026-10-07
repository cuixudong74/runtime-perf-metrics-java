package com.github.cuixudong74.runtimeperfmetricsjava.metrics;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 维度 -> 滑动窗口 的注册表。
 *
 * <p>维度数量受 {@code maxDimensions} 上限约束：超出上限时按策略降级到
 * 保留维度 {@code __other__} 或直接拒绝，并通过 {@link RecordOutcome}
 * 给出可区分原因，避免高基数维度拖垮整体采集。</p>
 */
final class MetricsRegistry {

    static final String OTHER_DIMENSION = "__other__";

    private final Clock clock;
    private final ConcurrentHashMap<String, SlidingWindow> windows = new ConcurrentHashMap<>();
    private volatile MetricsConfig config;

    MetricsRegistry(MetricsConfig config, Clock clock) {
        this.config = config;
        this.clock = clock;
    }

    /**
     * 获取维度对应的窗口。
     *
     * @return 目标窗口；被拒绝时返回 {@code null}，原因写入 {@code outcomeHolder[0]}。
     */
    SlidingWindow windowFor(String dimension, RecordOutcome[] outcomeHolder) {
        SlidingWindow existing = windows.get(dimension);
        if (existing != null) {
            outcomeHolder[0] = RecordOutcome.RECORDED;
            return existing;
        }
        MetricsConfig cfg = config;
        if (windows.size() < cfg.getMaxDimensions()) {
            SlidingWindow created = windows.computeIfAbsent(dimension,
                    d -> new SlidingWindow(cfg, clock));
            outcomeHolder[0] = RecordOutcome.RECORDED;
            return created;
        }
        // 基数超限：按策略降级或拒绝。
        if (cfg.getOverflowPolicy() == OverflowPolicy.DEGRADE_TO_OTHER) {
            SlidingWindow other = windows.computeIfAbsent(OTHER_DIMENSION,
                    d -> new SlidingWindow(cfg, clock));
            outcomeHolder[0] = RecordOutcome.CARDINALITY_DEGRADED;
            return other;
        }
        outcomeHolder[0] = RecordOutcome.CARDINALITY_REJECTED;
        return null;
    }

    void reconfigure(MetricsConfig newConfig) {
        this.config = newConfig;
        // 新配置只影响之后的采集与聚合：既有窗口按新参数重建（清空实时数据），
        // 已生成的快照是不可变副本，不会被追溯改写。
        windows.values().forEach(w -> w.reconfigure(newConfig));
    }

    Map<String, SlidingWindow> windows() {
        return Collections.unmodifiableMap(windows);
    }
}
