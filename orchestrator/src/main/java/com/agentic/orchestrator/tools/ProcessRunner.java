package com.agentic.orchestrator.tools;

import com.agentic.orchestrator.sandbox.SandboxViolationException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs external commands on behalf of agents, under four constraints that each close a specific
 * failure mode.
 *
 * <ol>
 *   <li><b>No shell.</b> Arguments are passed as a list straight to {@code execve}, so there is no
 *       interpreter to inject into. A filename containing {@code ; rm -rf /} is a filename.
 *   <li><b>Allowlist.</b> Only named executables run. Confining an agent's <em>files</em> while
 *       letting it run arbitrary binaries would confine nothing.
 *   <li><b>Streams drained concurrently.</b> A process whose output fills the OS pipe buffer blocks
 *       forever if nobody is reading, and {@code waitFor} then blocks with it. Reading one stream
 *       after the other deadlocks on whichever fills first — a classic and very confusing hang.
 *   <li><b>Bounded capture and a deadline.</b> Output is truncated rather than held in full, and a
 *       process past its deadline is destroyed forcibly. Compilers can emit megabytes; a wedged
 *       build must not become a wedged orchestrator.
 * </ol>
 */
@Component
public class ProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(ProcessRunner.class);

    /** Everything an agent is allowed to execute. Nothing else runs, whatever asks for it. */
    private static final Set<String> ALLOWED = Set.of("mvn", "mvnw", "./mvnw", "git", "java");

    private static final int MAX_CAPTURED_BYTES = 256 * 1024;

    private final ExecutorService streamReaders = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "process-stream-reader");
        thread.setDaemon(true);
        return thread;
    });

    public ToolResult run(Path workingDirectory, Duration timeout, List<String> command) {
        if (command == null || command.isEmpty()) {
            throw new SandboxViolationException("Empty command");
        }
        String executable = command.get(0);
        if (!ALLOWED.contains(executable)) {
            throw new SandboxViolationException(
                    "Executable is not on the allowlist: " + executable);
        }
        for (String argument : command) {
            if (argument == null || argument.indexOf('\0') >= 0) {
                throw new SandboxViolationException("Argument contains a NUL byte");
            }
        }
        if (!Files.isDirectory(workingDirectory)) {
            return ToolResult.failed("Working directory does not exist: " + workingDirectory);
        }

        long started = System.nanoTime();
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(workingDirectory.toFile());
            // Inherit the environment (Maven needs JAVA_HOME and PATH) but never hand a child a
            // credential the orchestrator happens to be holding.
            builder.environment().keySet().removeIf(ProcessRunner::looksLikeASecret);

            process = builder.start();

            // Both streams are drained on their own threads, started before waitFor. Either stream
            // filling its pipe buffer would otherwise block the child, and waitFor would wait on a
            // process that is itself waiting on us.
            Process running = process;
            Future<String> out = streamReaders.submit(() -> drain(running.getInputStream()));
            Future<String> err = streamReaders.submit(() -> drain(running.getErrorStream()));

            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                return new ToolResult(false, -1, safeGet(out), safeGet(err),
                        elapsedMs(started), "Timed out after " + timeout.toMillis() + "ms");
            }

            int exitCode = process.exitValue();
            return new ToolResult(exitCode == 0, exitCode, safeGet(out), safeGet(err),
                    elapsedMs(started),
                    String.join(" ", command) + " exited " + exitCode);

        } catch (IOException failure) {
            // A missing executable arrives here, not as a non-zero exit. Reporting it as an ordinary
            // tool failure keeps "maven is not installed" diagnosable instead of a stack trace.
            return new ToolResult(false, -1, "", failure.getMessage(), elapsedMs(started),
                    "Could not start " + executable + ": " + failure.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return new ToolResult(false, -1, "", "", elapsedMs(started), "Interrupted");
        }
    }

    /** Reads a stream to exhaustion, keeping at most {@link #MAX_CAPTURED_BYTES}. */
    private static String drain(InputStream stream) {
        if (stream == null) {
            return "";
        }
        StringBuilder captured = new StringBuilder();
        byte[] buffer = new byte[8192];
        long total = 0;
        try (InputStream input = stream) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                // Keep consuming after the cap so the pipe never fills; just stop retaining.
                if (captured.length() < MAX_CAPTURED_BYTES) {
                    captured.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                }
            }
        } catch (IOException ignored) {
            captured.append("\n[output truncated: ").append(ignored.getMessage()).append(']');
        }
        if (total > MAX_CAPTURED_BYTES) {
            captured.append("\n[").append(total - MAX_CAPTURED_BYTES)
                    .append(" further bytes not retained]");
        }
        return captured.toString();
    }

    private static String safeGet(Future<String> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception failure) {
            return "";
        }
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static boolean looksLikeASecret(String environmentVariable) {
        String name = environmentVariable.toUpperCase(java.util.Locale.ROOT);
        return name.contains("TOKEN") || name.contains("SECRET") || name.contains("PASSWORD")
                || name.contains("API_KEY") || name.contains("APIKEY")
                || name.endsWith("_KEY") || name.contains("CREDENTIAL");
    }

    List<String> allowlist() {
        return new ArrayList<>(ALLOWED);
    }
}
