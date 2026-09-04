package com.agentic.orchestrator.persistence;

import com.agentic.orchestrator.execution.Run;
import com.agentic.orchestrator.execution.RunEvent;
import com.agentic.orchestrator.governance.ApprovalRequest;
import com.agentic.orchestrator.governance.Decision;
import com.agentic.orchestrator.plan.Plan;
import org.springframework.stereotype.Component;

@Component
public class JdbcStatePersistence implements StatePersistence {

    private final PlanStore plans;
    private final RunStore runs;
    private final EventStore events;
    private final GovernanceStore governance;

    public JdbcStatePersistence(PlanStore plans, RunStore runs, EventStore events,
                                GovernanceStore governance) {
        this.plans = plans;
        this.runs = runs;
        this.events = events;
        this.governance = governance;
    }

    @Override
    public void savePlan(Plan plan) {
        plans.save(plan);
    }

    @Override
    public void saveRun(Run run) {
        runs.save(run);
    }

    @Override
    public void pruneRunNodes(Run run) {
        runs.pruneNodes(run.id(),
                run.nodes().stream().map(node -> node.taskId()).toList());
    }

    @Override
    public void appendEvent(RunEvent event) {
        events.append(event);
    }

    @Override
    public void saveApproval(ApprovalRequest approval) {
        governance.save(approval);
    }

    @Override
    public void saveDecision(Decision decision) {
        governance.save(decision);
    }
}
