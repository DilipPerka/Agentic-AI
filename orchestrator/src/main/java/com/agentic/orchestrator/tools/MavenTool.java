package com.agentic.orchestrator.tools;

import com.agentic.orchestrator.sandbox.Platform;
import com.agentic.orchestrator.sandbox.Workspace;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Compiles and tests the generated project.
 *
 * <p>This is the tool that turns an exit gate from a claim into evidence. The agent says it wrote
 * working code; {@code mvn} says whether it compiles and whether the tests pass, and only the
 * second one counts.
 */
@Component
public class MavenTool {

    private static final Logger log = LoggerFactory.getLogger(MavenTool.class);

    /** Surefire's summary line, which is more reliable to read than its XML across versions. */
    private static final Pattern SUMMARY = Pattern.compile(
            "Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+),\\s*Skipped:\\s*(\\d+)");

    private final ProcessRunner processes;
    private final Duration timeout;

    public MavenTool(ProcessRunner processes,
                     @Value("${orchestrator.tools.maven-timeout-ms:600000}") long timeoutMs) {
        this.processes = processes;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    public ToolResult compile(Workspace workspace) {
        return run(workspace, "-q", "-o", "compile");
    }

    public TestOutcome test(Workspace workspace) {
        ToolResult result = run(workspace, "-o", "test");
        return TestOutcome.from(result, parseTotals(workspace, result));
    }

    private ToolResult run(Workspace workspace, String... goals) {
        if (!Files.isRegularFile(workspace.root().resolve("pom.xml"))) {
            // Distinct from a compile failure: there is nothing to build yet, which is a different
            // problem from code that does not compile and deserves a different message.
            return ToolResult.failed("No pom.xml in the workspace; nothing to build");
        }

        List<String> command = new ArrayList<>();
        command.add(mavenExecutable(workspace));
        command.addAll(List.of(goals));

        ToolResult result = processes.run(workspace.root(), timeout, command);

        // -o (offline) fails if a dependency is genuinely missing from the local repository. Retry
        // online once rather than reporting a build failure that is really a cache miss.
        if (!result.success() && looksLikeAMissingDependency(result)) {
            log.info("Offline build could not resolve dependencies; retrying online");
            List<String> online = new ArrayList<>(command);
            online.remove("-o");
            result = processes.run(workspace.root(), timeout, online);
        }
        return result;
    }

    /**
     * Prefers a wrapper in the workspace so the build does not depend on the host's Maven.
     *
     * <p>Two things differ per platform and both of them are load-bearing. The wrapper is
     * {@code mvnw.cmd} on Windows and {@code mvnw} elsewhere, and Maven itself is {@code mvn.cmd}
     * rather than {@code mvn} — Java's process launcher appends only {@code .exe} when searching
     * {@code PATH}, so a bare {@code mvn} is simply not found and every build fails with a
     * "cannot run program" that looks nothing like its cause.
     *
     * <p>The wrapper is named by its absolute path rather than {@code ./mvnw}. A relative command is
     * resolved against the child's working directory on Unix but against the <em>parent's</em> on
     * Windows, so the relative form quietly looks in the wrong place.
     */
    private String mavenExecutable(Workspace workspace) {

        if (Platform.isWindows()) {

            // 1. Prefer wrapper inside generated workspace
            Path wrapper = workspace.root().resolve("mvnw.cmd");

            if (Files.isRegularFile(wrapper)) {
                return wrapper.toString();
            }

            // 2. Use MAVEN_HOME directly
            String mavenHome = System.getenv("MAVEN_HOME");

            if (mavenHome != null && !mavenHome.isBlank()) {

                Path mvn = Paths.get(
                        mavenHome,
                        "bin",
                        "mvn.cmd"
                );

                if (Files.isRegularFile(mvn)) {
                    return mvn.toString();
                }
            }

            // 3. Last fallback
            return "mvn.cmd";
        }

        Path wrapper = workspace.root().resolve("mvnw");

        if (Files.isExecutable(wrapper)) {
            return wrapper.toString();
        }

        return "mvn";
    }

    private static boolean looksLikeAMissingDependency(ToolResult result) {
        String output = result.stdout() + result.stderr();
        return output.contains("Cannot access central")
                || output.contains("has not been downloaded")
                || output.contains("was cached in the local repository")
                || output.contains("The repository system is offline");
    }

    /**
     * Reads the totals from Surefire's own output, falling back to the console summary.
     *
     * <p>Both are checked because either can be absent: a compilation failure produces no reports at
     * all, and {@code -q} suppresses the console summary. Concluding "0 failures" from missing
     * evidence would be exactly the wrong default, so absence yields {@code null} and the gate
     * treats it as unproven rather than passed.
     */
    private Totals parseTotals(Workspace workspace, ToolResult result) {
        Totals fromReports = parseSurefireReports(workspace);
        if (fromReports != null) {
            return fromReports;
        }
        Matcher matcher = SUMMARY.matcher(result.stdout() + "\n" + result.stderr());
        Totals last = null;
        while (matcher.find()) {
            last = new Totals(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)),
                    Integer.parseInt(matcher.group(4)));
        }
        return last;
    }

    private Totals parseSurefireReports(Workspace workspace) {
        Path reports = workspace.root().resolve("target/surefire-reports");
        if (!Files.isDirectory(reports)) {
            return null;
        }
        int run = 0;
        int failures = 0;
        int errors = 0;
        int skipped = 0;
        boolean found = false;

        try (Stream<Path> files = Files.list(reports)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".xml"))
                    .toList()) {
                String xml = Files.readString(file, StandardCharsets.UTF_8);
                run += attribute(xml, "tests");
                failures += attribute(xml, "failures");
                errors += attribute(xml, "errors");
                skipped += attribute(xml, "skipped");
                found = true;
            }
        } catch (IOException failure) {
            return null;
        }
        return found ? new Totals(run, failures, errors, skipped) : null;
    }

    private static int attribute(String xml, String name) {
        Matcher matcher = Pattern.compile(name + "=\"(\\d+)\"").matcher(xml);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private record Totals(int run, int failures, int errors, int skipped) {
    }

    /**
     * @param testsRun null when no totals could be read at all — meaning the outcome is unproven,
     *                 which is not the same as proven good
     */
    public record TestOutcome(boolean success, Integer testsRun, Integer failures, Integer errors,
                              Integer skipped, String summary, long durationMs) {

        static TestOutcome from(ToolResult result, Object totals) {
            if (totals == null) {
                return new TestOutcome(false, null, null, null, null,
                        "No test totals could be read. " + result.tail(4), result.durationMs());
            }
            Totals counts = (Totals) totals;
            boolean clean = result.success() && counts.failures() == 0 && counts.errors() == 0;
            return new TestOutcome(clean, counts.run(), counts.failures(), counts.errors(),
                    counts.skipped(),
                    "Tests run: " + counts.run() + ", failures: " + counts.failures()
                            + ", errors: " + counts.errors() + ", skipped: " + counts.skipped()
                            + (clean ? "" : ". " + result.tail(6)),
                    result.durationMs());
        }
    }
}
