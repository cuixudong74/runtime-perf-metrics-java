package com.github.cuixudong74.runtimeperfmetricsjava.metrics;

/** 单次采集的结果，用于区分正常记录与各类降级/拒绝原因。 */
public enum RecordOutcome {
    RECORDED,
    SAMPLED_OUT,
    CARDINALITY_DEGRADED,
    CARDINALITY_REJECTED,
    FAILED
}
