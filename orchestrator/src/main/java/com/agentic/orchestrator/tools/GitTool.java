package com.agentic.orchestrator.tools;

import com.agentic.orchestrator.sandbox.Workspace;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Version control for the generated workspace. This is what makes rollback real rather than
 * declarative: a node's changes are one commit, and undoing them is one revert.
 */
@Component
public class GitTool {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final ProcessRunner processes;

    public GitTool(ProcessRunner processes) {
        this.processes = processes;
    }

    /**
     * Initialises the repository if absent, and always sets local identity and branch name.
     *
     * <p>The identity is not optional housekeeping. On a machine with no global git config —
     * a CI container, a fresh checkout — {@code git commit} fails with "Please tell me who you are",
     * and the first thing anyone would see is a node failing for reasons that have nothing to do
     * with the code it wrote.
     */
    public ToolResult ensureRepository(Workspace workspace) {
        if (!Files.isDirectory(workspace.root().resolve(".git"))) {
            ToolResult init = run(workspace, "init", "--initial-branch=main");
            if (!init.success()) {
                // Older git does not know --initial-branch; the default branch is fine.
                init = run(workspace, "init");
                if (!init.success()) {
                    return init;
                }
            }
        }
        run(workspace, "config", "user.email", "orchestrator@agentic.local");
        run(workspace, "config", "user.name", "Agentic SDLC Orchestrator");
        return ToolResult.ok("Repository ready");
    }

    /**
     * Stages everything and commits.
     *
     * <p>"Nothing to commit" is reported as a distinct, successful outcome rather than a failure. A
     * node that legitimately produced no file change — a review, a readiness assessment — would
     * otherwise fail for having done exactly what it was asked to do.
     */
    public CommitResult commitAll(Workspace workspace, String message) {
        ToolResult staged = run(workspace, "add", "-A");
        if (!staged.success()) {
            return new CommitResult(false, null, "Could not stage changes: " + staged.tail(3));
        }

        ToolResult pending = run(workspace, "status", "--porcelain");
        if (pending.success() && pending.stdout().isBlank()) {
            return new CommitResult(true, null, "No file changes to commit");
        }

        ToolResult committed = run(workspace, "commit", "-m", message);
        if (!committed.success()) {
            return new CommitResult(false, null, "Commit failed: " + committed.tail(3));
        }
        return new CommitResult(true, headSha(workspace).orElse(null), "Committed");
    }

    public Optional<String> headSha(Workspace workspace) {
        ToolResult result = run(workspace, "rev-parse", "HEAD");
        if (!result.success() || result.stdout().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(result.stdout().strip());
    }

    /**
     * Reverts a specific commit.
     *
     * <p>{@code revert}, not {@code reset}: reverting adds a new commit that undoes the old one, so
     * the fact that something was done and then undone stays in history. A reset would erase the
     * evidence, which is the opposite of what an audit trail is for.
     *
     * <p>A revert that conflicts is aborted rather than left half-applied — a conflicted working
     * tree is precisely the partial state that must reach a human intact.
     */
    public ToolResult revert(Workspace workspace, String sha) {
        if (sha == null || sha.isBlank()) {
            return ToolResult.failed("No commit recorded for this node; nothing to revert");
        }
        ToolResult result = run(workspace, "revert", "--no-edit", sha);
        if (!result.success()) {
            run(workspace, "revert", "--abort");
            return new ToolResult(false, result.exitCode(), result.stdout(), result.stderr(),
                    result.durationMs(),
                    "Revert of " + shortSha(sha) + " failed and was aborted: " + result.tail(3));
        }
        return ToolResult.ok("Reverted " + shortSha(sha));
    }

    public boolean isClean(Workspace workspace) {
        ToolResult status = run(workspace, "status", "--porcelain");
        return status.success() && status.stdout().isBlank();
    }

    private ToolResult run(Workspace workspace, String... arguments) {
        List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        return processes.run(workspace.root(), TIMEOUT, command);
    }

    private static String shortSha(String sha) {
        return sha.length() > 8 ? sha.substring(0, 8) : sha;
    }

    public record CommitResult(boolean success, String sha, String summary) {
    }
}
