package com.agentic.orchestrator.requirement;

import java.util.List;

/**
 * An under-specified term found in a requirement, together with why it blocks planning and what the
 * plausible readings are.
 *
 * <p>Recording {@code options} matters: "this is ambiguous" is a complaint, whereas "this is
 * ambiguous and here are the three things it could mean" is a question a human can actually answer
 * in one pass.
 */
public record Ambiguity(String term, String question, List<String> options) {

    public Ambiguity {
        options = List.copyOf(options);
    }
}
