package com.agentic.orchestrator.reliability;

import com.agentic.orchestrator.execution.ExecutionContext;

/**
 * Undoes the side effects of a node that succeeded.
 *
 * <p>The counterpart to {@code NodeExecutor}, and a separate seam on purpose: undoing work is not
 * the same operation as doing it in reverse. With real tools this is {@code git revert} of the
 * node's commit, deletion of the files it created, or a down-migration — none of which the forward
 * executor knows how to do.
 */
public interface CompensationExecutor {

    CompensationResult compensate(ExecutionContext context);
}
