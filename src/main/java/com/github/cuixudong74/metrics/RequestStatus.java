package com.github.cuixudong74.metrics;

/** Logical result of a processed request. */
public enum RequestStatus {
    SUCCESS,
    ERROR,
    TIMEOUT,
    CANCELLED,
    UNKNOWN
}
