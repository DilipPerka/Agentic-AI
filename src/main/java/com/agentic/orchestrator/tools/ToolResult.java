package com.agentic.orchestrator.tools;

public record ToolResult(boolean success, int exitCode, String stdout, String stderr,
                         long durationMs, String summary) {

    public static ToolResult ok(String summary) {
        return new ToolResult(true, 0, "", "", 0, summary);
    }

    public static ToolResult failed(String summary) {
        return new ToolResult(false, -1, "", "", 0, summary);
    }

    /** Last few lines of output, for an error message that fits in an event log entry. */
    public String tail(int lines) {
        String combined = (stderr == null || stderr.isBlank()) ? stdout : stderr;
        if (combined == null || combined.isBlank()) {
            return summary;
        }
        String[] all = combined.strip().split("\n");
        int from = Math.max(0, all.length - lines);
        return String.join(" | ", java.util.Arrays.copyOfRange(all, from, all.length));
    }
}
