package com.agentic.orchestrator.agent;

/**
 * Blueprints for the capabilities a vague requirement resolves to: caching and observability.
 *
 * <p>These exist because "make it more reliable and faster" is the ambiguous scenario, and once a
 * human answers the clarification the re-plan expands into exactly these. A runtime that could not
 * build them would make the clarification pointless — the graph would change shape and then fail.
 */
final class PerformanceBlueprints {

    private PerformanceBlueprints() {
    }

    // ══════════════════════════════════════════════════════ caching

    static final String CACHE_CONFIG = """
            package com.example.urlshortener;

            import java.util.concurrent.ConcurrentHashMap;
            import java.util.concurrent.ConcurrentMap;
            import org.springframework.cache.CacheManager;
            import org.springframework.cache.annotation.EnableCaching;
            import org.springframework.cache.concurrent.ConcurrentMapCache;
            import org.springframework.cache.support.SimpleCacheManager;
            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;
            import java.util.List;

            /**
             * Cache-aside for the redirect hot path.
             *
             * <p>An in-process map rather than Redis, and the trade-off is stated rather than hidden:
             * each instance keeps its own copy, so behind several replicas a deleted link can still
             * resolve from another instance's cache until its entry is evicted. For short links —
             * which are overwhelmingly read, rarely changed, and harmless to serve a few seconds
             * stale — that is an acceptable trade for removing a network hop from every redirect.
             * A distributed cache becomes necessary when deletion has to be immediate.
             *
             * <p>The cache is bounded. An unbounded map keyed by user-supplied codes is a memory
             * exhaustion primitive: anyone can request nonsense codes until the heap is gone.
             */
            @Configuration
            @EnableCaching
            public class CacheConfig {

                public static final String LINKS = "links";
                private static final int MAX_ENTRIES = 10_000;

                @Bean
                public CacheManager cacheManager() {
                    SimpleCacheManager manager = new SimpleCacheManager();
                    manager.setCaches(List.of(new ConcurrentMapCache(
                            LINKS, new BoundedMap<>(MAX_ENTRIES), false)));
                    return manager;
                }

                /**
                 * Crude bounding: clear everything on overflow rather than evict least-recently-used.
                 *
                 * <p>An LRU needs access ordering and synchronisation on every read, which is a cost
                 * paid on the hot path to optimise an event that should be rare. Clearing is a cliff,
                 * but it is a cliff that costs one round of cache misses, and it cannot leak.
                 */
                static class BoundedMap<K, V> extends ConcurrentHashMap<K, V> {

                    private final int maxEntries;

                    BoundedMap(int maxEntries) {
                        this.maxEntries = maxEntries;
                    }

                    @Override
                    public V put(K key, V value) {
                        if (size() >= maxEntries) {
                            clear();
                        }
                        return super.put(key, value);
                    }

                    @Override
                    public V putIfAbsent(K key, V value) {
                        if (size() >= maxEntries) {
                            clear();
                        }
                        return super.putIfAbsent(key, value);
                    }

                    ConcurrentMap<K, V> self() {
                        return this;
                    }
                }
            }
            """;

    /**
     * The cached resolve path.
     *
     * <p>Derived from the redirect-capable service rather than written separately, so caching cannot
     * silently drift from the resolution rules it is caching. Only the annotations differ.
     */
    static final String LINK_SERVICE_CACHED = UrlShortenerBlueprints.LINK_SERVICE_WITH_RESOLVE
            .replace("""
                    import org.springframework.stereotype.Service;""",
                    """
                    import org.springframework.cache.annotation.CacheEvict;
                    import org.springframework.cache.annotation.Cacheable;
                    import org.springframework.stereotype.Service;""")
            .replace("""
                            public Optional<String> resolve(String code) {""",
                    """
                            /*
                             * Cached on the code, which is immutable for the life of a link.
                             *
                             * The unless clause matters: caching a miss would let anyone fill the
                             * cache with codes that do not exist, and would keep serving "not found"
                             * for a code created moments later.
                             *
                             * It tests for null, not for an empty Optional. Spring unwraps an
                             * Optional return before binding #result, so an empty one arrives as
                             * null — and "#result.isEmpty()" then throws a SpEL evaluation error on
                             * exactly the miss path it was meant to protect.
                             */
                            @Cacheable(cacheNames = CacheConfig.LINKS, unless = "#result == null")
                            public Optional<String> resolve(String code) {""")
            .replace("""
                            public boolean delete(String code) {""",
                    """
                            // Deletion must evict, or a deleted link keeps redirecting from cache —
                            // which for a takedown request is the difference between compliance and
                            // a serious problem.
                            @CacheEvict(cacheNames = CacheConfig.LINKS, key = "#code")
                            public boolean delete(String code) {""");

    static final String CACHE_TEST = """
            package com.example.urlshortener;

            import static org.assertj.core.api.Assertions.assertThat;

            import org.junit.jupiter.api.Test;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.boot.test.context.SpringBootTest;
            import org.springframework.cache.CacheManager;

            @SpringBootTest
            class CachingTest {

                @Autowired CacheManager caches;
                @Autowired LinkService links;

                @Test
                void theLinkCacheExists() {
                    assertThat(caches.getCache(CacheConfig.LINKS)).isNotNull();
                }

                @Test
                void resolvingPopulatesTheCache() {
                    Link link = links.create("https://example.com/cached", null, null);

                    links.resolve(link.code());

                    assertThat(caches.getCache(CacheConfig.LINKS)
                            .get(link.code())).isNotNull();
                }

                @Test
                void deletingEvictsTheEntry() {
                    Link link = links.create("https://example.com/evicted", null, null);
                    links.resolve(link.code());

                    links.delete(link.code());

                    // A deleted link that keeps redirecting from cache is, for a takedown request,
                    // the difference between compliance and a serious problem.
                    assertThat(caches.getCache(CacheConfig.LINKS).get(link.code())).isNull();
                    assertThat(links.resolve(link.code())).isEmpty();
                }

                @Test
                void missesAreNotCached() {
                    links.resolve("nosuch");

                    // Caching misses lets anyone fill the cache with codes that do not exist.
                    assertThat(caches.getCache(CacheConfig.LINKS).get("nosuch")).isNull();
                }

                @Test
                void theCacheIsBounded() {
                    CacheConfig.BoundedMap<String, String> map = new CacheConfig.BoundedMap<>(3);
                    for (int i = 0; i < 10; i++) {
                        map.put("k" + i, "v");
                    }

                    // An unbounded map keyed by user-supplied codes is a memory-exhaustion primitive.
                    assertThat(map.size()).isLessThanOrEqualTo(3);
                }
            }
            """;

    // ══════════════════════════════════════════════════════ observability

    static final String OBSERVABILITY_CONFIG = """
            package com.example.urlshortener;

            import java.time.Duration;
            import java.time.Instant;
            import java.util.Map;
            import java.util.concurrent.atomic.AtomicLong;
            import org.springframework.context.annotation.Configuration;

            /**
             * Counters and a health view for the service.
             *
             * <p>Hand-rolled rather than Micrometer, deliberately: the point of this capability is
             * that "reliable" was clarified to mean "we can see when it is not", and four counters
             * plus an uptime reading answer that without adding a metrics stack and its registry
             * configuration to a service this small. Swapping in Micrometer later changes only this
             * class.
             */
            @Configuration
            public class ObservabilityConfig {

                private final Instant startedAt = Instant.now();
                private final AtomicLong redirectsServed = new AtomicLong();
                private final AtomicLong redirectsMissed = new AtomicLong();
                private final AtomicLong linksCreated = new AtomicLong();
                private final AtomicLong errors = new AtomicLong();

                public void recordRedirect(boolean found) {
                    if (found) {
                        redirectsServed.incrementAndGet();
                    } else {
                        redirectsMissed.incrementAndGet();
                    }
                }

                public void recordLinkCreated() {
                    linksCreated.incrementAndGet();
                }

                public void recordError() {
                    errors.incrementAndGet();
                }

                public Map<String, Object> snapshot() {
                    long served = redirectsServed.get();
                    long missed = redirectsMissed.get();
                    long total = served + missed;

                    return Map.of(
                            "uptimeSeconds", Duration.between(startedAt, Instant.now()).toSeconds(),
                            "redirectsServed", served,
                            "redirectsMissed", missed,
                            // Null rather than 0 with no traffic: a hit rate of "nothing has been
                            // asked for" is not a hit rate of zero, and a dashboard that cannot tell
                            // them apart shows an outage for an idle service.
                            "redirectHitRatePct", total == 0 ? "no data"
                                    : Math.round(served * 1000.0 / total) / 10.0,
                            "linksCreated", linksCreated.get(),
                            "errors", errors.get());
                }
            }
            """;

    static final String METRICS_CONFIG = """
            package com.example.urlshortener;

            import java.util.Map;
            import org.springframework.http.ResponseEntity;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RequestMapping;
            import org.springframework.web.bind.annotation.RestController;

            /** Exposes the counters and a liveness answer. */
            @RestController
            @RequestMapping("/internal")
            public class MetricsConfig {

                private final ObservabilityConfig observability;

                public MetricsConfig(ObservabilityConfig observability) {
                    this.observability = observability;
                }

                @GetMapping("/metrics")
                public Map<String, Object> metrics() {
                    return observability.snapshot();
                }

                /**
                 * Liveness only — it does not touch the database.
                 *
                 * <p>A health check that queries the database will fail the instance during a brief
                 * database blip, and an orchestrator will then restart a process that was working
                 * fine, turning a transient dependency problem into an outage of its own.
                 */
                @GetMapping("/health")
                public ResponseEntity<Map<String, String>> health() {
                    return ResponseEntity.ok(Map.of("status", "UP"));
                }
            }
            """;

    static final String OBSERVABILITY_TEST = """
            package com.example.urlshortener;

            import static org.assertj.core.api.Assertions.assertThat;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
            import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
            import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

            import org.junit.jupiter.api.Test;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
            import org.springframework.boot.test.context.SpringBootTest;
            import org.springframework.test.web.servlet.MockMvc;

            @SpringBootTest
            @AutoConfigureMockMvc
            class ObservabilityTest {

                @Autowired MockMvc mvc;

                @Test
                void healthIsUp() throws Exception {
                    mvc.perform(get("/internal/health"))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.status").value("UP"));
                }

                @Test
                void metricsAreExposed() throws Exception {
                    mvc.perform(get("/internal/metrics"))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.uptimeSeconds").exists())
                            .andExpect(jsonPath("$.redirectsServed").exists());
                }

                @Test
                void countersMove() {
                    ObservabilityConfig observability = new ObservabilityConfig();

                    observability.recordRedirect(true);
                    observability.recordRedirect(false);
                    observability.recordLinkCreated();

                    assertThat(observability.snapshot().get("redirectsServed")).isEqualTo(1L);
                    assertThat(observability.snapshot().get("redirectsMissed")).isEqualTo(1L);
                    assertThat(observability.snapshot().get("redirectHitRatePct")).isEqualTo(50.0);
                }

                @Test
                void anIdleServiceReportsNoDataRatherThanZeroPercent() {
                    ObservabilityConfig observability = new ObservabilityConfig();

                    // A hit rate of 0% for a service nobody has called reads as an outage.
                    assertThat(observability.snapshot().get("redirectHitRatePct")).isEqualTo("no data");
                }
            }
            """;
}
