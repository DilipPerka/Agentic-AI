package com.agentic.orchestrator.agent;

import com.agentic.orchestrator.execution.ExecutionContext;
import com.agentic.orchestrator.reliability.CompensationExecutor;
import com.agentic.orchestrator.reliability.CompensationResult;
import com.agentic.orchestrator.sandbox.SandboxViolationException;
import com.agentic.orchestrator.sandbox.Workspace;
import com.agentic.orchestrator.tools.GitTool;
import com.agentic.orchestrator.tools.ToolResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Real compensation: reverts the commit a node made.
 *
 * <p>Precise rather than approximate. Each node commits separately and records its sha, so undoing
 * one node is one revert of one commit — not an attempt to reconstruct which files a node was
 * responsible for after the fact.
 */
@Component
@ConditionalOnProperty(name = "orchestrator.executor", havingValue = "agent")
public class GitCompensationExecutor implements CompensationExecutor {

    private final GitTool git;
    private final AgentNodeExecutor executor;

    public GitCompensationExecutor(GitTool git, AgentNodeExecutor executor) {
        this.git = git;
        this.executor = executor;
    }

    @Override
    public CompensationResult compensate(ExecutionContext context) {
        String sha = context.nodeOutputs().get("git.commit");
        if (sha == null) {
            // A node that wrote nothing has nothing to undo. Distinguishing that from a failure
            // matters: reporting it as failed would halt a rollback that is proceeding correctly.
            return CompensationResult.undone(
                    "No commit recorded for " + context.task().id() + "; nothing to undo");
        }

        Workspace workspace;
        try {
            workspace = executor.workspaceFor(context.runId());
        } catch (SandboxViolationException unavailable) {
            return CompensationResult.failed(
                    "Workspace unavailable: " + unavailable.getMessage());
        }

        if (!git.isClean(workspace)) {
            // Reverting onto uncommitted changes produces a tree that is neither the old state nor
            // the new one. Refusing is the outcome a human can still reason about.
            return CompensationResult.failed(
                    "Working tree has uncommitted changes; refusing to revert onto them");
        }

        ToolResult revert = git.revert(workspace, sha);
        return revert.success()
                ? CompensationResult.undone(revert.summary())
                : CompensationResult.failed(revert.summary());
    }
}
