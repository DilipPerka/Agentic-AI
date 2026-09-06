package com.agentic.orchestrator.tools;

import com.agentic.orchestrator.sandbox.Platform;
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
import java.util.regex.Pattern;
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

    /**
     * Everything an agent is allowed to execute. Nothing else runs, whatever asks for it.
     *
     * <p>Compared against the executable's <em>base name</em> with any {@code .cmd}, {@code .bat} or
     * {@code .exe} suffix removed, because the same tool is spelled differently per platform:
     * {@code mvn} is {@code mvn.cmd} on Windows, and the Maven wrapper is {@code mvnw} or
     * {@code mvnw.cmd}. Matching the raw string instead would mean the allowlist silently rejected
     * every build on Windows.
     */
    private static final Set<String> ALLOWED = Set.of("mvn", "mvnw", "git", "java");

    /**
     * The only tool that may be named by a path rather than found on {@code PATH}.
     *
     * <p>A path lets a caller pick a specific file, and the workspace is agent-writable — so
     * permitting any allowlisted base name to be reached by path would let an agent write its own
     * {@code git} and have the orchestrator run it. The Maven wrapper is the one case that needs it,
     * and it is a file the build itself ships.
     */
    private static final String PATH_ADDRESSABLE = "mvnw";

    private static final List<String> EXECUTABLE_SUFFIXES = List.of(".cmd", ".bat", ".exe");

    /** Build goals and flags, and nothing that could mean something to an interpreter. */
    private static final Pattern SHELL_SAFE_ARGUMENT = Pattern.compile("[A-Za-z0-9._:=/-]+");

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
        String tool = allowlistKey(executable);
        if (!ALLOWED.contains(tool)) {
            throw new SandboxViolationException(
                    "Executable is not on the allowlist: " + executable);
        }
        if (namesAPath(executable) && !PATH_ADDRESSABLE.equals(tool)) {
            throw new SandboxViolationException(
                    "Only " + PATH_ADDRESSABLE + " may be named by a path; "
                            + tool + " must be found on PATH: " + executable);
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
            ProcessBuilder builder = new ProcessBuilder(launchCommand(command))
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

    /**
     * The command as the OS must actually receive it.
     *
     * <p>Everywhere except one case this is the command unchanged, handed to {@code execve} or
     * {@code CreateProcess} with no interpreter. The exception is Windows batch wrappers: Maven ships
     * as {@code mvn.cmd} and its wrapper as {@code mvnw.cmd}, and a {@code .cmd} file is not an
     * executable image — {@code CreateProcess} rejects it with the well-known
     * {@code error=193, %1 is not a valid Win32 application}. Only {@code cmd.exe} can run it.
     *
     * <p>That is a real narrowing of the no-shell guarantee, so it is narrowed back by
     * {@link #verifyArgumentsAreShellSafe}: nothing reaches {@code cmd.exe} that is not a plain
     * build goal. Agent-controlled text — a commit message, a file name — travels via {@code git},
     * which is a genuine executable and is still launched with no interpreter anywhere near it.
     */
    private static List<String> launchCommand(List<String> command) {
        if (!Platform.isWindows() || !isBatchFile(command.get(0))) {
            return command;
        }
        verifyArgumentsAreShellSafe(command);
        List<String> wrapped = new ArrayList<>(List.of("cmd.exe", "/c"));
        wrapped.addAll(command);
        return wrapped;
    }

    private static boolean isBatchFile(String executable) {
        String lower = executable.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".cmd") || lower.endsWith(".bat");
    }

    /**
     * Refuses anything but a plain build goal on the one path that goes through an interpreter.
     *
     * <p>Documented assumptions rot; this one is checked. If a future caller passes agent-derived
     * text to a batch wrapper, it fails here rather than becoming a command substitution.
     */
    private static void verifyArgumentsAreShellSafe(List<String> command) {
        for (String argument : command.subList(1, command.size())) {
            if (!SHELL_SAFE_ARGUMENT.matcher(argument).matches()) {
                throw new SandboxViolationException(
                        "Argument is not safe to pass to a batch wrapper: " + argument);
            }
        }
    }

    /**
     * The allowlist name for an executable: base name, lower-cased, without a Windows suffix.
     *
     * <p>So {@code mvn}, {@code mvn.cmd}, {@code ./mvnw} and {@code C:\ws\run1\mvnw.cmd} all reduce
     * to a name the allowlist can be written in once.
     */
    private static String allowlistKey(String executable) {
        String name = executable.replace('\\', '/');
        int lastSeparator = name.lastIndexOf('/');
        if (lastSeparator >= 0) {
            name = name.substring(lastSeparator + 1);
        }
        name = name.toLowerCase(java.util.Locale.ROOT);
        for (String suffix : EXECUTABLE_SUFFIXES) {
            if (name.endsWith(suffix)) {
                return name.substring(0, name.length() - suffix.length());
            }
        }
        return name;
    }

    private static boolean namesAPath(String executable) {
        return executable.indexOf('/') >= 0 || executable.indexOf('\\') >= 0;
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
