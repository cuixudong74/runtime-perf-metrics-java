package com.github.cuixudong74.runtimeperfmetricsjava.metrics;

import java.util.concurrent.atomic.AtomicLong;

/** 测试用可控时钟。 */
final class MutableClock implements Clock {
    private final AtomicLong now;

    MutableClock(long initial) {
        this.now = new AtomicLong(initial);
    }

    @Override
    public long currentTimeMillis() {
        return now.get();
    }

    void advance(long millis) {
        now.addAndGet(millis);
    }

    void set(long millis) {
        now.set(millis);
    }
}
