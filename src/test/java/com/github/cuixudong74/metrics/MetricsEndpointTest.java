package com.github.cuixudong74.metrics;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import com.github.cuixudong74.runtimeperfmetricsjava.Application;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
class MetricsEndpointTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    MetricCollector collector;

    @Test
    void exposesImmutableSnapshotOverHttp() throws Exception {
        collector.recordLatency(1_000L, RequestStatus.SUCCESS);

        mockMvc.perform(get("/actuator/metrics/request-runtime"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sampleCount").exists())
                .andExpect(jsonPath("$.estimatedCount").exists())
                .andExpect(jsonPath("$.quantilesNanos['0.5']").exists())
                .andExpect(jsonPath("$.groups").exists())
                .andExpect(jsonPath("$.dropped").exists());
    }
}
