package com.agentic.orchestrator.requirement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Turns free text into a {@link Requirement}: classifies the scenario, extracts capabilities, and
 * records what remains under-specified.
 *
 * <p>The classification rule is deliberately blunt and stated out loud rather than tuned: a
 * requirement is {@link ScenarioType#AMBIGUOUS} when not a single concrete capability can be
 * extracted from it. That is defensible ("we could not find anything actionable to plan") and it
 * fails in the safe direction — towards asking a human rather than towards inventing work.
 */
@Component
public class RequirementNormalizer {

    private static final List<String> BROWNFIELD_SIGNALS = List.of(
            "add ", "adding", "extend", "enhance", "fix ", "bug", "refactor", "existing",
            "modify", "migrate", "improve the", "update the", "change the", "current");

    private static final List<String> GREENFIELD_SIGNALS = List.of(
            "build ", "create ", "from scratch", "new service", "new system", "greenfield",
            "implement a", "stand up");

    /** Vague terms that name a goal without naming a mechanism. */
    private static final Map<String, Ambiguity> VAGUE_TERMS = new LinkedHashMap<>();

    static {
        vague("reliable", "'Reliable' is unquantified. Which failure does it need to survive?",
                List.of("Uptime under load — retries, timeouts, circuit breakers",
                        "Data durability — replication, backups, no lost writes",
                        "Graceful degradation — serve stale data rather than fail"));
        vague("faster", "'Faster' has no target or baseline. What is being measured?",
                List.of("Redirect latency at p99 — caching, indexing",
                        "Link creation throughput — connection pooling, batching",
                        "Cold-start time — lazy initialisation"));
        vague("performance", "'Performance' names no metric. Which one, and what is the target?",
                List.of("Latency (p50/p95/p99)", "Throughput (requests/sec)",
                        "Resource footprint (memory, connections)"));
        vague("scalable", "'Scalable' to what, exactly?",
                List.of("Requests per second", "Total links stored",
                        "Geographic distribution / multi-region"));
        vague("secure", "'Secure' against which threat?",
                List.of("Abuse and denial of service — rate limiting",
                        "Unauthorised link creation — authentication",
                        "Open redirect and malicious target URLs — validation and blocklists"));
        vague("better", "'Better' names no dimension.",
                List.of("Correctness", "Performance", "Maintainability", "Security"));
        vague("improve", "'Improve' names no dimension.",
                List.of("Correctness", "Performance", "Maintainability", "Security"));
        vague("production ready", "'Production-ready' is a checklist, not a feature. Which items?",
                List.of("Observability and alerting", "Authentication and rate limiting",
                        "Backups and disaster recovery", "Runbooks and on-call docs"));
        vague("robust", "'Robust' against what kind of input or failure?",
                List.of("Malformed input — validation", "Downstream failure — timeouts and fallbacks",
                        "Concurrent load — locking and idempotency"));
    }

    private static void vague(String term, String question, List<String> options) {
        VAGUE_TERMS.put(term, new Ambiguity(term, question, options));
    }

    private final CapabilityCatalog catalog;

    public RequirementNormalizer(CapabilityCatalog catalog) {
        this.catalog = catalog;
    }

    public Requirement normalize(String rawText) {
        return normalize(rawText, null);
    }

    /**
     * @param scenarioHint optional override; useful for demonstrations where the same text should be
     *                     planned as greenfield and then as brownfield
     */
    public Requirement normalize(String rawText, ScenarioType scenarioHint) {
        String text = rawText == null ? "" : rawText.trim();
        String haystack = text.toLowerCase(Locale.ROOT);

        List<Capability> detected = catalog.detect(haystack);
        List<Ambiguity> ambiguities = detectAmbiguities(haystack);

        ScenarioType scenario = scenarioHint != null
                ? scenarioHint
                : classify(haystack, detected);

        List<Capability> planned;
        List<String> assumedExisting;
        if (scenario == ScenarioType.GREENFIELD) {
            // Nothing exists yet, so prerequisites are work we have to do.
            planned = catalog.withPrerequisites(ids(detected));
            assumedExisting = List.of();
        } else {
            // The prerequisites are already in the codebase; we depend on them, we don't rebuild them.
            planned = detected;
            assumedExisting = catalog.missingPrerequisites(ids(detected));
        }

        return new Requirement(text, summarise(text, scenario, planned), scenario, planned,
                assumedExisting, ambiguities);
    }

    private ScenarioType classify(String haystack, List<Capability> detected) {
        if (detected.isEmpty()) {
            return ScenarioType.AMBIGUOUS;
        }
        if (containsAny(haystack, GREENFIELD_SIGNALS)) {
            return ScenarioType.GREENFIELD;
        }
        if (containsAny(haystack, BROWNFIELD_SIGNALS)) {
            return ScenarioType.BROWNFIELD;
        }
        // No verb signal either way. Default to greenfield: planning prerequisites we did not need
        // is visible and cheap to correct, whereas silently assuming code exists that does not is
        // a failure the human only discovers at implementation time.
        return ScenarioType.GREENFIELD;
    }

    private List<Ambiguity> detectAmbiguities(String haystack) {
        List<Ambiguity> found = new ArrayList<>();
        for (Map.Entry<String, Ambiguity> entry : VAGUE_TERMS.entrySet()) {
            if (haystack.contains(entry.getKey())) {
                found.add(entry.getValue());
            }
        }
        return found;
    }

    private String summarise(String text, ScenarioType scenario, List<Capability> capabilities) {
        if (capabilities.isEmpty()) {
            return "Unable to derive concrete capabilities from the request; clarification required "
                    + "before a plan can be produced.";
        }
        String names = capabilities.stream()
                .map(Capability::displayName)
                .collect(Collectors.joining(", "));
        String verb = scenario == ScenarioType.GREENFIELD ? "Build" : "Extend the service with";
        return verb + ": " + names + ".";
    }

    private static List<String> ids(List<Capability> capabilities) {
        return capabilities.stream().map(Capability::id).toList();
    }

    private static boolean containsAny(String haystack, List<String> needles) {
        return needles.stream().anyMatch(haystack::contains);
    }
}
