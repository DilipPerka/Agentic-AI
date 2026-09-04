package com.agentic.orchestrator.api;

import com.agentic.orchestrator.metrics.MetricsService;
import com.agentic.orchestrator.metrics.MetricsSnapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Reliability metrics (CR4.10), fleet-wide or for one run. */
@RestController
@RequestMapping("/api/v1/metrics")
public class MetricsController {

    private final MetricsService metrics;

    public MetricsController(MetricsService metrics) {
        this.metrics = metrics;
    }

    @GetMapping
    public MetricsSnapshot overall() {
        return metrics.overall();
    }

    /**
     * Unknown run ids return an empty snapshot rather than 404: the console asks for metrics on the
     * currently-selected run, and a 404 mid-poll would blank a dashboard that is otherwise fine.
     */
    @GetMapping("/runs/{id}")
    public MetricsSnapshot forRun(@PathVariable String id) {
        return metrics.forRun(id);
    }
}
