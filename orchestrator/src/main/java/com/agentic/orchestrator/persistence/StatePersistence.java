package com.agentic.orchestrator.persistence;

import com.agentic.orchestrator.execution.Run;
import com.agentic.orchestrator.execution.RunEvent;
import com.agentic.orchestrator.governance.ApprovalRequest;
import com.agentic.orchestrator.governance.Decision;
import com.agentic.orchestrator.plan.Plan;

/**
 * Write side of durable state.
 *
 * <p>An interface rather than injecting the DAOs directly, so that persistence is something the
 * domain has rather than something it is. {@link #NONE} lets the scheduler and its collaborators be
 * unit-tested without a database — those tests are about scheduling and governance, and a database
 * in them would only add setup cost and a new way to fail.
 */
public interface StatePersistence {

    void savePlan(Plan plan);

    void saveRun(Run run);

    /**
     * Drops stored nodes the run no longer has. Only meaningful after a re-plan; an upsert cannot
     * delete, so without this a superseded plan's tasks would linger and be resurrected on restart.
     */
    void pruneRunNodes(Run run);

    void appendEvent(RunEvent event);

    void saveApproval(ApprovalRequest approval);

    void saveDecision(Decision decision);

    /** No-op implementation for tests and any context deliberately running without storage. */
    StatePersistence NONE = new StatePersistence() {

        @Override
        public void savePlan(Plan plan) {
        }

        @Override
        public void saveRun(Run run) {
        }

        @Override
        public void pruneRunNodes(Run run) {
        }

        @Override
        public void appendEvent(RunEvent event) {
        }

        @Override
        public void saveApproval(ApprovalRequest approval) {
        }

        @Override
        public void saveDecision(Decision decision) {
        }
    };
}
