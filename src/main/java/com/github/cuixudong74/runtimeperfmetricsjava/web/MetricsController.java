package com.github.cuixudong74.runtimeperfmetricsjava.web;

import com.github.cuixudong74.runtimeperfmetricsjava.metrics.MetricsCollector;
import com.github.cuixudong74.runtimeperfmetricsjava.metrics.WindowSnapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 对外暴露指标快照查询。 */
@RestController
@RequestMapping("/metrics")
public class MetricsController {
    private final MetricsCollector collector;

    public MetricsController(MetricsCollector collector) {
        this.collector = collector;
    }

    @GetMapping("/snapshot")
    public WindowSnapshot snapshot() {
        return collector.snapshot();
    }
}
