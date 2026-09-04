package com.agentic.orchestrator.agent;

/**
 * Blueprints for the brownfield capabilities: click analytics and rate limiting.
 *
 * <p>Separate from {@link UrlShortenerBlueprints} because these are <em>changes to an existing
 * service</em>, not a build from nothing. They assume {@code Link}, {@code LinkService} and
 * {@code RedirectController} already exist in the workspace — which is exactly why a brownfield run
 * is seeded from a baseline run rather than starting empty.
 *
 * <p>{@link #REDIRECT_CONTROLLER_WITH_ANALYTICS} rewrites a file the greenfield run wrote. That
 * overlap is the point: the planner detects it as a write conflict and serialises the two nodes, and
 * the rewrite is what makes the redirect hot path record clicks.
 */
final class BrownfieldBlueprints {

    private BrownfieldBlueprints() {
    }

    // ══════════════════════════════════════════════════════ click analytics

    static final String MIGRATION_CLICKS = """
            -- Click events are append-only: one row per redirect served.
            --
            -- Deliberately not a counter column on `links`. A counter forces every redirect to take a
            -- write lock on the row it is reading, which serialises the hot path under exactly the
            -- traffic that makes analytics worth having. Append-only rows aggregate on read instead.
            CREATE TABLE click_event (
                id           BIGINT AUTO_INCREMENT PRIMARY KEY,
                code         VARCHAR(16)  NOT NULL,
                occurred_at  TIMESTAMP    NOT NULL,
                referer      VARCHAR(512),
                user_agent   VARCHAR(512)
            );

            -- Every analytics query filters by code, usually with a time bound.
            CREATE INDEX idx_click_code_time ON click_event (code, occurred_at);
            """;

    static final String CLICK_EVENT = """
            package com.example.urlshortener;

            import java.time.Instant;

            /** One redirect served. Immutable: an event that already happened cannot change. */
            public record ClickEvent(Long id, String code, Instant occurredAt, String referer,
                                     String userAgent) {

                public static ClickEvent of(String code, String referer, String userAgent) {
                    return new ClickEvent(null, code, Instant.now(), truncate(referer),
                            truncate(userAgent));
                }

                /**
                 * Headers are attacker-controlled and unbounded. Truncating here rather than letting
                 * the insert fail keeps a hostile User-Agent from turning every redirect into an
                 * error, which would be a denial of service via a header nobody validates.
                 */
                private static String truncate(String value) {
                    if (value == null) {
                        return null;
                    }
                    return value.length() <= 512 ? value : value.substring(0, 512);
                }
            }
            """;

    static final String CLICK_EVENT_REPOSITORY = """
            package com.example.urlshortener;

            import java.sql.Timestamp;
            import java.time.Instant;
            import java.util.List;
            import org.springframework.jdbc.core.JdbcTemplate;
            import org.springframework.stereotype.Repository;

            @Repository
            public class ClickEventRepository {

                private final JdbcTemplate jdbc;

                public ClickEventRepository(JdbcTemplate jdbc) {
                    this.jdbc = jdbc;
                }

                public void record(ClickEvent event) {
                    jdbc.update(
                            "INSERT INTO click_event (code, occurred_at, referer, user_agent) "
                                    + "VALUES (?, ?, ?, ?)",
                            event.code(), Timestamp.from(event.occurredAt()), event.referer(),
                            event.userAgent());
                }

                public long countFor(String code) {
                    Long count = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM click_event WHERE code = ?", Long.class, code);
                    return count == null ? 0 : count;
                }

                public long countSince(String code, Instant since) {
                    Long count = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM click_event WHERE code = ? AND occurred_at >= ?",
                            Long.class, code, Timestamp.from(since));
                    return count == null ? 0 : count;
                }

                public Instant lastClick(String code) {
                    List<Timestamp> found = jdbc.query(
                            "SELECT MAX(occurred_at) FROM click_event WHERE code = ?",
                            (rs, row) -> rs.getTimestamp(1), code);
                    return found.isEmpty() || found.get(0) == null
                            ? null : found.get(0).toInstant();
                }

                /** Top codes by click count. Bounded by the caller; an unbounded top-N is a scan. */
                public List<ClickCount> topCodes(int limit) {
                    return jdbc.query(
                            "SELECT code, COUNT(*) AS hits FROM click_event GROUP BY code "
                                    + "ORDER BY hits DESC LIMIT ?",
                            (rs, row) -> new ClickCount(rs.getString("code"), rs.getLong("hits")),
                            limit);
                }

                public record ClickCount(String code, long clicks) {
                }
            }
            """;

    static final String ANALYTICS_SERVICE = """
            package com.example.urlshortener;

            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;
            import org.springframework.scheduling.annotation.Async;
            import org.springframework.stereotype.Service;

            /**
             * Records clicks off the redirect hot path.
             *
             * <p>{@code @Async} is the whole point. A redirect must do one lookup and return; making
             * the visitor wait for an analytics insert adds a database round-trip to every click, and
             * it is a write, so it contends with every other click at exactly peak traffic.
             *
             * <p>Failures are swallowed after logging, on purpose. Analytics is not worth failing a
             * redirect for — a visitor who cannot reach their link because a stats table is full has
             * been failed by a feature that was supposed to be invisible to them.
             */
            @Service
            public class AnalyticsService {

                private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);

                private final ClickEventRepository clicks;

                public AnalyticsService(ClickEventRepository clicks) {
                    this.clicks = clicks;
                }

                @Async
                public void record(String code, String referer, String userAgent) {
                    try {
                        clicks.record(ClickEvent.of(code, referer, userAgent));
                    } catch (RuntimeException failed) {
                        log.warn("Could not record click for {}: {}", code, failed.getMessage());
                    }
                }

                // ── reads ─────────────────────────────────────────────────────────────────────
                // Queries live behind the service too, so the controller never touches the
                // repository directly. That keeps the write path's async policy and the read path's
                // aggregation in one place, where a change to either can see the other.

                public long totalClicks(String code) {
                    return clicks.countFor(code);
                }

                public long clicksSince(String code, java.time.Instant since) {
                    return clicks.countSince(code, since);
                }

                public java.time.Instant lastClick(String code) {
                    return clicks.lastClick(code);
                }

                public java.util.List<ClickEventRepository.ClickCount> topCodes(int limit) {
                    return clicks.topCodes(limit);
                }
            }
            """;

    static final String RATE_LIMIT_CONFIG = """
            package com.example.urlshortener;

            import org.springframework.beans.factory.annotation.Value;
            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;
            import org.springframework.scheduling.annotation.EnableScheduling;
            import org.springframework.scheduling.annotation.Scheduled;

            /**
             * Wiring and housekeeping for the rate limiter.
             *
             * <p>The limiter itself is a plain class taking two numbers, so it can be unit-tested
             * without a Spring context — a limiter whose window behaviour can only be exercised by
             * booting an application is a limiter nobody writes edge-case tests for.
             *
             * <p>{@code @EnableScheduling} lives here rather than on the application class so that
             * rate limiting brings its own housekeeping with it, and does not silently stop evicting
             * if the analytics capability is removed.
             */
            @Configuration
            @EnableScheduling
            public class RateLimitConfig {

                @Bean
                public RateLimiter rateLimiter(
                        @Value("${app.rate-limit.requests:60}") int requests,
                        @Value("${app.rate-limit.window-seconds:60}") long windowSeconds) {
                    return new RateLimiter(requests, windowSeconds);
                }

                /**
                 * Evicts expired windows on a schedule.
                 *
                 * <p>Without this the limiter's map grows one entry per distinct client and never
                 * shrinks — a memory leak in the component whose job is to protect the service from
                 * exhaustion, which is the sort of irony that reaches production.
                 */
                @Bean
                public RateLimitJanitor rateLimitJanitor(RateLimiter limiter) {
                    return new RateLimitJanitor(limiter);
                }

                public static class RateLimitJanitor {

                    private final RateLimiter limiter;

                    public RateLimitJanitor(RateLimiter limiter) {
                        this.limiter = limiter;
                    }

                    @Scheduled(fixedDelay = 300_000)
                    public void evict() {
                        limiter.evictExpired();
                    }
                }
            }
            """;

    static final String ANALYTICS_CONTROLLER = """
            package com.example.urlshortener;

            import java.time.Duration;
            import java.time.Instant;
            import java.util.List;
            import java.util.Map;
            import org.springframework.http.ResponseEntity;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.PathVariable;
            import org.springframework.web.bind.annotation.RequestMapping;
            import org.springframework.web.bind.annotation.RequestParam;
            import org.springframework.web.bind.annotation.RestController;

            /** Aggregate click statistics. Read-only, and never on the redirect path. */
            @RestController
            @RequestMapping("/api/v1/analytics")
            public class AnalyticsController {

                private final AnalyticsService analytics;
                private final LinkService links;

                public AnalyticsController(AnalyticsService analytics, LinkService links) {
                    this.analytics = analytics;
                    this.links = links;
                }

                @GetMapping("/links/{code}")
                public ResponseEntity<Map<String, Object>> forCode(@PathVariable String code) {
                    // 404 for a code that never existed, rather than a cheerful zero. "No clicks"
                    // and "no such link" are different answers and a caller must be able to tell.
                    if (links.find(code).isEmpty()) {
                        return ResponseEntity.notFound().build();
                    }
                    Instant dayAgo = Instant.now().minus(Duration.ofDays(1));
                    Instant weekAgo = Instant.now().minus(Duration.ofDays(7));

                    return ResponseEntity.ok(Map.of(
                            "code", code,
                            "totalClicks", analytics.totalClicks(code),
                            "clicksLast24h", analytics.clicksSince(code, dayAgo),
                            "clicksLast7d", analytics.clicksSince(code, weekAgo),
                            "lastClickAt", String.valueOf(analytics.lastClick(code))));
                }

                @GetMapping("/top")
                public List<ClickEventRepository.ClickCount> top(
                        @RequestParam(defaultValue = "10") int limit) {
                    return analytics.topCodes(Math.min(Math.max(limit, 1), 100));
                }
            }
            """;

    /** Rewrites the greenfield redirect controller so the hot path records a click. */
    static final String REDIRECT_CONTROLLER_WITH_ANALYTICS = """
            package com.example.urlshortener;

            import java.net.URI;
            import org.springframework.http.HttpHeaders;
            import org.springframework.http.HttpStatus;
            import org.springframework.http.ResponseEntity;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.PathVariable;
            import org.springframework.web.bind.annotation.RequestHeader;
            import org.springframework.web.bind.annotation.RestController;

            /**
             * The redirect hot path, now recording clicks.
             *
             * <p>The recording call is asynchronous and happens <em>after</em> the lookup succeeds.
             * A click on a code that does not resolve is not a click on anything, and counting it
             * would let anyone inflate another link's statistics by guessing codes.
             */
            @RestController
            public class RedirectController {

                private final LinkService links;
                private final AnalyticsService analytics;

                public RedirectController(LinkService links, AnalyticsService analytics) {
                    this.links = links;
                    this.analytics = analytics;
                }

                @GetMapping("/{code}")
                public ResponseEntity<Void> redirect(
                        @PathVariable String code,
                        @RequestHeader(value = "Referer", required = false) String referer,
                        @RequestHeader(value = "User-Agent", required = false) String userAgent) {

                    if (!CodeGenerator.isValid(code)) {
                        return ResponseEntity.notFound().build();
                    }
                    return links.resolve(code)
                            .map(target -> {
                                analytics.record(code, referer, userAgent);
                                return ResponseEntity.status(HttpStatus.FOUND)
                                        .header(HttpHeaders.LOCATION, URI.create(target).toString())
                                        .<Void>build();
                            })
                            .orElseGet(() -> ResponseEntity.notFound().build());
                }
            }
            """;

    /** Enables {@code @Async} so ClickRecorder actually runs off the request thread. */
    static final String ASYNC_CONFIG = """
            package com.example.urlshortener;

            import java.util.concurrent.Executor;
            import java.util.concurrent.ThreadPoolExecutor;
            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;
            import org.springframework.scheduling.annotation.EnableAsync;
            import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

            @Configuration
            @EnableAsync
            public class AsyncConfig {

                /**
                 * A bounded queue with CallerRunsPolicy, not an unbounded one.
                 *
                 * <p>Unbounded is the default and it is the wrong default here: under a click flood
                 * the queue grows until the heap is gone, and the service dies of analytics. Bounded
                 * plus caller-runs means the worst case is that clicks record synchronously and
                 * redirects slow down — degraded, which is survivable, rather than dead.
                 */
                @Bean("clickExecutor")
                public Executor clickExecutor() {
                    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
                    executor.setCorePoolSize(2);
                    executor.setMaxPoolSize(4);
                    executor.setQueueCapacity(500);
                    executor.setThreadNamePrefix("click-");
                    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
                    executor.initialize();
                    return executor;
                }
            }
            """;

    // ══════════════════════════════════════════════════════ rate limiting

    static final String RATE_LIMITER = """
            package com.example.urlshortener;

            import java.time.Duration;
            import java.time.Instant;
            import java.util.Map;
            import java.util.concurrent.ConcurrentHashMap;
            /**
             * Fixed-window rate limiter, per client key.
             *
             * <p>Fixed window rather than a token bucket, and the trade-off is stated rather than
             * hidden: a client can send a full window's allowance at the end of one window and again
             * at the start of the next, so the true worst case is twice the configured rate over a
             * window boundary. That is acceptable for abuse protection and it is a fraction of the
             * code, which matters more than theoretical smoothness for a limit whose job is to stop
             * scripts rather than to shape traffic.
             *
             * <p>In-memory, so limits are per instance. Behind more than one replica this must move
             * to Redis; that is noted in the docs rather than pretended away.
             */
            public class RateLimiter {

                private final int limit;
                private final Duration window;
                private final Map<String, Window> windows = new ConcurrentHashMap<>();

                public RateLimiter(int limit, long windowSeconds) {
                    this.limit = limit;
                    this.window = Duration.ofSeconds(windowSeconds);
                }

                /** @return true if the request may proceed */
                public boolean tryAcquire(String key) {
                    Instant now = Instant.now();
                    Window current = windows.compute(key, (k, existing) ->
                            existing == null || existing.isExpired(now, window)
                                    ? new Window(now, 1)
                                    : existing.increment());
                    return current.count() <= limit;
                }

                public int remaining(String key) {
                    Window current = windows.get(key);
                    return current == null ? limit : Math.max(0, limit - current.count());
                }

                public int limit() {
                    return limit;
                }

                /**
                 * Bounded cleanup, called on a schedule rather than on every request.
                 *
                 * <p>Without it the map grows one entry per distinct client forever, which is a
                 * memory leak that a rate limiter — of all things — should not have.
                 */
                public int evictExpired() {
                    Instant now = Instant.now();
                    int before = windows.size();
                    windows.entrySet().removeIf(entry -> entry.getValue().isExpired(now, window));
                    return before - windows.size();
                }

                private record Window(Instant startedAt, int count) {

                    boolean isExpired(Instant now, Duration window) {
                        return startedAt.plus(window).isBefore(now);
                    }

                    Window increment() {
                        return new Window(startedAt, count + 1);
                    }
                }
            }
            """;

    static final String RATE_LIMIT_FILTER = """
            package com.example.urlshortener;

            import jakarta.servlet.FilterChain;
            import jakarta.servlet.ServletException;
            import jakarta.servlet.http.HttpServletRequest;
            import jakarta.servlet.http.HttpServletResponse;
            import java.io.IOException;
            import org.springframework.core.annotation.Order;
            import org.springframework.stereotype.Component;
            import org.springframework.web.filter.OncePerRequestFilter;

            /**
             * Applies the rate limit to link creation only.
             *
             * <p>Deliberately <em>not</em> applied to redirects. A short link that stops resolving
             * because it became popular is a broken link, and rate-limiting the read path punishes
             * the visitor for the publisher's success. Creation is the expensive, abusable operation
             * — it writes rows and consumes the code space — so that is what is limited.
             */
            @Component
            @Order(1)
            public class RateLimitFilter extends OncePerRequestFilter {

                private final RateLimiter limiter;

                public RateLimitFilter(RateLimiter limiter) {
                    this.limiter = limiter;
                }

                @Override
                protected boolean shouldNotFilter(HttpServletRequest request) {
                    return !("POST".equals(request.getMethod())
                            && request.getRequestURI().startsWith("/api/v1/links"));
                }

                @Override
                protected void doFilterInternal(HttpServletRequest request,
                                                HttpServletResponse response, FilterChain chain)
                        throws ServletException, IOException {

                    String key = clientKey(request);
                    if (!limiter.tryAcquire(key)) {
                        response.setStatus(429);
                        response.setHeader("Retry-After", "60");
                        response.setHeader("X-RateLimit-Limit", String.valueOf(limiter.limit()));
                        response.setHeader("X-RateLimit-Remaining", "0");
                        response.setContentType("application/json");
                        response.getWriter().write(
                                "{\\"error\\":\\"Rate limit exceeded. Try again shortly.\\"}");
                        return;
                    }
                    response.setHeader("X-RateLimit-Limit", String.valueOf(limiter.limit()));
                    response.setHeader("X-RateLimit-Remaining",
                            String.valueOf(limiter.remaining(key)));
                    chain.doFilter(request, response);
                }

                /**
                 * X-Forwarded-For is trusted only for its first hop, and only because this service is
                 * expected to sit behind a proxy that sets it. Where there is no such proxy this
                 * header is client-controlled and trivially spoofed — noted in the docs, because a
                 * rate limiter keyed on a spoofable value is theatre.
                 */
                private String clientKey(HttpServletRequest request) {
                    String forwarded = request.getHeader("X-Forwarded-For");
                    if (forwarded != null && !forwarded.isBlank()) {
                        return forwarded.split(",")[0].trim();
                    }
                    return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
                }
            }
            """;

    // ══════════════════════════════════════════════════════ tests

    static final String CLICK_ANALYTICS_TEST = """
            package com.example.urlshortener;

            import static org.assertj.core.api.Assertions.assertThat;

            import java.time.Duration;
            import java.time.Instant;
            import org.junit.jupiter.api.Test;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.boot.test.context.SpringBootTest;

            @SpringBootTest
            class ClickAnalyticsTest {

                @Autowired ClickEventRepository clicks;

                @Test
                void recordsAndCountsClicks() {
                    String code = "an" + System.nanoTime() % 100000;

                    clicks.record(ClickEvent.of(code, "https://ref.example", "curl/8"));
                    clicks.record(ClickEvent.of(code, null, null));

                    assertThat(clicks.countFor(code)).isEqualTo(2);
                    assertThat(clicks.lastClick(code)).isNotNull();
                }

                @Test
                void countsWithinATimeWindow() {
                    String code = "tw" + System.nanoTime() % 100000;
                    clicks.record(ClickEvent.of(code, null, null));

                    assertThat(clicks.countSince(code, Instant.now().minus(Duration.ofMinutes(5))))
                            .isEqualTo(1);
                    assertThat(clicks.countSince(code, Instant.now().plus(Duration.ofMinutes(5))))
                            .isZero();
                }

                @Test
                void overlongHeadersAreTruncatedRatherThanRejected() {
                    // A hostile User-Agent must not turn a redirect into a 500.
                    ClickEvent event = ClickEvent.of("abc", "r".repeat(900), "u".repeat(900));

                    assertThat(event.referer()).hasSize(512);
                    assertThat(event.userAgent()).hasSize(512);
                }

                @Test
                void aCodeWithNoClicksCountsZero() {
                    assertThat(clicks.countFor("never-clicked")).isZero();
                    assertThat(clicks.lastClick("never-clicked")).isNull();
                }
            }
            """;

    static final String RATE_LIMIT_TEST = """
            package com.example.urlshortener;

            import static org.assertj.core.api.Assertions.assertThat;

            import org.junit.jupiter.api.Test;

            class RateLimiterTest {

                @Test
                void allowsUpToTheLimitThenRefuses() {
                    RateLimiter limiter = new RateLimiter(3, 60);

                    assertThat(limiter.tryAcquire("a")).isTrue();
                    assertThat(limiter.tryAcquire("a")).isTrue();
                    assertThat(limiter.tryAcquire("a")).isTrue();
                    assertThat(limiter.tryAcquire("a")).isFalse();
                }

                @Test
                void limitsArePerClient() {
                    RateLimiter limiter = new RateLimiter(1, 60);

                    assertThat(limiter.tryAcquire("a")).isTrue();
                    assertThat(limiter.tryAcquire("a")).isFalse();
                    // One noisy client must not consume everyone else's allowance.
                    assertThat(limiter.tryAcquire("b")).isTrue();
                }

                @Test
                void theWindowResets() throws InterruptedException {
                    RateLimiter limiter = new RateLimiter(1, 1);

                    assertThat(limiter.tryAcquire("a")).isTrue();
                    assertThat(limiter.tryAcquire("a")).isFalse();

                    Thread.sleep(1100);
                    assertThat(limiter.tryAcquire("a")).isTrue();
                }

                @Test
                void remainingNeverGoesNegative() {
                    RateLimiter limiter = new RateLimiter(2, 60);
                    for (int i = 0; i < 10; i++) {
                        limiter.tryAcquire("a");
                    }
                    assertThat(limiter.remaining("a")).isZero();
                }

                @Test
                void expiredWindowsAreEvicted() {
                    RateLimiter limiter = new RateLimiter(5, 0);
                    limiter.tryAcquire("a");
                    limiter.tryAcquire("b");

                    // Without eviction the map grows one entry per client forever — a memory leak in
                    // the component whose job is to protect the service.
                    assertThat(limiter.evictExpired()).isPositive();
                }
            }
            """;

    static final String ANALYTICS_INTEGRATION_TEST = """
            package com.example.urlshortener;

            import static org.assertj.core.api.Assertions.assertThat;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
            import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
            import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
            import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

            import com.fasterxml.jackson.databind.JsonNode;
            import com.fasterxml.jackson.databind.ObjectMapper;
            import org.junit.jupiter.api.Test;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
            import org.springframework.boot.test.context.SpringBootTest;
            import org.springframework.http.MediaType;
            import org.springframework.test.web.servlet.MockMvc;

            @SpringBootTest
            @AutoConfigureMockMvc
            class AnalyticsApiIntegrationTest {

                @Autowired MockMvc mvc;
                @Autowired ObjectMapper json;
                @Autowired ClickEventRepository clicks;

                private String createLink() throws Exception {
                    String body = mvc.perform(post("/api/v1/links")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\\"url\\":\\"https://example.com/analytics\\"}"))
                            .andExpect(status().isCreated())
                            .andReturn().getResponse().getContentAsString();
                    JsonNode node = json.readTree(body);
                    return node.get("code").asText();
                }

                @Test
                void statsForAKnownCodeStartAtZero() throws Exception {
                    String code = createLink();

                    mvc.perform(get("/api/v1/analytics/links/" + code))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.totalClicks").value(0));
                }

                @Test
                void statsForAnUnknownCodeAre404() throws Exception {
                    // "No clicks" and "no such link" are different answers.
                    mvc.perform(get("/api/v1/analytics/links/zzzzzzz"))
                            .andExpect(status().isNotFound());
                }

                @Test
                void creationCarriesRateLimitHeaders() throws Exception {
                    mvc.perform(post("/api/v1/links")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\\"url\\":\\"https://example.com/rl\\"}"))
                            .andExpect(status().isCreated())
                            .andExpect(header().exists("X-RateLimit-Limit"));
                }

                @Test
                void redirectsAreNotRateLimited() throws Exception {
                    String code = createLink();

                    // A popular link must keep resolving. Limiting reads punishes the visitor for
                    // the publisher's success.
                    for (int i = 0; i < 5; i++) {
                        mvc.perform(get("/" + code)).andExpect(status().isFound());
                    }
                }

                @Test
                void aRedirectIsRecordedAsAClick() throws Exception {
                    String code = createLink();

                    mvc.perform(get("/" + code)).andExpect(status().isFound());

                    // Recording is asynchronous, so poll rather than assert immediately. Polling
                    // with a deadline is the honest way to test an async side effect: a bare sleep
                    // is either flaky or slow, and usually both.
                    long deadline = System.currentTimeMillis() + 5000;
                    long recorded = 0;
                    while (System.currentTimeMillis() < deadline) {
                        recorded = clicks.countFor(code);
                        if (recorded > 0) {
                            break;
                        }
                        Thread.sleep(50);
                    }

                    // This assertion exists because the redirect controller is written by one node
                    // and rewritten by another. When the second node re-emitted the pre-analytics
                    // version, everything still compiled and every other test still passed — click
                    // recording had simply vanished. This is the test that notices.
                    assertThat(recorded).isPositive();
                }

                @Test
                void topCodesIsBounded() throws Exception {
                    mvc.perform(get("/api/v1/analytics/top?limit=500"))
                            .andExpect(status().isOk());
                }
            }
            """;

    static final String APPLICATION_PROPERTIES_WITH_LIMITS = """
            spring.application.name=url-shortener
            server.port=8081

            spring.datasource.url=jdbc:h2:mem:urlshortener;DB_CLOSE_DELAY=-1
            spring.datasource.driver-class-name=org.h2.Driver
            spring.datasource.username=sa
            spring.datasource.password=

            spring.flyway.enabled=true
            app.base-url=http://localhost:8081

            # Rate limiting. Generous by default: the purpose is to stop scripts, not to shape
            # legitimate traffic, and a limit tight enough to inconvenience real users gets removed.
            app.rate-limit.requests=60
            app.rate-limit.window-seconds=60
            """;
}
