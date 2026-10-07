package com.github.cuixudong74.runtimeperfmetricsjava.metrics;

/** 时间源抽象，便于测试窗口轮转。 */
public interface Clock {
    long currentTimeMillis();

    static Clock system() {
        return System::currentTimeMillis;
    }
}
