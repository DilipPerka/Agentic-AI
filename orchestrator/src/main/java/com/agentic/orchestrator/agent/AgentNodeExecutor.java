package com.agentic.orchestrator.agent;

import com.agentic.orchestrator.execution.ExecutionContext;
import com.agentic.orchestrator.execution.NodeExecutor;
import com.agentic.orchestrator.execution.NodeResult;
import com.agentic.orchestrator.plan.Stage;
import com.agentic.orchestrator.sandbox.SandboxViolationException;
import com.agentic.orchestrator.sandbox.Workspace;
import com.agentic.orchestrator.tools.FileSystemTool;
import com.agentic.orchestrator.tools.GitTool;
import com.agentic.orchestrator.tools.MavenTool;
import com.agentic.orchestrator.tools.ToolResult;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The real executor: writes files, builds, tests and commits.
 *
 * <h2>Evidence, not claims</h2>
 * The agent says what to write; {@code mvn} says whether it compiles and whether the tests pass. The
 * compiler's exit code and Surefire's counts go into the node's outputs, and the exit gate reads
 * them. An agent cannot talk its way past a failing build.
 *
 * <h2>Why execution serialises per run</h2>
 * All nodes in a run share one working tree, and two concurrent Maven builds in one directory
 * corrupt each other's {@code target/}, as do two concurrent {@code git} commands contending for
 * {@code index.lock}. So tool-using work is serialised per workspace.
 *
 * <p>This is a real limitation and worth naming precisely: the <em>scheduler</em> is still parallel —
 * it computes ready sets, honours joins and runs governance concurrently — but the <em>tools</em>
 * queue. The correct fix is a git worktree per parallel branch, merged at the join. That is a
 * meaningful piece of work and is not in this increment.
 */
@Component
@ConditionalOnProperty(name = "orchestrator.executor", havingValue = "agent")
public class AgentNodeExecutor implements NodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentNodeExecutor.class);

    /** Extensions whose appearance means the project must still compile afterwards. */
    private static final Set<String> COMPILABLE = Set.of(".java", ".xml", ".sql", ".properties");

    private final AgentRuntime runtime;
    private final FileSystemTool files;
    private final MavenTool maven;
    private final GitTool git;
    private final Path workspaceRoot;

    private final Map<String, Workspace> workspaces = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public AgentNodeExecutor(AgentRuntime runtime, FileSystemTool files, MavenTool maven,
                             GitTool git,
                             @Value("${orchestrator.workspace.root:./workspace}") String root) {
        this.runtime = runtime;
        this.files = files;
        this.maven = maven;
        this.git = git;
        this.workspaceRoot = Paths.get(root).toAbsolutePath().normalize();
    }

    public Workspace workspaceFor(String runId) {
        return workspaceFor(runId, null);
    }

    /**
     * Resolves the run's workspace, seeding it from {@code baseRunId} the first time if given.
     *
     * <p>Seeding copies the baseline's working tree but <em>not</em> its {@code .git}. The new run
     * gets a fresh history whose first commit is the baseline — so its own commits are exactly the
     * changes it made, and a rollback of one of its nodes can never revert into the baseline's
     * history. Sharing the history would make "undo this run" ambiguous.
     *
     * <p>A missing or empty baseline is a warning, not a failure: the run proceeds greenfield-style
     * and its first compile will say what is missing far more clearly than a workspace error would.
     */
    public Workspace workspaceFor(String runId, String baseRunId) {
        return workspaces.computeIfAbsent(runId, id -> {
            Workspace workspace = Workspace.at(workspaceRoot.resolve(id));
            if (baseRunId != null && !baseRunId.isBlank()) {
                seed(workspace, baseRunId);
            }
            return workspace;
        });
    }

    private void seed(Workspace workspace, String baseRunId) {
        Path base = workspaceRoot.resolve(baseRunId);
        if (!Files.isDirectory(base)) {
            log.warn("Baseline run {} has no workspace at {}; starting empty", baseRunId, base);
            return;
        }
        try (Stream<Path> tree = Files.walk(base)) {
            List<Path> sources = tree.filter(Files::isRegularFile)
                    .filter(path -> {
                        String relative = base.relativize(path).toString();
                        // .git is excluded deliberately (see above); target/ is build output that
                        // would be stale the moment anything is edited.
                        return !relative.startsWith(".git" + File.separator)
                                && !relative.startsWith("target" + File.separator);
                    })
                    .toList();

            int copied = 0;
            for (Path source : sources) {
                Path destination = workspace.root().resolve(base.relativize(source));
                Files.createDirectories(destination.getParent());
                Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                copied++;
            }
            log.info("Seeded workspace from baseline {}: {} file(s)", baseRunId, copied);
        } catch (IOException failed) {
            // Not fatal. A partially seeded workspace fails its first compile with a message about
            // the actual missing type, which is more useful than an IO error here.
            log.warn("Could not fully seed from baseline {}: {}", baseRunId, failed.getMessage());
        }
    }

    @Override
    public NodeResult execute(ExecutionContext context) {
        ReentrantLock lock = locks.computeIfAbsent(context.runId(), id -> new ReentrantLock());
        lock.lock();
        try {
            return executeExclusively(context);
        } finally {
            lock.unlock();
        }
    }

    private NodeResult executeExclusively(ExecutionContext context) {
        String taskId = context.task().id();
        Workspace workspace;
        try {
            workspace = workspaceFor(context.runId(), context.baseRunId());
            git.ensureRepository(workspace);
        } catch (SandboxViolationException refused) {
            return NodeResult.permanentFailure("Workspace unavailable: " + refused.getMessage());
        }

        AgentOutput output = runtime.produce(
                context.task(), context.degraded(), context.upstreamOutputs());

        if (!output.supported()) {
            // Retryable so the escalation ladder can reach the degraded fallback, which does have
            // something to offer. Retrying costs nothing here — no work is attempted.
            return NodeResult.failure(output.summary());
        }

        Map<String, String> evidence = new LinkedHashMap<>();
        Set<String> written = new LinkedHashSet<>();

        for (Map.Entry<String, String> file : output.files().entrySet()) {
            try {
                ToolResult write = files.write(workspace, file.getKey(), file.getValue());
                if (!write.success()) {
                    return NodeResult.failure(write.summary());
                }
                written.add(file.getKey());
            } catch (SandboxViolationException refused) {
                // A blueprint trying to escape the workspace, or write a credential. Permanent:
                // repeating it will produce the same refusal, and it needs a person to look.
                log.error("Sandbox refused a write for {}: {}", taskId, refused.getMessage());
                return NodeResult.permanentFailure(refused.getMessage());
            }
        }

        evidence.put("agent.runtime", runtime.name());
        evidence.put("files.written", String.valueOf(written.size()));

        NodeResult buildFailure = verifyBuild(workspace, context, written, evidence);
        if (buildFailure != null) {
            return buildFailure;
        }

        commit(workspace, context, written, evidence);

        // The declared outputs the gate will check. Anything the blueprint did not account for is
        // simply absent, and the gate fails the node for it.
        output.evidence().forEach(evidence::put);

        return NodeResult.success(
                runtime.name() + " produced " + written.size() + " file(s) for " + taskId
                        + (context.degraded() ? " (degraded)" : ""),
                evidence);
    }

    /**
     * Compiles, and runs tests for testing-stage nodes.
     *
     * @return a failing result, or null if the build evidence is acceptable
     */
    private NodeResult verifyBuild(Workspace workspace, ExecutionContext context,
                                   Set<String> written, Map<String, String> evidence) {
        boolean touchesBuild = written.stream()
                .anyMatch(path -> COMPILABLE.stream().anyMatch(path::endsWith));
        if (!touchesBuild) {
            return null; // A documentation-only node has nothing to compile.
        }

        ToolResult compile = maven.compile(workspace);
        evidence.put("build.exitCode", String.valueOf(compile.exitCode()));
        evidence.put("build.durationMs", String.valueOf(compile.durationMs()));
        if (!compile.success()) {
            return NodeResult.failure("Compilation failed: " + compile.tail(6));
        }

        Stage stage = context.task().stage();
        if (stage != Stage.TESTING) {
            return null;
        }

        MavenTool.TestOutcome tests = maven.test(workspace);
        evidence.put("tests.run", String.valueOf(tests.testsRun()));
        evidence.put("tests.failures", String.valueOf(tests.failures()));
        evidence.put("tests.errors", String.valueOf(tests.errors()));
        evidence.put("tests.durationMs", String.valueOf(tests.durationMs()));
        if (!tests.success()) {
            return NodeResult.failure("Tests failed: " + tests.summary());
        }
        return null;
    }

    private void commit(Workspace workspace, ExecutionContext context, Set<String> written,
                        Map<String, String> evidence) {
        if (written.isEmpty()) {
            return;
        }
        GitTool.CommitResult commit = git.commitAll(workspace,
                context.task().id() + ": " + context.task().title()
                        + (context.degraded() ? " [degraded]" : ""));

        if (commit.sha() != null) {
            // Recorded on the node so rollback can revert precisely this change rather than
            // guessing at what a node was responsible for.
            evidence.put("git.commit", commit.sha());
        }
        evidence.put("git.summary", commit.summary());
    }
}
