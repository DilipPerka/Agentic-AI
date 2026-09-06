package com.agentic.orchestrator.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The directory an agent is confined to, and the only way to turn a requested path into a real one.
 *
 * <p>Every filesystem action goes through {@link #resolve}. The checks below are not defensive
 * padding — each corresponds to a way a path can leave a directory it appears to be inside:
 *
 * <ol>
 *   <li><b>Absolute paths</b> ignore the root entirely.
 *   <li><b>{@code ..} segments</b> walk out. Normalising first and comparing after is what catches
 *       {@code a/../../b}, which looks contained until you resolve it.
 *   <li><b>Symlinks</b> are the subtle one. A path can normalise to something inside the root and
 *       still resolve to a file outside it, so the <em>real</em> path is checked too — the parent's
 *       real path when the file does not exist yet, since a symlinked directory is enough.
 *   <li><b>NUL bytes</b> truncate the path in native code below the JVM, so a name the JVM sees as
 *       {@code "safe\0../../etc/passwd"} can reach the filesystem as something else.
 *   <li><b>Overlong paths</b> hit platform limits mid-operation, leaving partial state.
 *   <li><b>Windows name games</b> — a backslash separator, a trailing dot or space that Windows
 *       silently strips, a reserved device name like {@code NUL} that is not a file at all.
 * </ol>
 *
 * <p>Checking the string alone would satisfy none of these. The comparison that matters is always
 * between resolved, real paths.
 *
 * <h2>One ruleset on every platform</h2>
 * The Windows name rules are enforced on Linux and macOS too, even though a file called {@code aux.}
 * is perfectly ordinary there. A workspace that cannot be checked out on Windows is a defect wherever
 * it was produced, and git refuses these names on Windows checkout for the same reason. Enforcing the
 * union means the same requirement yields the same result on every host, which is the property the
 * deterministic runtime exists to provide. Only the <em>length</em> limit is genuinely per-platform,
 * because only there does the host impose a different hard ceiling.
 */
public final class Workspace {

    private static final Logger log = LoggerFactory.getLogger(Workspace.class);

    /** Comfortably under the usual 4096-byte limit, leaving room for names appended below. */
    private static final int MAX_PATH_LENGTH = 3000;

    /**
     * Windows refuses to create a path at or beyond {@code MAX_PATH} (260, minus a NUL terminator)
     * unless long paths are enabled machine-wide, which cannot be assumed. Checking here turns a
     * mid-run {@code IOException} from somewhere inside a write into a refusal that names the cause.
     */
    private static final int MAX_ABSOLUTE_LENGTH = Platform.isWindows() ? 259 : 4000;

    /** Device names Windows resolves ahead of any file, with or without an extension. */
    private static final Pattern RESERVED_NAME = Pattern.compile(
            "(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?$");

    private final Path root;

    private Workspace(Path root) {
        this.root = root;
    }

    /** Creates the directory if absent and pins the workspace to its real location. */
    public static Workspace at(Path directory) {
        try {
            Files.createDirectories(directory);
            // toRealPath once, here: if the root itself is reached through a symlink, every later
            // comparison must be against where it actually lives, not how we got there.
            Path real = directory.toRealPath();
            log.info("Workspace rooted at {}", real);
            return new Workspace(real);
        } catch (IOException failure) {
            throw new SandboxViolationException(
                    "Could not establish workspace at " + directory + ": " + failure.getMessage());
        }
    }

    public Path root() {
        return root;
    }

    /**
     * Turns a workspace-relative path into an absolute one, or refuses.
     *
     * @throws SandboxViolationException if the path is malformed or would escape the workspace
     */
    public Path resolve(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new SandboxViolationException("Path must not be blank");
        }
        if (relativePath.indexOf('\0') >= 0) {
            throw new SandboxViolationException("Path contains a NUL byte: " + sanitise(relativePath));
        }
        if (relativePath.length() > MAX_PATH_LENGTH) {
            throw new SandboxViolationException(
                    "Path exceeds " + MAX_PATH_LENGTH + " characters");
        }
        if (relativePath.indexOf('\\') >= 0) {
            // A separator on Windows, an ordinary filename character elsewhere. Accepting it would
            // mean the same string names two different files depending on the host.
            throw new SandboxViolationException(
                    "Backslash is not a valid path separator here; use '/': "
                            + sanitise(relativePath));
        }
        verifySegmentsAreUsable(relativePath);

        Path requested;
        try {
            requested = Paths.get(relativePath);
        } catch (Exception malformed) {
            // InvalidPathException on Windows for characters the NTFS parser rejects outright
            // (`:` naming an alternate data stream, `<>|?*`). Unix reaches here far more rarely.
            throw new SandboxViolationException(
                    "Malformed path: " + sanitise(relativePath));
        }

        // getRoot() as well as isAbsolute(): on Windows "/etc/passwd" is *not* absolute — it has a
        // root but no drive letter — and would otherwise be resolved against the workspace's drive.
        if (requested.isAbsolute() || requested.getRoot() != null) {
            throw new SandboxViolationException(
                    "Absolute paths are not permitted: " + sanitise(relativePath));
        }

        Path normalised = root.resolve(requested).normalize();
        if (!normalised.startsWith(root)) {
            throw new SandboxViolationException(
                    "Path escapes the workspace: " + sanitise(relativePath));
        }
        if (normalised.equals(root)) {
            throw new SandboxViolationException("Path must name a file, not the workspace root");
        }
        if (normalised.toString().length() > MAX_ABSOLUTE_LENGTH) {
            throw new SandboxViolationException(
                    "Resolved path exceeds this platform's " + MAX_ABSOLUTE_LENGTH
                            + "-character limit: " + sanitise(relativePath));
        }

        verifyNoSymlinkEscape(normalised, relativePath);
        return normalised;
    }

    /**
     * Rejects segment names Windows would not store as asked.
     *
     * <p>A trailing dot or space is stripped silently, so {@code "evidence."} and {@code "evidence"}
     * become the same file — which makes a containment check on the requested string meaningless. A
     * reserved name is not a file at all: opening {@code NUL} succeeds and discards everything
     * written to it, so a node could "write" its output and pass its gate having produced nothing.
     */
    private static void verifySegmentsAreUsable(String relativePath) {
        for (String segment : relativePath.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                continue; // Normalisation deals with these; they are not names.
            }
            if (segment.endsWith(".") || segment.endsWith(" ")) {
                throw new SandboxViolationException(
                        "Path segment ends with a dot or space, which Windows strips: "
                                + sanitise(relativePath));
            }
            if (RESERVED_NAME.matcher(segment).matches()) {
                throw new SandboxViolationException(
                        "Path segment is a reserved device name: " + sanitise(segment));
            }
        }
    }

    /**
     * Normalising catches {@code ..}; it does not catch a symlink. The nearest existing ancestor is
     * resolved to its real location and re-checked, which covers both an existing file that is a
     * link and a new file whose parent directory is one.
     */
    private void verifyNoSymlinkEscape(Path normalised, String requested) {
        Path existing = normalised;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new SandboxViolationException(
                    "No existing ancestor within the workspace for " + sanitise(requested));
        }

        try {
            Path real = existing.toRealPath();
            if (!real.startsWith(root)) {
                throw new SandboxViolationException(
                        "Path resolves outside the workspace through a link: " + sanitise(requested));
            }
        } catch (IOException failure) {
            throw new SandboxViolationException(
                    "Could not verify " + sanitise(requested) + ": " + failure.getMessage());
        }
    }

    /**
     * Relative form of an absolute path, for logs and evidence.
     *
     * <p>Always {@code /}-separated. Evidence keys are compared against the paths a blueprint
     * declared, which are written with forward slashes; returning {@code docs\readme.md} on Windows
     * would make a gate fail to match its own artifact.
     */
    public String relativise(Path absolute) {
        return root.relativize(absolute).toString().replace('\\', '/');
    }

    /** Keeps a hostile path out of logs verbatim and bounds its length. */
    private static String sanitise(String value) {
        String cleaned = value.replaceAll("[\\p{Cntrl}]", "?");
        return cleaned.length() > 200 ? cleaned.substring(0, 200) + "..." : cleaned;
    }
}
