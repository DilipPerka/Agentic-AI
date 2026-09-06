package com.agentic.orchestrator.sandbox;

import java.util.Locale;

/**
 * The one place the host operating system is consulted.
 *
 * <p>Kept to a single flag rather than scattered {@code os.name} checks so that the places where
 * behaviour genuinely differs — which executable name launches Maven, how long a path may be — are
 * findable, and so a test can reason about both branches without reflection.
 */
public final class Platform {

    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win");

    private Platform() {
    }

    public static boolean isWindows() {
        return WINDOWS;
    }
}
