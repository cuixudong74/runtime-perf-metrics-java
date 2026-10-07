package com.github.cuixudong74.runtimeperfmetricsjava.metrics;

/** 维度基数超上限时的处理策略。 */
public enum OverflowPolicy {
    /** 归并到保留维度 "__other__"。 */
    DEGRADE_TO_OTHER,
    /** 直接拒绝该次记录。 */
    REJECT
}
