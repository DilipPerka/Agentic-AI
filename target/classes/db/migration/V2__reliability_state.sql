-- Increment 4 adds pause/resume, a degraded fallback path and rollback, each of which is state that
-- has to survive a restart like everything else.

ALTER TABLE run ADD COLUMN paused BOOLEAN NOT NULL DEFAULT FALSE;

-- Whether the node has already spent its one degraded attempt. Without this, a restart would hand
-- a node a fresh fallback it had already used, and "one fallback attempt" would stop being bounded.
ALTER TABLE run_node ADD COLUMN fallback_attempted BOOLEAN NOT NULL DEFAULT FALSE;

-- Whether the node only succeeded via that degraded path. Release readiness needs to know; a
-- reviewer looking at a green run should still be told which parts are weaker than intended.
ALTER TABLE run_node ADD COLUMN completed_degraded BOOLEAN NOT NULL DEFAULT FALSE;
