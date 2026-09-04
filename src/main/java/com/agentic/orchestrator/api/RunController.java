package com.agentic.orchestrator.api;

import com.agentic.orchestrator.execution.EventLog;
import com.agentic.orchestrator.execution.Run;
import com.agentic.orchestrator.execution.RunEvent;
import com.agentic.orchestrator.execution.RunRepository;
import com.agentic.orchestrator.execution.RunScheduler;
import com.agentic.orchestrator.governance.Decision;
import com.agentic.orchestrator.governance.DecisionLog;
import com.agentic.orchestrator.plan.DecompositionPlanner;
import com.agentic.orchestrator.plan.Plan;
import com.agentic.orchestrator.plan.PlanRepository;
import com.agentic.orchestrator.replan.ReplanOutcome;
import com.agentic.orchestrator.replan.ReplanTrigger;
import com.agentic.orchestrator.requirement.RequirementNormalizer;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/runs")
public class RunController {

    private final RunScheduler scheduler;
    private final RunRepository runs;
    private final PlanRepository plans;
    private final EventLog events;
    private final DecisionLog decisions;
    private final RequirementNormalizer normalizer;
    private final DecompositionPlanner planner;

    public RunController(RunScheduler scheduler, RunRepository runs, PlanRepository plans,
                         EventLog events, DecisionLog decisions, RequirementNormalizer normalizer,
                         DecompositionPlanner planner) {
        this.scheduler = scheduler;
        this.runs = runs;
        this.plans = plans;
        this.events = events;
        this.decisions = decisions;
        this.normalizer = normalizer;
        this.planner = planner;
    }

    /**
     * Starts a run. Returns 202 immediately — the run is scheduled, not finished. Poll the run or
     * its event log to observe progress.
     */
    @PostMapping
    public ResponseEntity<RunResponse> start(@RequestBody StartRunRequest request) {
        Plan plan = resolvePlan(request);
        Run run = scheduler.start(plan, request.autonomyOrDefault(), request.baseRunId());
        return ResponseEntity.accepted().body(RunResponse.of(run));
    }

    private Plan resolvePlan(StartRunRequest request) {
        if (request.hasPlanId()) {
            return plans.findById(request.planId())
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "No such plan: " + request.planId()));
        }
        if (request.hasRequirement()) {
            return plans.save(planner.decompose(
                    normalizer.normalize(request.requirement(), request.scenarioHint())));
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Provide either planId or requirement");
    }

    @GetMapping
    public List<RunResponse> list() {
        return runs.findAll().stream().map(RunResponse::of).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<RunResponse> get(@PathVariable String id) {
        return runs.findById(id)
                .map(RunResponse::of)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * The run's append-only log. {@code since} returns only entries after that sequence number, so a
     * client can poll incrementally rather than re-reading the whole log.
     */
    @GetMapping("/{id}/events")
    public ResponseEntity<List<RunEvent>> events(@PathVariable String id,
                                                 @RequestParam(defaultValue = "0") long since) {
        if (runs.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(events.forRunSince(id, since));
    }

    /**
     * The decisions taken during this run and why (CR4.5). Sparser than the event log and far more
     * useful for the question "who decided that, and on what basis".
     */
    @GetMapping("/{id}/decisions")
    public ResponseEntity<List<Decision>> decisions(@PathVariable String id) {
        if (runs.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(decisions.forRun(id));
    }

    @PostMapping("/{id}/stop")
    public ResponseEntity<Map<String, Object>> stop(
            @PathVariable String id,
            @RequestParam(defaultValue = "Stopped by operator") String reason) {

        if (runs.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        boolean stopped = scheduler.stop(id, reason);
        return ResponseEntity.ok(Map.of(
                "runId", id,
                "applied", stopped,
                "detail", stopped
                        ? "Pending nodes cancelled; running nodes will finish before the run stops."
                        : "Run had already reached a terminal state."));
    }

    /** Holds the run. Running nodes finish; nothing new dispatches until resumed. */
    @PostMapping("/{id}/pause")
    public ResponseEntity<Map<String, Object>> pause(
            @PathVariable String id,
            @RequestParam(defaultValue = "Paused by operator") String reason) {

        if (runs.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        boolean paused = scheduler.pause(id, reason);
        return ResponseEntity.ok(Map.of(
                "runId", id,
                "applied", paused,
                "detail", paused
                        ? "Run held; in-flight nodes will finish."
                        : "Run is already paused or has reached a terminal state."));
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<Map<String, Object>> resume(@PathVariable String id) {
        if (runs.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        boolean resumed = scheduler.resume(id);
        return ResponseEntity.ok(Map.of(
                "runId", id,
                "applied", resumed,
                "detail", resumed ? "Scheduling resumed." : "Run was not paused."));
    }

    /**
     * Rebuilds the graph from an amended requirement, keeping the work that is still valid.
     *
     * <p>Returns the outcome rather than a bare boolean: hitting the re-plan bound, detecting
     * oscillation and needing approval of the plan change are all different things, and a caller
     * that cannot tell them apart cannot react sensibly to any of them.
     */
    @PostMapping("/{id}/replan")
    public ResponseEntity<ReplanOutcome> replan(
            @PathVariable String id,
            @RequestParam String guidance) {

        if (runs.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(
                scheduler.replan(id, ReplanTrigger.MANUAL, guidance, false));
    }

    /**
     * Undoes every succeeded node, most recent first.
     *
     * <p>Only permitted on a run that has stopped moving — compensating a node while a dependent is
     * still executing against its output would corrupt both.
     */
    @PostMapping("/{id}/rollback")
    public ResponseEntity<Map<String, Object>> rollback(
            @PathVariable String id,
            @RequestParam(defaultValue = "Rolled back by operator") String reason) {

        if (runs.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        boolean complete = scheduler.rollback(id, reason);
        return ResponseEntity.ok(Map.of(
                "runId", id,
                "complete", complete,
                "detail", complete
                        ? "All succeeded nodes were undone."
                        : "Rollback did not complete: either the run is still active, or a "
                          + "compensation failed and the workspace needs human review."));
    }
}
