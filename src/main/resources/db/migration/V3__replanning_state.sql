-- Re-planning state.
--
-- replan_count is persisted because it is the hard bound on re-planning; if it reset on restart, a
-- run could re-plan indefinitely by being restarted, which is exactly the failure the bound exists
-- to prevent.
ALTER TABLE run ADD COLUMN replan_count INT NOT NULL DEFAULT 0;

-- A re-plan held pending approval of the plan change itself. The candidate plan is not stored: the
-- planner is deterministic, so the guidance plus the requirement is enough to reproduce it, and
-- storing a whole speculative plan would mean two sources of truth for what is about to happen.
ALTER TABLE run ADD COLUMN pending_replan_guidance VARCHAR(4000);
ALTER TABLE run ADD COLUMN pending_replan_trigger VARCHAR(48);

-- Content fingerprint at the moment the node succeeded. Without it, a restart would leave every
-- completed node with no basis for comparison and re-planning would have to redo all of them.
ALTER TABLE run_node ADD COLUMN fingerprint VARCHAR(64);

-- Plans are versioned now; a run's plan_id changes when a revision is adopted.
ALTER TABLE plan ADD COLUMN version INT NOT NULL DEFAULT 1;
ALTER TABLE plan ADD COLUMN supersedes VARCHAR(64);
