-- A brownfield run modifies an existing codebase. base_run_id names the run whose workspace this
-- one starts from, which is what makes "add analytics to the existing service" mean something
-- concrete rather than writing analytics code into an empty directory.
--
-- Deliberately not a foreign key to run(id): the baseline may be pruned, archived, or produced
-- outside this orchestrator entirely, and a dangling reference should degrade to "no baseline
-- found" rather than block the insert of a perfectly valid run.
ALTER TABLE run ADD COLUMN base_run_id VARCHAR(64);
