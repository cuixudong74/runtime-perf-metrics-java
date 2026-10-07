package com.github.cuixudong74.runtimeperfmetricsjava.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsCollectorConfigTest {

    @Test
    void configChangeAffectsOnlyFutureCollection() {
        MutableClock clock = new MutableClock(1_000_000);
        MetricsCollector collector = new MetricsCollector(
                new MetricsConfig(60_000, 60, 1.0, 100, OverflowPolicy.DEGRADE_TO_OTHER, 4096), clock);
        collector.record("api", 100, RequestResult.SUCCESS);

        // 变更前的快照：包含历史数据，且之后不得被追溯改写。
        WindowSnapshot before = collector.snapshot();
        assertEquals(1, before.getDimensions().get("api").getTotalCount());

        // 缩短窗口并重建：只影响之后的采集与聚合。
        collector.reconfigure(new MetricsConfig(10_000, 10, 1.0, 100,
                OverflowPolicy.DEGRADE_TO_OTHER, 4096));
        WindowSnapshot after = collector.snapshot();
        assertEquals(10_000, after.getWindowEndMillis() - after.getWindowStartMillis());
        assertEquals(0, after.getDimensions().get("api").getTotalCount());

        // 旧快照保持不变。
        assertEquals(1, before.getDimensions().get("api").getTotalCount());

        // 新配置下采集正常进行。
        collector.record("api", 50, RequestResult.SUCCESS);
        assertEquals(1, collector.snapshot().getDimensions().get("api").getTotalCount());
    }

    @Test
    void sampleRateControlsCollection() {
        MutableClock clock = new MutableClock(1_000_000);
        // 采样率 0：全部丢弃。
        MetricsCollector none = new MetricsCollector(
                new MetricsConfig(60_000, 60, 0.0, 100, OverflowPolicy.DEGRADE_TO_OTHER, 4096), clock);
        assertEquals(RecordOutcome.SAMPLED_OUT, none.record("api", 10, RequestResult.SUCCESS));
        assertTrue(none.snapshot().getDimensions().isEmpty());

        // 采样率 0.3：大约 30% 被记录，允许统计误差。
        MetricsCollector sampled = new MetricsCollector(
                new MetricsConfig(60_000, 60, 0.3, 100, OverflowPolicy.DEGRADE_TO_OTHER, 4096), clock);
        int n = 100_000;
        for (int i = 0; i < n; i++) {
            sampled.record("api", 10, RequestResult.SUCCESS);
        }
        long count = sampled.snapshot().getDimensions().get("api").getTotalCount();
        assertTrue(count > n * 0.25 && count < n * 0.35, "count=" + count);
    }

    @Test
    void cardinalityOverflowDegradesToOther() {
        MetricsCollector collector = new MetricsCollector(
                new MetricsConfig(60_000, 60, 1.0, 2, OverflowPolicy.DEGRADE_TO_OTHER, 4096));
        assertEquals(RecordOutcome.RECORDED, collector.record("a", 1, RequestResult.SUCCESS));
        assertEquals(RecordOutcome.RECORDED, collector.record("b", 1, RequestResult.SUCCESS));
        // 超过上限：降级到 __other__，原因可区分。
        assertEquals(RecordOutcome.CARDINALITY_DEGRADED, collector.record("c", 1, RequestResult.SUCCESS));
        assertEquals(RecordOutcome.CARDINALITY_DEGRADED, collector.record("d", 1, RequestResult.SUCCESS));

        WindowSnapshot snap = collector.snapshot();
        assertEquals(1, snap.getDimensions().get("a").getTotalCount());
        assertEquals(2, snap.getDimensions().get(MetricsRegistry.OTHER_DIMENSION).getTotalCount());
    }

    @Test
    void cardinalityOverflowRejectsWhenConfigured() {
        MetricsCollector collector = new MetricsCollector(
                new MetricsConfig(60_000, 60, 1.0, 1, OverflowPolicy.REJECT, 4096));
        assertEquals(RecordOutcome.RECORDED, collector.record("a", 1, RequestResult.SUCCESS));
        assertEquals(RecordOutcome.CARDINALITY_REJECTED, collector.record("b", 1, RequestResult.SUCCESS));
        assertEquals(1, collector.snapshot().getDimensions().size());
    }

    @Test
    void invalidInputIsIsolatedAndNeverThrows() {
        MetricsCollector collector = new MetricsCollector(MetricsConfig.defaults());
        assertEquals(RecordOutcome.FAILED, collector.record(null, 1, RequestResult.SUCCESS));
        assertEquals(RecordOutcome.FAILED, collector.record("api", -1, RequestResult.SUCCESS));
        assertEquals(RecordOutcome.FAILED, collector.record("api", 1, null));
        // 失败记录不影响后续正常采集。
        assertEquals(RecordOutcome.RECORDED, collector.record("api", 1, RequestResult.SUCCESS));
        assertEquals(1, collector.snapshot().getDimensions().get("api").getTotalCount());
    }
}
