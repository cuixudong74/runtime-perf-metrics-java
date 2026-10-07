package com.github.cuixudong74.runtimeperfmetricsjava.metrics;

import com.github.cuixudong74.runtimeperfmetricsjava.Application;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MetricsEndpointTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    MetricsCollector collector;

    @Test
    void snapshotEndpointExposesRecordedMetrics() {
        collector.record("endpoint-test", 123, RequestResult.SUCCESS);
        WindowSnapshot snap = rest.getForObject(
                "http://localhost:" + port + "/metrics/snapshot", WindowSnapshot.class);
        assertTrue(snap.getDimensions().containsKey("endpoint-test"));
        assertEquals(1, snap.getDimensions().get("endpoint-test").getTotalCount());
        assertTrue(snap.getWindowEndMillis() >= snap.getWindowStartMillis());
    }
}
