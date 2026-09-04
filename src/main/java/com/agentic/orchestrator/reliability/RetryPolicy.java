package com.agentic.orchestrator.reliability;

import java.time.Duration;

/**
 * Bounded retry with exponential backoff.
 *
 * <p>Bounded is the operative word. An orchestrator that retries indefinitely converts a broken node
 * into an infinite loop that consumes budget and never surfaces the problem, which is strictly worse
 * than failing and telling someone.
 *
 * @param maxAttempts    total attempts including the first, so 1 means "no retry"
 * @param initialBackoff delay before the second attempt
 * @param multiplier     growth factor applied per subsequent attempt
 * @param maxBackoff     ceiling, so exponential growth cannot run away
 */
public record RetryPolicy(int maxAttempts, Duration initialBackoff, double multiplier,
                          Duration maxBackoff) {

    public static final RetryPolicy NONE =
            new RetryPolicy(1, Duration.ZERO, 1.0, Duration.ZERO);

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
    }

    public static RetryPolicy of(int maxAttempts, Duration initialBackoff) {
        return new RetryPolicy(maxAttempts, initialBackoff, 2.0, Duration.ofSeconds(10));
    }

    public boolean allowsAnotherAttempt(int attemptsSoFar) {
        return attemptsSoFar < maxAttempts;
    }

    /** Delay before the attempt following {@code attemptsSoFar}. */
    public Duration backoffAfter(int attemptsSoFar) {
        if (attemptsSoFar < 1) {
            return Duration.ZERO;
        }
        double scaled = initialBackoff.toMillis() * Math.pow(multiplier, attemptsSoFar - 1.0);
        long capped = Math.min((long) scaled, maxBackoff.toMillis());
        return Duration.ofMillis(Math.max(0, capped));
    }
}
