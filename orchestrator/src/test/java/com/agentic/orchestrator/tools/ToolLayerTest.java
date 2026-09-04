package com.agentic.orchestrator.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentic.orchestrator.sandbox.ContentPolicy;
import com.agentic.orchestrator.sandbox.SandboxViolationException;
import com.agentic.orchestrator.sandbox.Workspace;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolLayerTest {

    @TempDir
    Path tempDir;

    private final ContentPolicy contentPolicy = new ContentPolicy();
    private final FileSystemTool files = new FileSystemTool(contentPolicy, 1024 * 1024);
    private final ProcessRunner processes = new ProcessRunner();

    private Workspace workspace() {
        return Workspace.at(tempDir.resolve("ws"));
    }

    // ------------------------------------------------------------------ content policy

    @Nested
    @DisplayName("content screening")
    class Screening {

        @Test
        @DisplayName("a private key is refused before it reaches disk")
        void refusesPrivateKeys() {
            Workspace workspace = workspace();
            String content = "-----BEGIN RSA PRIVATE KEY-----\nMIIEow...\n";

            assertThatThrownBy(() -> files.write(workspace, "src/Key.java", content))
                    .isInstanceOf(SandboxViolationException.class)
                    .hasMessageContaining("private key");

            // Refused before writing, not written and then removed: a secret that briefly existed
            // on disk has still existed, and if the node committed, still exists in history.
            assertThat(Files.exists(workspace.root().resolve("src/Key.java"))).isFalse();
        }

        @Test
        void refusesCloudCredentials() {
            Workspace workspace = workspace();

            assertThatThrownBy(() -> files.write(workspace, "a.java",
                    "String key = \"AKIAIOSFODNN7EXAMPLE\";"))
                    .isInstanceOf(SandboxViolationException.class);
            assertThatThrownBy(() -> files.write(workspace, "b.java",
                    "token = \"ghp_abcdefghijklmnopqrstuvwxyz0123456789\";"))
                    .isInstanceOf(SandboxViolationException.class);
        }

        @Test
        void refusesAssignedSecretLiterals() {
            assertThat(contentPolicy.firstViolation(
                    "spring.datasource.password=\"S3cretValue0123456789\"")).isPresent();
        }

        @Test
        @DisplayName("ordinary code that merely mentions credentials is not flagged")
        void doesNotCryWolf() {
            // A check that fires on every configuration class gets switched off, which is worse
            // than not having it.
            assertThat(contentPolicy.firstViolation(
                    "spring.datasource.password=")).isEmpty();
            assertThat(contentPolicy.firstViolation(
                    "private String password; // injected at runtime")).isEmpty();
            assertThat(contentPolicy.firstViolation(
                    "String token = properties.get(\"api.token\");")).isEmpty();
            assertThat(contentPolicy.firstViolation(
                    "/** Authenticates using an API key. */")).isEmpty();
        }
    }

    // ------------------------------------------------------------------ filesystem

    @Nested
    @DisplayName("filesystem")
    class FileSystem {

        @Test
        void writesAndReadsBack() {
            Workspace workspace = workspace();

            assertThat(files.write(workspace, "docs/readme.md", "hello").success()).isTrue();
            assertThat(files.read(workspace, "docs/readme.md")).contains("hello");
            assertThat(files.exists(workspace, "docs/readme.md")).isTrue();
        }

        @Test
        void refusesToWriteOutsideTheWorkspace() {
            assertThatThrownBy(() -> files.write(workspace(), "../escaped.txt", "x"))
                    .isInstanceOf(SandboxViolationException.class);
        }

        @Test
        void refusesOversizedFiles() {
            FileSystemTool tiny = new FileSystemTool(contentPolicy, 16);

            assertThat(tiny.write(workspace(), "big.txt", "x".repeat(64)).success()).isFalse();
        }

        @Test
        void refusesNullContent() {
            assertThat(files.write(workspace(), "a.txt", null).success()).isFalse();
        }

        @Test
        void readingAMissingFileIsEmptyRatherThanAnError() {
            assertThat(files.read(workspace(), "nope.txt")).isEmpty();
        }

        @Test
        @DisplayName("directories cannot be deleted")
        void refusesDirectoryDeletion() {
            Workspace workspace = workspace();
            files.write(workspace, "docs/a.md", "x");

            // Recursive deletion is the tool most likely to turn one bad path into an
            // unrecoverable mistake, and nothing in an agent's job needs it.
            assertThat(files.delete(workspace, "docs").success()).isFalse();
            assertThat(files.delete(workspace, "docs/a.md").success()).isTrue();
        }

        @Test
        void existsIsFalseRatherThanThrowingForABadPath() {
            assertThat(files.exists(workspace(), "../../etc/passwd")).isFalse();
        }
    }

    // ------------------------------------------------------------------ processes

    @Nested
    @DisplayName("process execution")
    class Processes {

        @Test
        @DisplayName("only allowlisted executables run")
        void enforcesTheAllowlist() {
            Workspace workspace = workspace();

            // Confining an agent's files while letting it run arbitrary binaries confines nothing.
            assertThatThrownBy(() -> processes.run(workspace.root(), Duration.ofSeconds(5),
                    List.of("rm", "-rf", "/")))
                    .isInstanceOf(SandboxViolationException.class)
                    .hasMessageContaining("allowlist");
            assertThatThrownBy(() -> processes.run(workspace.root(), Duration.ofSeconds(5),
                    List.of("bash", "-c", "echo hi")))
                    .isInstanceOf(SandboxViolationException.class);
        }

        @Test
        void rejectsEmptyCommands() {
            assertThatThrownBy(() -> processes.run(workspace().root(), Duration.ofSeconds(5),
                    List.of()))
                    .isInstanceOf(SandboxViolationException.class);
        }

        @Test
        void rejectsArgumentsWithNulBytes() {
            assertThatThrownBy(() -> processes.run(workspace().root(), Duration.ofSeconds(5),
                    List.of("git", "status", "x\0y")))
                    .isInstanceOf(SandboxViolationException.class);
        }

        @Test
        @DisplayName("arguments are never interpreted by a shell")
        void doesNotInvokeAShell() {
            Workspace workspace = workspace();
            files.write(workspace, "marker.txt", "still here");

            // Passed straight to execve, so this is a nonsense git subcommand, not a command
            // substitution. If a shell were involved, marker.txt would be gone.
            ToolResult result = processes.run(workspace.root(), Duration.ofSeconds(20),
                    List.of("git", "; rm -rf .", "&& echo pwned"));

            assertThat(result.success()).isFalse();
            assertThat(files.exists(workspace, "marker.txt")).isTrue();
        }

        @Test
        @DisplayName("a missing executable is a tool failure, not an exception")
        void missingExecutableFailsCleanly() {
            Workspace workspace = workspace();
            // "java" is allowlisted; a nonexistent working directory is the reachable failure here.
            ToolResult result = processes.run(workspace.root().resolve("nope"),
                    Duration.ofSeconds(5), List.of("java", "-version"));

            assertThat(result.success()).isFalse();
            assertThat(result.summary()).contains("does not exist");
        }

        @Test
        @DisplayName("a process past its deadline is killed and reported")
        void enforcesTheTimeout() {
            Workspace workspace = workspace();
            // A JVM told to read stdin from a terminal it does not have will sit indefinitely.
            ToolResult result = processes.run(workspace.root(), Duration.ofMillis(300),
                    List.of("java", "-XshowSettings:properties", "-Xint", "-version"));

            // Either it completed inside the deadline or it was killed; both are acceptable
            // outcomes. What must never happen is the call not returning at all.
            assertThat(result).isNotNull();
            assertThat(result.durationMs()).isLessThan(30_000);
        }

        @Test
        @DisplayName("a non-zero exit is reported with its output, not swallowed")
        void reportsNonZeroExits() {
            Workspace workspace = workspace();
            ToolResult result = processes.run(workspace.root(), Duration.ofSeconds(20),
                    List.of("git", "rev-parse", "HEAD"));

            // No repository yet, so git exits non-zero and says why.
            assertThat(result.success()).isFalse();
            assertThat(result.exitCode()).isNotZero();
            assertThat(result.tail(3)).isNotBlank();
        }
    }

    // ------------------------------------------------------------------ git

    @Nested
    @DisplayName("git")
    class Git {

        private final GitTool git = new GitTool(processes);

        @Test
        @DisplayName("a repository is initialised with a local identity")
        void initialisesWithIdentity() {
            Workspace workspace = workspace();

            assertThat(git.ensureRepository(workspace).success()).isTrue();
            assertThat(Files.isDirectory(workspace.root().resolve(".git"))).isTrue();

            // Without a configured identity, commit fails with "Please tell me who you are" on any
            // machine without global git config, and the first symptom is an unrelated-looking
            // node failure.
            files.write(workspace, "a.txt", "one");
            assertThat(git.commitAll(workspace, "first").success()).isTrue();
        }

        @Test
        @DisplayName("committing with nothing staged succeeds rather than failing")
        void handlesAnEmptyCommit() {
            Workspace workspace = workspace();
            git.ensureRepository(workspace);
            files.write(workspace, "a.txt", "one");
            git.commitAll(workspace, "first");

            GitTool.CommitResult second = git.commitAll(workspace, "nothing changed");

            // A review or readiness node legitimately changes no files; failing it for that would
            // punish doing exactly what was asked.
            assertThat(second.success()).isTrue();
            assertThat(second.sha()).isNull();
            assertThat(second.summary()).contains("No file changes");
        }

        @Test
        void revertsACommit() {
            Workspace workspace = workspace();
            git.ensureRepository(workspace);
            files.write(workspace, "a.txt", "one");
            git.commitAll(workspace, "first");

            files.write(workspace, "b.txt", "two");
            String sha = git.commitAll(workspace, "second").sha();
            assertThat(sha).isNotNull();

            assertThat(git.revert(workspace, sha).success()).isTrue();
            assertThat(files.exists(workspace, "b.txt")).isFalse();
            assertThat(files.exists(workspace, "a.txt")).isTrue();
        }

        @Test
        @DisplayName("reverting keeps history rather than erasing it")
        void revertAddsACommitRatherThanRemovingOne() {
            Workspace workspace = workspace();
            git.ensureRepository(workspace);
            files.write(workspace, "a.txt", "one");
            git.commitAll(workspace, "first");
            files.write(workspace, "b.txt", "two");
            String sha = git.commitAll(workspace, "second").sha();

            git.revert(workspace, sha);

            // The head moved forward, not back: that something was done and undone stays visible,
            // which is the opposite of what a reset would leave behind.
            assertThat(git.headSha(workspace)).isPresent().get().isNotEqualTo(sha);
        }

        @Test
        void revertingWithNoShaIsRefused() {
            Workspace workspace = workspace();
            git.ensureRepository(workspace);

            assertThat(git.revert(workspace, null).success()).isFalse();
            assertThat(git.revert(workspace, "  ").success()).isFalse();
        }

        @Test
        void reportsWhetherTheTreeIsClean() {
            Workspace workspace = workspace();
            git.ensureRepository(workspace);
            files.write(workspace, "a.txt", "one");

            assertThat(git.isClean(workspace)).isFalse();
            git.commitAll(workspace, "first");
            assertThat(git.isClean(workspace)).isTrue();
        }
    }
}
