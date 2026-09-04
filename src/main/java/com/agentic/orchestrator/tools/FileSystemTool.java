package com.agentic.orchestrator.tools;

import com.agentic.orchestrator.sandbox.ContentPolicy;
import com.agentic.orchestrator.sandbox.SandboxViolationException;
import com.agentic.orchestrator.sandbox.Workspace;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The only way an agent touches the filesystem.
 *
 * <p>Every path goes through {@link Workspace#resolve}, and every write is screened by
 * {@link ContentPolicy} first. Screening before writing rather than after matters: a secret written
 * and then deleted has still been on disk, and if the node also committed, still in history.
 */
@Component
public class FileSystemTool {

    private final ContentPolicy contentPolicy;
    private final long maxFileBytes;

    public FileSystemTool(ContentPolicy contentPolicy,
                          @Value("${orchestrator.tools.max-file-bytes:1048576}") long maxFileBytes) {
        this.contentPolicy = contentPolicy;
        this.maxFileBytes = maxFileBytes;
    }

    public ToolResult write(Workspace workspace, String relativePath, String content) {
        if (content == null) {
            return ToolResult.failed("Refusing to write null content to " + relativePath);
        }

        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxFileBytes) {
            return ToolResult.failed("File exceeds " + maxFileBytes + " bytes: " + relativePath);
        }

        Optional<String> violation = contentPolicy.firstViolation(content);
        if (violation.isPresent()) {
            // Not a warning. A credential in generated source is the one thing that cannot be
            // undone by a later revert, because it has already existed on disk.
            throw new SandboxViolationException(
                    "Refusing to write " + relativePath + ": content matches " + violation.get());
        }

        try {
            Path target = workspace.resolve(relativePath);
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
                // Creating the parents can itself have crossed a link, so the final target is
                // re-checked against the workspace now that it exists.
                workspace.resolve(relativePath);
            }
            Files.writeString(target, content, StandardCharsets.UTF_8);
            return ToolResult.ok("Wrote " + relativePath + " (" + bytes.length + " bytes)");
        } catch (IOException failure) {
            return ToolResult.failed("Could not write " + relativePath + ": " + failure.getMessage());
        }
    }

    public Optional<String> read(Workspace workspace, String relativePath) {
        try {
            Path target = workspace.resolve(relativePath);
            if (!Files.isRegularFile(target)) {
                return Optional.empty();
            }
            if (Files.size(target) > maxFileBytes) {
                return Optional.empty();
            }
            return Optional.of(Files.readString(target, StandardCharsets.UTF_8));
        } catch (IOException failure) {
            return Optional.empty();
        }
    }

    public boolean exists(Workspace workspace, String relativePath) {
        try {
            return Files.exists(workspace.resolve(relativePath));
        } catch (SandboxViolationException refused) {
            return false;
        }
    }

    /**
     * Deletes a single regular file. Directory trees are deliberately not deletable: recursive
     * deletion is the tool most likely to turn one bad path into an unrecoverable mistake, and
     * nothing in the agent's job needs it.
     */
    public ToolResult delete(Workspace workspace, String relativePath) {
        try {
            Path target = workspace.resolve(relativePath);
            if (Files.isDirectory(target)) {
                return ToolResult.failed("Refusing to delete a directory: " + relativePath);
            }
            boolean deleted = Files.deleteIfExists(target);
            return deleted
                    ? ToolResult.ok("Deleted " + relativePath)
                    : ToolResult.failed("Nothing to delete at " + relativePath);
        } catch (IOException failure) {
            return ToolResult.failed(
                    "Could not delete " + relativePath + ": " + failure.getMessage());
        }
    }
}
