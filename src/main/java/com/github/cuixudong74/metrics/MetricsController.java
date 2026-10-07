package com.github.cuixudong74.metrics;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only endpoint exposing an immutable point-in-time metrics snapshot. */
@RestController
@RequestMapping("/actuator/metrics/request-runtime")
public class MetricsController {

    private final MetricCollector collector;

    MetricsController(MetricCollector collector) {
        this.collector = collector;
    }

    @GetMapping
    MetricSnapshot snapshot() {
        return collector.snapshot();
    }
}
