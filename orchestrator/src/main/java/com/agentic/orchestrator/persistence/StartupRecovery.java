package com.agentic.orchestrator.persistence;

import com.agentic.orchestrator.execution.EventLog;
import com.agentic.orchestrator.execution.Run;
import com.agentic.orchestrator.execution.RunRepository;
import com.agentic.orchestrator.execution.RunScheduler;
import com.agentic.orchestrator.governance.ApprovalRepository;
import com.agentic.orchestrator.governance.ApprovalRequest;
import com.agentic.orchestrator.governance.DecisionLog;
import com.agentic.orchestrator.plan.Plan;
import com.agentic.orchestrator.plan.PlanRepository;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Rebuilds live state from storage when the process starts.
 *
 * <p>Runs on {@code ApplicationReadyEvent} rather than {@code @PostConstruct}: recovery reads across
 * five collaborators and then re-enters the scheduler, and doing that while the context is still
 * wiring itself makes initialisation order load-bearing. Waiting until the context is up removes
 * that class of problem entirely.
 *
 * <p>Order matters within the method: plans first, because a run cannot be reconstructed without the
 * plan it executes.
 */
@Component
public class StartupRecovery {

    private static final Logger log = LoggerFactory.getLogger(StartupRecovery.class);

    private final PlanStore planStore;
    private final RunStore runStore;
    private final EventStore eventStore;
    private final GovernanceStore governanceStore;

    private final PlanRepository plans;
    private final RunRepository runs;
    private final EventLog events;
    private final ApprovalRepository approvals;
    private final DecisionLog decisions;
    private final RunScheduler scheduler;

    public StartupRecovery(PlanStore planStore, RunStore runStore, EventStore eventStore,
                           GovernanceStore governanceStore, PlanRepository plans,
                           RunRepository runs, EventLog events, ApprovalRepository approvals,
                           DecisionLog decisions, RunScheduler scheduler) {
        this.planStore = planStore;
        this.runStore = runStore;
        this.eventStore = eventStore;
        this.governanceStore = governanceStore;
        this.plans = plans;
        this.runs = runs;
        this.events = events;
        this.approvals = approvals;
        this.decisions = decisions;
        this.scheduler = scheduler;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        List<Plan> storedPlans = planStore.findAll();
        storedPlans.forEach(plans::restore);

        List<Run> restored = restoreRuns();

        events.restore(eventStore.findAll());
        governanceStore.findAllApprovals().forEach(approvals::restore);
        decisions.restore(governanceStore.findAllDecisions());

        List<Run> live = restored.stream()
                .filter(run -> !run.status().isTerminal())
                .toList();

        log.info("Recovered {} plan(s) and {} run(s); {} still live",
                storedPlans.size(), restored.size(), live.size());

        // Re-enter the scheduler only after every log is back in place, so a resumed run appends to
        // a complete history rather than starting a second one alongside it.
        for (Run run : live) {
            log.info("Resuming run {} in state {}", run.id(), run.status());
            scheduler.recover(run);
        }
    }

    private List<Run> restoreRuns() {
        List<Run> restored = new ArrayList<>();

        for (PersistedRun stored : runStore.findAll()) {
            Plan plan = plans.findById(stored.planId()).orElse(null);
            if (plan == null) {
                // Storage says a run exists whose plan does not. Skipping and saying so is better
                // than failing startup for every other run, or inventing a plan to satisfy it.
                log.warn("Skipping run {}: its plan {} is missing from storage",
                        stored.id(), stored.planId());
                continue;
            }

            Run run = new Run(stored.id(), plan, stored.autonomyLevel(), stored.baseRunId());
            run.restore(stored.status(), stored.createdAt(), stored.startedAt(), stored.endedAt(),
                    stored.stopRequested(), stored.stopReason(), stored.paused(),
                    stored.replanCount(), stored.pendingReplanGuidance(),
                    stored.pendingReplanTrigger());

            for (PersistedRun.PersistedNode node : stored.nodes()) {
                if (run.node(node.taskId()) == null) {
                    log.warn("Run {} has a stored node {} that its plan does not contain",
                            stored.id(), node.taskId());
                    continue;
                }
                run.node(node.taskId()).restore(node.state(), node.attempt(), node.startedAt(),
                        node.endedAt(), node.message(), node.outputs(),
                        node.fallbackAttempted(), node.completedDegraded(), node.fingerprint());
            }

            runs.restore(run);
            restored.add(run);
        }
        return restored;
    }

    /** Approvals are restored by id; exposed for the recovery test to assert against. */
    List<ApprovalRequest> restoredApprovals() {
        return approvals.findAll();
    }
}
