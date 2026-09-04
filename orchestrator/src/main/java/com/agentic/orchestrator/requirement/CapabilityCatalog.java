package com.agentic.orchestrator.requirement;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Registry of capabilities the planner can recognise and expand, plus the keyword matching used to
 * detect them in free text.
 *
 * <p>Scoped to the URL-shortener domain on purpose. A generic catalogue would be less honest: the
 * point is to show a planner that reasons about a real domain's dependency structure, not one that
 * pattern-matches arbitrary English.
 */
@Component
public class CapabilityCatalog {

    public static final String LINK_CREATION = "LINK_CREATION";
    public static final String REDIRECT = "REDIRECT";
    public static final String LINK_MANAGEMENT = "LINK_MANAGEMENT";
    public static final String CLICK_ANALYTICS = "CLICK_ANALYTICS";
    public static final String RATE_LIMITING = "RATE_LIMITING";
    public static final String CACHING = "CACHING";
    public static final String AUTHENTICATION = "AUTHENTICATION";
    public static final String OBSERVABILITY = "OBSERVABILITY";

    private final Map<String, Capability> byId = new LinkedHashMap<>();
    private final Map<String, List<Pattern>> patternsById = new LinkedHashMap<>();

    public CapabilityCatalog() {
        register(new Capability(
                LINK_CREATION,
                "Short link creation",
                // "url shortener" is deliberately absent: it names the product, not a capability.
                // "Make the URL shortener faster" asks for nothing concrete, and should be treated
                // as ambiguous rather than silently read as a request to build link creation.
                List.of("shorten", "shortening", "short link", "shortlink", "short url",
                        "create link", "generate code"),
                true,
                false,
                Set.of(),
                Set.of("Link", "LinkRepository", "LinkService", "LinkController", "CodeGenerator")));

        register(new Capability(
                REDIRECT,
                "Short code redirect",
                List.of("redirect", "resolve", "302", "follow link", "look up code"),
                false,
                true,
                Set.of(LINK_CREATION),
                Set.of("RedirectController", "LinkService")));

        register(new Capability(
                LINK_MANAGEMENT,
                "Link lifecycle management",
                List.of("delete link", "expire", "expiry", "expiration", "ttl", "custom alias",
                        "alias", "update link", "manage link", "deactivate"),
                true,
                false,
                Set.of(LINK_CREATION),
                Set.of("Link", "LinkController", "LinkService")));

        register(new Capability(
                CLICK_ANALYTICS,
                "Click analytics",
                List.of("analytics", "click", "clicks", "stats", "statistics", "tracking", "track"),
                true,
                true,
                Set.of(REDIRECT),
                Set.of("ClickEvent", "ClickEventRepository", "AnalyticsService",
                        "AnalyticsController", "RedirectController")));

        register(new Capability(
                RATE_LIMITING,
                "Rate limiting",
                List.of("rate limit", "rate-limit", "ratelimit", "throttle", "throttling", "abuse"),
                false,
                true,
                Set.of(),
                Set.of("RateLimitFilter", "RateLimitConfig", "RedirectController")));

        register(new Capability(
                CACHING,
                "Hot-code caching",
                List.of("cache", "caching", "cache-aside", "in-memory lookup"),
                false,
                true,
                Set.of(REDIRECT),
                Set.of("CacheConfig", "LinkService", "RedirectController")));

        register(new Capability(
                AUTHENTICATION,
                "API authentication",
                List.of("authentication", "authenticated", "api key", "api-key", "apikey",
                        "oauth", "login", "bearer token"),
                true,
                false,
                Set.of(),
                Set.of("ApiKey", "ApiKeyFilter", "SecurityConfig")));

        register(new Capability(
                OBSERVABILITY,
                "Observability",
                List.of("observability", "monitoring", "health check", "healthcheck",
                        "metrics endpoint", "structured logging"),
                false,
                false,
                Set.of(),
                Set.of("ObservabilityConfig", "MetricsConfig")));
    }

    private void register(Capability capability) {
        byId.put(capability.id(), capability);
        // The trailing group absorbs plurals ("redirects", "short links") and gerunds
        // ("rate limiting", "tracking") without reopening the substring problem: it matches only
        // "", "s", "es" or "ing" — never "er", so "shorten" still does not match "shortener".
        patternsById.put(capability.id(), capability.keywords().stream()
                .map(keyword -> Pattern.compile("\\b" + Pattern.quote(keyword) + "(?:e?s|ing)?\\b",
                        Pattern.CASE_INSENSITIVE))
                .toList());
    }

    public Optional<Capability> byId(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public List<Capability> all() {
        return List.copyOf(byId.values());
    }

    /**
     * Detects capabilities mentioned in free text. Matching is deliberately conservative: a
     * capability is only detected on an explicit keyword, never inferred from vague language.
     * Vagueness is the {@link RequirementNormalizer}'s concern, and conflating the two would let the
     * planner invent work nobody asked for.
     *
     * <p>Matching is on word boundaries, not substrings. Substring matching reads "URL shortener"
     * as a request to build link creation, which turns an ambiguous requirement into a confidently
     * wrong plan — the exact failure this system exists to prevent.
     *
     * @return detected capabilities in dependency-safe order
     */
    public List<Capability> detect(String text) {
        String haystack = text == null ? "" : text.toLowerCase(Locale.ROOT);
        Set<String> hits = new LinkedHashSet<>();
        patternsById.forEach((id, patterns) -> {
            if (patterns.stream().anyMatch(pattern -> pattern.matcher(haystack).find())) {
                hits.add(id);
            }
        });
        return inDependencyOrder(hits);
    }

    /**
     * Expands a set of capability ids to include everything they transitively depend on. Used for
     * greenfield planning, where prerequisites must actually be built.
     */
    public List<Capability> withPrerequisites(java.util.Collection<String> ids) {
        Set<String> resolved = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>(ids);
        while (!pending.isEmpty()) {
            String id = pending.pop();
            if (!resolved.add(id)) {
                continue;
            }
            byId(id).ifPresent(capability -> pending.addAll(capability.dependsOn()));
        }
        return inDependencyOrder(resolved);
    }

    /**
     * The prerequisites of {@code ids} that are not themselves in {@code ids}. Used for brownfield
     * planning, where these are assumed to already exist in the codebase rather than built again.
     */
    public List<String> missingPrerequisites(java.util.Collection<String> ids) {
        Set<String> requested = Set.copyOf(ids);
        List<String> assumed = new ArrayList<>();
        for (Capability capability : withPrerequisites(requested)) {
            if (!requested.contains(capability.id())) {
                assumed.add(capability.id());
            }
        }
        return assumed;
    }

    /** Topological order over {@code dependsOn}, so prerequisites always precede dependants. */
    private List<Capability> inDependencyOrder(Set<String> ids) {
        List<Capability> ordered = new ArrayList<>();
        Set<String> placed = new LinkedHashSet<>();
        for (Capability candidate : byId.values()) {
            if (ids.contains(candidate.id())) {
                place(candidate, ids, placed, ordered);
            }
        }
        return ordered;
    }

    private void place(Capability capability, Set<String> scope, Set<String> placed,
                       List<Capability> ordered) {
        if (placed.contains(capability.id())) {
            return;
        }
        placed.add(capability.id());
        for (String dependency : capability.dependsOn()) {
            if (scope.contains(dependency)) {
                byId(dependency).ifPresent(dep -> place(dep, scope, placed, ordered));
            }
        }
        ordered.add(capability);
    }
}
