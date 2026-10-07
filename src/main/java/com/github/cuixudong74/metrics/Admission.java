package com.github.cuixudong74.metrics;

/** Result of submitting one measurement or configuration change. */
public enum Admission {
    ACCEPTED(false),
    DISABLED(true),
    SHUTDOWN(true),
    INVALID_ARGUMENT(true),
    SAMPLED_OUT(false),
    QUEUE_FULL(true),
    DIMENSION_CARDINALITY_EXCEEDED(true),
    EXPIRED(true),
    INTERNAL_ERROR(true);

    private final boolean dropped;

    Admission(boolean dropped) {
        this.dropped = dropped;
    }

    public boolean accepted() {
        return this == ACCEPTED;
    }

    public boolean dropped() {
        return dropped;
    }
}
