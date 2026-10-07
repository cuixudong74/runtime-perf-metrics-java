package com.github.cuixudong74.metrics;

/** Policy used when a bucket already contains the maximum number of dimension groups. */
public enum CardinalityPolicy {
    /** Reject only the newly observed dimension group. */
    REJECT,
    /** Merge newly observed groups into one bounded degraded group. */
    DEGRADE
}
