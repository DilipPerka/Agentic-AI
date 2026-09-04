package com.agentic.orchestrator.agent;

import static com.agentic.orchestrator.agent.UrlShortenerBlueprints.PACKAGE_DIR;
import static com.agentic.orchestrator.agent.UrlShortenerBlueprints.TEST_DIR;

import com.agentic.orchestrator.plan.Stage;
import com.agentic.orchestrator.plan.Task;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Deterministic agent runtime: emits pre-written blueprints.
 *
 * <p><b>Stated plainly:</b> this does not synthesise code. The source it emits was written in
 * advance. What is genuinely being exercised is everything around it — when each piece is written,
 * whether it compiles, whether its tests pass, and whether the gate accepts the result. Swapping in
 * an LLM-backed runtime changes only this class.
 *
 * <p>Its value over an LLM for building the rest of the system is that it is reproducible and needs
 * no key, so a failing gate is unambiguously the orchestrator's fault rather than the model's.
 */
@Component
public class BlueprintAgentRuntime implements AgentRuntime {

    @Override
    public String name() {
        return "blueprint";
    }

    /**
     * Whether some predecessor produced a given declared output.
     *
     * <p>Upstream keys arrive namespaced as {@code TASK_ID.outputName}, so a bare
     * {@code containsKey("AnalyticsService")} silently never matches. That is not a hypothetical:
     * getting this wrong made {@code IMPL_RATE_LIMITING} re-emit the pre-analytics
     * {@code RedirectController}, quietly deleting click recording. Both versions compiled and every
     * test passed, so nothing caught it — which is precisely why the check is a named method with
     * this comment attached rather than an inline string compare.
     */
    private static boolean producedUpstream(Map<String, String> upstreamOutputs, String output) {
        String suffix = "." + output;
        return upstreamOutputs.keySet().stream().anyMatch(key -> key.endsWith(suffix));
    }

    @Override
    public AgentOutput produce(Task task, boolean degraded, Map<String, String> upstreamOutputs) {
        Map<String, String> files = new LinkedHashMap<>();
        Map<String, String> evidence = new LinkedHashMap<>();

        String id = task.id();
        switch (id) {
            case "REQ_NORMALISE" -> {
                files.put("docs/requirements.md", requirementsDoc(task));
                evidence.put("artifact:requirement-spec", "docs/requirements.md");
            }
            case "CLARIFY" -> {
                files.put("docs/clarification.md", clarificationDoc());
                evidence.put("artifact:clarification", "docs/clarification.md");
            }
            case "IMPACT_ANALYSIS" -> {
                files.put("docs/impact-analysis.md", impactDoc());
                evidence.put("artifact:impact-analysis", "docs/impact-analysis.md");
            }
            case "ARCH_BASELINE" -> {
                files.put("pom.xml", UrlShortenerBlueprints.POM);
                files.put(PACKAGE_DIR + "UrlShortenerApplication.java",
                        UrlShortenerBlueprints.APPLICATION);
                files.put("src/main/resources/application.properties",
                        UrlShortenerBlueprints.APPLICATION_PROPERTIES);
                files.put(".gitignore", UrlShortenerBlueprints.GITIGNORE);
                files.put("docs/architecture.md", architectureDoc());
                evidence.put("artifact:architecture", "docs/architecture.md");
            }
            case "SCHEMA_LINK_CREATION" -> {
                files.put("src/main/resources/db/migration/V1__links.sql",
                        UrlShortenerBlueprints.MIGRATION_LINKS);
                evidence.put("migration:LINK_CREATION",
                        "src/main/resources/db/migration/V1__links.sql");
            }
            case "IMPL_LINK_CREATION" -> {
                files.put(PACKAGE_DIR + "Link.java", UrlShortenerBlueprints.LINK);
                files.put(PACKAGE_DIR + "CodeGenerator.java", UrlShortenerBlueprints.CODE_GENERATOR);
                files.put(PACKAGE_DIR + "LinkRepository.java",
                        UrlShortenerBlueprints.LINK_REPOSITORY);
                files.put(PACKAGE_DIR + "LinkService.java",
                        UrlShortenerBlueprints.LINK_SERVICE_BASE);
                files.put(PACKAGE_DIR + "LinkResponse.java", UrlShortenerBlueprints.LINK_RESPONSE);
                files.put(PACKAGE_DIR + "LinkController.java",
                        UrlShortenerBlueprints.LINK_CONTROLLER);
                evidence.put("Link", PACKAGE_DIR + "Link.java");
                evidence.put("CodeGenerator", PACKAGE_DIR + "CodeGenerator.java");
                evidence.put("LinkRepository", PACKAGE_DIR + "LinkRepository.java");
                evidence.put("LinkService", PACKAGE_DIR + "LinkService.java");
                evidence.put("LinkController", PACKAGE_DIR + "LinkController.java");
            }
            case "IMPL_REDIRECT" -> {
                // Rewrites LinkService with resolve() added. This is why the planner serialised
                // this node against IMPL_LINK_CREATION: they genuinely write the same file.
                files.put(PACKAGE_DIR + "LinkService.java",
                        UrlShortenerBlueprints.LINK_SERVICE_WITH_RESOLVE);
                files.put(PACKAGE_DIR + "RedirectController.java",
                        UrlShortenerBlueprints.REDIRECT_CONTROLLER);
                evidence.put("RedirectController", PACKAGE_DIR + "RedirectController.java");
                evidence.put("LinkService", PACKAGE_DIR + "LinkService.java");
            }
            // ── brownfield: click analytics ──────────────────────────────────────────────────
            case "SCHEMA_CLICK_ANALYTICS" -> {
                files.put("src/main/resources/db/migration/V2__clicks.sql",
                        BrownfieldBlueprints.MIGRATION_CLICKS);
                evidence.put("migration:CLICK_ANALYTICS",
                        "src/main/resources/db/migration/V2__clicks.sql");
            }
            case "IMPL_CLICK_ANALYTICS" -> {
                files.put(PACKAGE_DIR + "ClickEvent.java", BrownfieldBlueprints.CLICK_EVENT);
                files.put(PACKAGE_DIR + "ClickEventRepository.java",
                        BrownfieldBlueprints.CLICK_EVENT_REPOSITORY);
                files.put(PACKAGE_DIR + "AnalyticsService.java",
                        BrownfieldBlueprints.ANALYTICS_SERVICE);
                files.put(PACKAGE_DIR + "AsyncConfig.java", BrownfieldBlueprints.ASYNC_CONFIG);
                files.put(PACKAGE_DIR + "AnalyticsController.java",
                        BrownfieldBlueprints.ANALYTICS_CONTROLLER);
                // Rewrites a file the baseline already had: the redirect path now records a click.
                // This is the write conflict the planner serialises against IMPL_RATE_LIMITING.
                files.put(PACKAGE_DIR + "RedirectController.java",
                        BrownfieldBlueprints.REDIRECT_CONTROLLER_WITH_ANALYTICS);
                // Evidence keys are the component names the capability catalogue declared. They are
                // what the exit gate checks, so they are a contract, not a label.
                evidence.put("ClickEvent", PACKAGE_DIR + "ClickEvent.java");
                evidence.put("ClickEventRepository", PACKAGE_DIR + "ClickEventRepository.java");
                evidence.put("AnalyticsService", PACKAGE_DIR + "AnalyticsService.java");
                evidence.put("AnalyticsController", PACKAGE_DIR + "AnalyticsController.java");
                evidence.put("RedirectController", PACKAGE_DIR + "RedirectController.java");
            }
            case "TEST_CLICK_ANALYTICS" -> {
                files.put(TEST_DIR + "ClickAnalyticsTest.java",
                        BrownfieldBlueprints.CLICK_ANALYTICS_TEST);
                evidence.put("test:CLICK_ANALYTICS", TEST_DIR + "ClickAnalyticsTest.java");
            }

            // ── brownfield: rate limiting ────────────────────────────────────────────────────
            case "IMPL_RATE_LIMITING" -> {
                files.put(PACKAGE_DIR + "RateLimiter.java", BrownfieldBlueprints.RATE_LIMITER);
                files.put(PACKAGE_DIR + "RateLimitConfig.java",
                        BrownfieldBlueprints.RATE_LIMIT_CONFIG);
                files.put(PACKAGE_DIR + "RateLimitFilter.java",
                        BrownfieldBlueprints.RATE_LIMIT_FILTER);
                files.put("src/main/resources/application.properties",
                        BrownfieldBlueprints.APPLICATION_PROPERTIES_WITH_LIMITS);

                // The catalogue declares RedirectController as touched by rate limiting, so the gate
                // demands it. The considered answer is "no change on the read path" — limiting
                // redirects would break a link for being popular — so the file is re-emitted as it
                // should stand. Which variant depends on whether analytics was built upstream:
                // writing the analytics-aware one without AnalyticsService present would not compile.
                files.put(PACKAGE_DIR + "RedirectController.java",
                        producedUpstream(upstreamOutputs, "AnalyticsService")
                                ? BrownfieldBlueprints.REDIRECT_CONTROLLER_WITH_ANALYTICS
                                : UrlShortenerBlueprints.REDIRECT_CONTROLLER);

                evidence.put("RateLimitConfig", PACKAGE_DIR + "RateLimitConfig.java");
                evidence.put("RateLimitFilter", PACKAGE_DIR + "RateLimitFilter.java");
                evidence.put("RedirectController", PACKAGE_DIR + "RedirectController.java");
            }
            case "TEST_RATE_LIMITING" -> {
                files.put(TEST_DIR + "RateLimiterTest.java", BrownfieldBlueprints.RATE_LIMIT_TEST);
                evidence.put("test:RATE_LIMITING", TEST_DIR + "RateLimiterTest.java");
            }

            // ── clarified-from-ambiguous: caching and observability ─────────────────────────
            case "IMPL_CACHING" -> {
                files.put(PACKAGE_DIR + "CacheConfig.java", PerformanceBlueprints.CACHE_CONFIG);
                // Rewrites LinkService to add @Cacheable/@CacheEvict, which is why the planner
                // serialises this against IMPL_REDIRECT: they write the same file.
                files.put(PACKAGE_DIR + "LinkService.java",
                        PerformanceBlueprints.LINK_SERVICE_CACHED);
                files.put(PACKAGE_DIR + "RedirectController.java",
                        producedUpstream(upstreamOutputs, "AnalyticsService")
                                ? BrownfieldBlueprints.REDIRECT_CONTROLLER_WITH_ANALYTICS
                                : UrlShortenerBlueprints.REDIRECT_CONTROLLER);
                evidence.put("CacheConfig", PACKAGE_DIR + "CacheConfig.java");
                evidence.put("LinkService", PACKAGE_DIR + "LinkService.java");
                evidence.put("RedirectController", PACKAGE_DIR + "RedirectController.java");
            }
            case "TEST_CACHING" -> {
                files.put(TEST_DIR + "CachingTest.java", PerformanceBlueprints.CACHE_TEST);
                evidence.put("test:CACHING", TEST_DIR + "CachingTest.java");
            }
            case "IMPL_OBSERVABILITY" -> {
                files.put(PACKAGE_DIR + "ObservabilityConfig.java",
                        PerformanceBlueprints.OBSERVABILITY_CONFIG);
                files.put(PACKAGE_DIR + "MetricsConfig.java", PerformanceBlueprints.METRICS_CONFIG);
                evidence.put("ObservabilityConfig", PACKAGE_DIR + "ObservabilityConfig.java");
                evidence.put("MetricsConfig", PACKAGE_DIR + "MetricsConfig.java");
            }
            case "TEST_OBSERVABILITY" -> {
                files.put(TEST_DIR + "ObservabilityTest.java",
                        PerformanceBlueprints.OBSERVABILITY_TEST);
                evidence.put("test:OBSERVABILITY", TEST_DIR + "ObservabilityTest.java");
            }

            case "TEST_LINK_CREATION" -> {
                files.put(TEST_DIR + "CodeGeneratorTest.java",
                        UrlShortenerBlueprints.CODE_GENERATOR_TEST);
                files.put(TEST_DIR + "LinkServiceValidationTest.java",
                        UrlShortenerBlueprints.LINK_API_TEST);
                evidence.put("test:LINK_CREATION", TEST_DIR + "CodeGeneratorTest.java");
            }
            case "TEST_REDIRECT" -> {
                files.put(TEST_DIR + "LinkUsabilityTest.java", UrlShortenerBlueprints.REDIRECT_TEST);
                evidence.put("test:REDIRECT", TEST_DIR + "LinkUsabilityTest.java");
            }
            case "INTEGRATION_TEST" -> {
                files.put(TEST_DIR + "LinkApiIntegrationTest.java",
                        UrlShortenerBlueprints.END_TO_END_TEST);
                evidence.put("test:integration", TEST_DIR + "LinkApiIntegrationTest.java");

                // The analytics suite references classes that exist only after the analytics
                // capability was built. Writing it unconditionally would break the greenfield
                // compile — so what gets written is decided by what upstream actually produced,
                // which is exactly what the declared-outputs contract is for.
                if (producedUpstream(upstreamOutputs, "test:CLICK_ANALYTICS")) {
                    files.put(TEST_DIR + "AnalyticsApiIntegrationTest.java",
                            BrownfieldBlueprints.ANALYTICS_INTEGRATION_TEST);
                    evidence.put("test:integration:analytics",
                            TEST_DIR + "AnalyticsApiIntegrationTest.java");
                }
            }
            case "DOCUMENTATION" -> {
                files.put("README.md", UrlShortenerBlueprints.README);
                evidence.put("artifact:docs", "README.md");
            }
            case "CODE_REVIEW" -> {
                files.put("docs/review.md", reviewDoc());
                evidence.put("artifact:review", "docs/review.md");
            }
            case "RELEASE_READINESS" -> {
                files.put("docs/release-notes.md", releaseDoc());
                evidence.put("artifact:release-note", "docs/release-notes.md");
            }
            default -> {
                if (id.startsWith("DESIGN_")) {
                    String capability = id.substring("DESIGN_".length());
                    String path = "docs/design-" + capability.toLowerCase() + ".md";
                    files.put(path, designDoc(capability));
                    evidence.put("artifact:design:" + capability, path);
                } else {
                    return degraded
                            ? stub(task)
                            : AgentOutput.unsupported(
                                    "No blueprint for " + id + ". The deterministic runtime covers "
                                            + "the core shortener capabilities; an LLM-backed "
                                            + "runtime would not have this limit.");
                }
            }
        }
        return AgentOutput.of(files, evidence, "blueprint produced " + files.size() + " file(s)");
    }

    /**
     * The degraded fallback: a documented placeholder rather than working code.
     *
     * <p>Honest about being a placeholder. Emitting something that merely compiles would let a node
     * pass its gate while delivering nothing, which is worse than the node failing — the run would
     * go green over a hole.
     */
    private AgentOutput stub(Task task) {
        Map<String, String> files = new LinkedHashMap<>();
        Map<String, String> evidence = new LinkedHashMap<>();
        String path = "docs/stubs/" + task.id().toLowerCase() + ".md";

        files.put(path, """
                # Placeholder: %s

                **This is not an implementation.**

                The deterministic runtime has no blueprint for `%s`, retries were exhausted, and the
                degraded fallback produced this note instead so that the run could continue and a
                human could see the gap.

                ## What was requested
                %s

                ## Declared outputs that do not exist
                %s

                ## To resolve
                Add a blueprint for this task, or run with an LLM-backed agent runtime.
                """.formatted(task.id(), task.id(), task.description(),
                String.join(", ", task.writes())));

        // Evidence points at the placeholder, and says so. The gate is satisfied that *something*
        // was produced; the run is marked degraded so nobody mistakes it for done.
        for (String declared : task.writes()) {
            evidence.put(declared, path);
        }
        return AgentOutput.of(files, evidence, "degraded: placeholder note, no implementation");
    }

    // ------------------------------------------------------------------ documents

    private String requirementsDoc(Task task) {
        return """
                # Normalised requirement

                %s

                ## Acceptance criteria
                %s
                """.formatted(task.description(),
                task.acceptanceCriteria().stream()
                        .map(criterion -> "- " + criterion)
                        .reduce((a, b) -> a + "\n" + b).orElse("- none recorded"));
    }

    private String clarificationDoc() {
        return """
                # Clarification

                A human reviewed the under-specified terms in the requirement and approved this task,
                which is the record that the ambiguity was resolved rather than assumed away.
                """;
    }

    private String impactDoc() {
        return """
                # Impact analysis

                ## Affected components
                - `RedirectController` — the request hot path; any change here affects every click.
                - `LinkService` — shared by the management API and the redirect path.
                - `links` table — schema changes are hard to reverse once data exists.

                ## Risks
                - Latency regression on redirect is not caught by functional tests.
                - Additive migrations are safe; column drops are not.
                """;
    }

    private String architectureDoc() {
        return """
                # Architecture baseline

                Spring Boot 3, Java 21, H2 with Flyway migrations, JdbcTemplate rather than JPA.

                ## Boundaries
                `Controller -> Service -> Repository`. Dependencies point inward only; the repository
                knows nothing about HTTP and the controller knows nothing about SQL.

                ## Key decisions
                - **JdbcTemplate over JPA.** The data model is one table read by primary key. An ORM
                  would add a mapping layer and a lazy-loading failure mode for no benefit.
                - **Code as primary key.** It is the natural key and the only thing redirects look up
                  by; a surrogate id would add a second index to the hottest query.
                - **Flyway from the start.** Schema changes are the least reversible thing here, so
                  they get version control before there is anything to version.
                """;
    }

    private String designDoc(String capability) {
        return """
                # Design: %s

                ## API contract
                See `README.md` for the endpoint table, including failure responses.

                ## Failure behaviour
                - Invalid input returns 400 with a message, never a stack trace.
                - A conflicting alias returns 409, distinguishable from a validation failure.
                - An unknown or unusable code returns 404, and does not distinguish "never existed"
                  from "expired" — that difference would leak which codes are in use.
                """.formatted(capability);
    }

    private String reviewDoc() {
        return """
                # Code review

                **Verdict: APPROVE**

                ## Checked
                - Target URL validation rejects non-http(s) schemes, which is the open-redirect risk.
                - Code generation is random, not sequential, so links are not enumerable.
                - Collision handling relies on the primary key rather than check-then-insert.
                - Redirect resolution applies expiry and deactivation; the management API does not.

                ## Outstanding
                - No rate limiting: link creation is currently unbounded.
                - In-memory H2: not suitable beyond development.
                """;
    }

    private String releaseDoc() {
        return """
                # Release readiness

                ## Evidence
                - Build green, tests passing (see the run's event log for the counts the gate read).
                - Every high-impact change was approved by a human before it ran.

                ## Outstanding risks
                - No authentication on link creation.
                - No rate limiting.
                - In-memory storage: data does not survive a restart.

                ## Rollback
                Every node committed separately; `git revert <sha>` undoes one, and the orchestrator's
                rollback endpoint undoes them all in reverse order.
                """;
    }
}
