package com.agentic.orchestrator.agent;

/**
 * The source the deterministic runtime emits.
 *
 * <p>Held apart from the runtime that selects between them so that the selection logic stays
 * readable. These are ordinary strings, and the honesty note bears repeating: this code was written
 * in advance, not synthesised. What the orchestrator genuinely does is decide when each piece is
 * written, verify it compiles and passes tests, and refuse it if not.
 */
final class UrlShortenerBlueprints {

    private UrlShortenerBlueprints() {
    }

    static final String PACKAGE_DIR = "src/main/java/com/example/urlshortener/";
    static final String TEST_DIR = "src/test/java/com/example/urlshortener/";

    static final String POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>3.3.5</version>
                    <relativePath/>
                </parent>
                <groupId>com.example</groupId>
                <artifactId>urlshortener</artifactId>
                <version>0.1.0-SNAPSHOT</version>
                <name>URL Shortener</name>
                <properties>
                    <java.version>21</java.version>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                </properties>
                <dependencies>
                    <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-web</artifactId>
                    </dependency>
                    <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-jdbc</artifactId>
                    </dependency>
                    <dependency>
                        <groupId>org.flywaydb</groupId>
                        <artifactId>flyway-core</artifactId>
                    </dependency>
                    <dependency>
                        <groupId>com.h2database</groupId>
                        <artifactId>h2</artifactId>
                        <scope>runtime</scope>
                    </dependency>
                    <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-test</artifactId>
                        <scope>test</scope>
                    </dependency>
                </dependencies>
                <build>
                    <plugins>
                        <plugin>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-maven-plugin</artifactId>
                        </plugin>
                    </plugins>
                </build>
            </project>
            """;

    static final String APPLICATION = """
            package com.example.urlshortener;

            import org.springframework.boot.SpringApplication;
            import org.springframework.boot.autoconfigure.SpringBootApplication;

            @SpringBootApplication
            public class UrlShortenerApplication {

                public static void main(String[] args) {
                    SpringApplication.run(UrlShortenerApplication.class, args);
                }
            }
            """;

    static final String APPLICATION_PROPERTIES = """
            spring.application.name=url-shortener
            server.port=8090

            spring.datasource.url=jdbc:h2:mem:urlshortener;DB_CLOSE_DELAY=-1
            spring.datasource.driver-class-name=org.h2.Driver
            spring.datasource.username=sa
            spring.datasource.password=

            spring.flyway.enabled=true
            app.base-url=http://localhost:8090
            """;

    static final String GITIGNORE = """
            target/
            *.class
            .idea/
            *.iml
            """;

    static final String MIGRATION_LINKS = """
            -- Short links.
            --
            -- code is the primary key rather than a surrogate id: it is the natural key, it is what
            -- every redirect looks up by, and making it the key removes a second index from the
            -- hottest query in the system.
            CREATE TABLE links (
                code           VARCHAR(16)  PRIMARY KEY,
                target_url     VARCHAR(2048) NOT NULL,
                created_at     TIMESTAMP     NOT NULL,
                expires_at     TIMESTAMP,
                active         BOOLEAN       NOT NULL DEFAULT TRUE
            );

            CREATE INDEX idx_links_active ON links (active);
            """;

    static final String LINK = """
            package com.example.urlshortener;

            import java.time.Instant;

            /**
             * A short link.
             *
             * @param expiresAt null means the link does not expire
             */
            public record Link(String code, String targetUrl, Instant createdAt, Instant expiresAt,
                               boolean active) {

                public boolean isExpired(Instant now) {
                    return expiresAt != null && now.isAfter(expiresAt);
                }

                public boolean isUsable(Instant now) {
                    return active && !isExpired(now);
                }
            }
            """;

    static final String CODE_GENERATOR = """
            package com.example.urlshortener;

            import java.security.SecureRandom;
            import org.springframework.beans.factory.annotation.Value;
            import org.springframework.stereotype.Component;

            /**
             * Generates short codes.
             *
             * <p>Random rather than sequential. A sequential counter encoded in Base62 makes every
             * link in the system enumerable from any single link, which turns a shortener into a
             * directory of everything anyone has ever shortened.
             *
             * <p>Collisions are handled by the caller retrying, not by checking here: checking and
             * then inserting is a race, whereas letting the unique constraint reject a duplicate is
             * not.
             */
            @Component
            public class CodeGenerator {

                private static final String ALPHABET =
                        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

                private final SecureRandom random = new SecureRandom();
                private final int length;

                public CodeGenerator(@Value("${app.code-length:7}") int length) {
                    if (length < 4 || length > 16) {
                        throw new IllegalArgumentException("Code length must be between 4 and 16");
                    }
                    this.length = length;
                }

                public String generate() {
                    StringBuilder code = new StringBuilder(length);
                    for (int index = 0; index < length; index++) {
                        code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
                    }
                    return code.toString();
                }

                public int length() {
                    return length;
                }

                public static boolean isValid(String code) {
                    if (code == null || code.isEmpty() || code.length() > 16) {
                        return false;
                    }
                    for (int index = 0; index < code.length(); index++) {
                        if (ALPHABET.indexOf(code.charAt(index)) < 0) {
                            return false;
                        }
                    }
                    return true;
                }
            }
            """;

    static final String LINK_REPOSITORY = """
            package com.example.urlshortener;

            import java.sql.Timestamp;
            import java.time.Instant;
            import java.util.List;
            import java.util.Optional;
            import org.springframework.dao.DuplicateKeyException;
            import org.springframework.jdbc.core.JdbcTemplate;
            import org.springframework.jdbc.core.RowMapper;
            import org.springframework.stereotype.Repository;

            @Repository
            public class LinkRepository {

                private static final RowMapper<Link> MAPPER = (rs, row) -> new Link(
                        rs.getString("code"),
                        rs.getString("target_url"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("expires_at") == null
                                ? null
                                : rs.getTimestamp("expires_at").toInstant(),
                        rs.getBoolean("active"));

                private final JdbcTemplate jdbc;

                public LinkRepository(JdbcTemplate jdbc) {
                    this.jdbc = jdbc;
                }

                /**
                 * @return false if the code is already taken, letting the caller pick another
                 */
                public boolean insert(Link link) {
                    try {
                        jdbc.update(
                                "INSERT INTO links (code, target_url, created_at, expires_at, active)"
                                        + " VALUES (?, ?, ?, ?, ?)",
                                link.code(), link.targetUrl(), Timestamp.from(link.createdAt()),
                                link.expiresAt() == null ? null : Timestamp.from(link.expiresAt()),
                                link.active());
                        return true;
                    } catch (DuplicateKeyException collision) {
                        return false;
                    }
                }

                public Optional<Link> findByCode(String code) {
                    List<Link> found = jdbc.query(
                            "SELECT * FROM links WHERE code = ?", MAPPER, code);
                    return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
                }

                public boolean deactivate(String code) {
                    return jdbc.update("UPDATE links SET active = FALSE WHERE code = ?", code) > 0;
                }

                public long countActive(Instant now) {
                    Long count = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM links WHERE active = TRUE", Long.class);
                    return count == null ? 0 : count;
                }
            }
            """;

    /** Version written by IMPL_LINK_CREATION: create, look up, delete. */
    static final String LINK_SERVICE_BASE = """
            package com.example.urlshortener;

            import java.net.URI;
            import java.time.Instant;
            import java.util.Optional;
            import org.springframework.stereotype.Service;

            @Service
            public class LinkService {

                /** Bounded so a pathological run of collisions fails loudly instead of spinning. */
                private static final int MAX_CODE_ATTEMPTS = 5;

                private final LinkRepository links;
                private final CodeGenerator codes;

                public LinkService(LinkRepository links, CodeGenerator codes) {
                    this.links = links;
                    this.codes = codes;
                }

                public Link create(String targetUrl, String requestedAlias, Instant expiresAt) {
                    String validated = validateTarget(targetUrl);

                    if (requestedAlias != null && !requestedAlias.isBlank()) {
                        if (!CodeGenerator.isValid(requestedAlias)) {
                            throw new IllegalArgumentException("Alias contains unsupported characters");
                        }
                        Link link = new Link(requestedAlias, validated, Instant.now(), expiresAt, true);
                        if (!links.insert(link)) {
                            throw new IllegalStateException("Alias is already taken");
                        }
                        return link;
                    }

                    for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
                        Link link = new Link(codes.generate(), validated, Instant.now(), expiresAt, true);
                        if (links.insert(link)) {
                            return link;
                        }
                    }
                    throw new IllegalStateException(
                            "Could not allocate a unique code after " + MAX_CODE_ATTEMPTS + " attempts");
                }

                public Optional<Link> find(String code) {
                    return links.findByCode(code);
                }

                public boolean delete(String code) {
                    return links.deactivate(code);
                }

                /**
                 * Rejects anything that is not an absolute http(s) URL.
                 *
                 * <p>Without this a shortener will happily mint links to javascript: and file: URLs,
                 * which turns it into a redirect laundering service for whoever asks.
                 */
                static String validateTarget(String targetUrl) {
                    if (targetUrl == null || targetUrl.isBlank()) {
                        throw new IllegalArgumentException("Target URL is required");
                    }
                    if (targetUrl.length() > 2048) {
                        throw new IllegalArgumentException("Target URL is too long");
                    }
                    URI uri;
                    try {
                        uri = URI.create(targetUrl.strip());
                    } catch (IllegalArgumentException malformed) {
                        throw new IllegalArgumentException("Target URL is malformed");
                    }
                    if (!uri.isAbsolute() || uri.getScheme() == null) {
                        throw new IllegalArgumentException("Target URL must be absolute");
                    }
                    String scheme = uri.getScheme().toLowerCase();
                    if (!scheme.equals("http") && !scheme.equals("https")) {
                        throw new IllegalArgumentException("Only http and https targets are allowed");
                    }
                    if (uri.getHost() == null || uri.getHost().isBlank()) {
                        throw new IllegalArgumentException("Target URL must name a host");
                    }
                    return uri.toString();
                }
            }
            """;

    /** Version written by IMPL_REDIRECT: adds resolution for the redirect hot path. */
    static final String LINK_SERVICE_WITH_RESOLVE = LINK_SERVICE_BASE.replace("""
                public Optional<Link> find(String code) {
                    return links.findByCode(code);
                }
            """, """
                public Optional<Link> find(String code) {
                    return links.findByCode(code);
                }

                /**
                 * Resolves a code for redirection.
                 *
                 * <p>Separate from {@link #find} because it applies the usability rules an expired or
                 * deactivated link must fail: a management API should still be able to see a link that
                 * a redirect must refuse to follow.
                 */
                public Optional<String> resolve(String code) {
                    return links.findByCode(code)
                            .filter(link -> link.isUsable(Instant.now()))
                            .map(Link::targetUrl);
                }
            """);

    static final String LINK_CONTROLLER = """
            package com.example.urlshortener;

            import java.time.Instant;
            import java.util.Map;
            import org.springframework.http.HttpStatus;
            import org.springframework.http.ResponseEntity;
            import org.springframework.web.bind.annotation.DeleteMapping;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.PathVariable;
            import org.springframework.web.bind.annotation.PostMapping;
            import org.springframework.web.bind.annotation.RequestBody;
            import org.springframework.web.bind.annotation.RequestMapping;
            import org.springframework.web.bind.annotation.RestController;

            @RestController
            @RequestMapping("/api/v1/links")
            public class LinkController {

                public record CreateLinkRequest(String url, String alias, Instant expiresAt) {
                }

                private final LinkService links;

                public LinkController(LinkService links) {
                    this.links = links;
                }

                @PostMapping
                public ResponseEntity<?> create(@RequestBody CreateLinkRequest request) {
                    try {
                        Link link = links.create(request.url(), request.alias(), request.expiresAt());
                        return ResponseEntity.status(HttpStatus.CREATED).body(link);
                    } catch (IllegalArgumentException invalid) {
                        return ResponseEntity.badRequest().body(Map.of("error", invalid.getMessage()));
                    } catch (IllegalStateException conflict) {
                        return ResponseEntity.status(HttpStatus.CONFLICT)
                                .body(Map.of("error", conflict.getMessage()));
                    }
                }

                @GetMapping("/{code}")
                public ResponseEntity<Link> get(@PathVariable String code) {
                    return links.find(code)
                            .map(ResponseEntity::ok)
                            .orElseGet(() -> ResponseEntity.notFound().build());
                }

                @DeleteMapping("/{code}")
                public ResponseEntity<Void> delete(@PathVariable String code) {
                    return links.delete(code)
                            ? ResponseEntity.noContent().build()
                            : ResponseEntity.notFound().build();
                }
            }
            """;

    static final String REDIRECT_CONTROLLER = """
            package com.example.urlshortener;

            import java.net.URI;
            import org.springframework.http.HttpHeaders;
            import org.springframework.http.HttpStatus;
            import org.springframework.http.ResponseEntity;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.PathVariable;
            import org.springframework.web.bind.annotation.RestController;

            /**
             * The redirect hot path. Every short link click lands here, so it does exactly one lookup
             * and nothing else.
             */
            @RestController
            public class RedirectController {

                private final LinkService links;

                public RedirectController(LinkService links) {
                    this.links = links;
                }

                @GetMapping("/{code}")
                public ResponseEntity<Void> redirect(@PathVariable String code) {
                    if (!CodeGenerator.isValid(code)) {
                        return ResponseEntity.notFound().build();
                    }
                    return links.resolve(code)
                            .map(target -> ResponseEntity.status(HttpStatus.FOUND)
                                    .header(HttpHeaders.LOCATION, URI.create(target).toString())
                                    .<Void>build())
                            .orElseGet(() -> ResponseEntity.notFound().build());
                }
            }
            """;

    static final String CODE_GENERATOR_TEST = """
            package com.example.urlshortener;

            import static org.assertj.core.api.Assertions.assertThat;
            import static org.assertj.core.api.Assertions.assertThatThrownBy;

            import java.util.HashSet;
            import java.util.Set;
            import org.junit.jupiter.api.DisplayName;
            import org.junit.jupiter.api.Test;

            class CodeGeneratorTest {

                private final CodeGenerator generator = new CodeGenerator(7);

                @Test
                void generatesCodesOfTheConfiguredLength() {
                    assertThat(generator.generate()).hasSize(7);
                }

                @Test
                @DisplayName("codes are drawn from the Base62 alphabet")
                void generatesValidCodes() {
                    for (int i = 0; i < 200; i++) {
                        assertThat(CodeGenerator.isValid(generator.generate())).isTrue();
                    }
                }

                @Test
                @DisplayName("codes do not repeat over a large sample")
                void collisionsAreRare() {
                    Set<String> seen = new HashSet<>();
                    for (int i = 0; i < 5000; i++) {
                        seen.add(generator.generate());
                    }
                    assertThat(seen).hasSize(5000);
                }

                @Test
                void rejectsUnsupportedCharacters() {
                    assertThat(CodeGenerator.isValid("abc-def")).isFalse();
                    assertThat(CodeGenerator.isValid("")).isFalse();
                    assertThat(CodeGenerator.isValid(null)).isFalse();
                    assertThat(CodeGenerator.isValid("abcdefghijklmnopq")).isFalse();
                }

                @Test
                void rejectsAbsurdLengths() {
                    assertThatThrownBy(() -> new CodeGenerator(2))
                            .isInstanceOf(IllegalArgumentException.class);
                    assertThatThrownBy(() -> new CodeGenerator(64))
                            .isInstanceOf(IllegalArgumentException.class);
                }
            }
            """;

    static final String LINK_API_TEST = """
            package com.example.urlshortener;

            import static org.assertj.core.api.Assertions.assertThat;
            import static org.assertj.core.api.Assertions.assertThatThrownBy;

            import org.junit.jupiter.api.DisplayName;
            import org.junit.jupiter.api.Test;

            class LinkServiceValidationTest {

                @Test
                void acceptsHttpAndHttps() {
                    assertThat(LinkService.validateTarget("https://example.com/a"))
                            .isEqualTo("https://example.com/a");
                    assertThat(LinkService.validateTarget("http://example.com"))
                            .isEqualTo("http://example.com");
                }

                @Test
                @DisplayName("refuses schemes that would launder a redirect")
                void rejectsDangerousSchemes() {
                    assertThatThrownBy(() -> LinkService.validateTarget("javascript:alert(1)"))
                            .isInstanceOf(IllegalArgumentException.class);
                    assertThatThrownBy(() -> LinkService.validateTarget("file:///etc/passwd"))
                            .isInstanceOf(IllegalArgumentException.class);
                    assertThatThrownBy(() -> LinkService.validateTarget("data:text/html,hi"))
                            .isInstanceOf(IllegalArgumentException.class);
                }

                @Test
                void rejectsRelativeAndBlankTargets() {
                    assertThatThrownBy(() -> LinkService.validateTarget("/just/a/path"))
                            .isInstanceOf(IllegalArgumentException.class);
                    assertThatThrownBy(() -> LinkService.validateTarget("  "))
                            .isInstanceOf(IllegalArgumentException.class);
                    assertThatThrownBy(() -> LinkService.validateTarget(null))
                            .isInstanceOf(IllegalArgumentException.class);
                }

                @Test
                void rejectsOverlongTargets() {
                    String tooLong = "https://example.com/" + "x".repeat(2100);
                    assertThatThrownBy(() -> LinkService.validateTarget(tooLong))
                            .isInstanceOf(IllegalArgumentException.class);
                }
            }
            """;

    static final String REDIRECT_TEST = """
            package com.example.urlshortener;

            import static org.assertj.core.api.Assertions.assertThat;

            import java.time.Instant;
            import java.time.temporal.ChronoUnit;
            import org.junit.jupiter.api.DisplayName;
            import org.junit.jupiter.api.Test;

            class LinkUsabilityTest {

                private static Link link(Instant expiresAt, boolean active) {
                    return new Link("abc1234", "https://example.com", Instant.now(), expiresAt, active);
                }

                @Test
                void aFreshActiveLinkIsUsable() {
                    assertThat(link(null, true).isUsable(Instant.now())).isTrue();
                }

                @Test
                @DisplayName("an expired link is not usable even while active")
                void expiredLinksAreNotUsable() {
                    Link expired = link(Instant.now().minus(1, ChronoUnit.HOURS), true);
                    assertThat(expired.isExpired(Instant.now())).isTrue();
                    assertThat(expired.isUsable(Instant.now())).isFalse();
                }

                @Test
                void deactivatedLinksAreNotUsable() {
                    assertThat(link(null, false).isUsable(Instant.now())).isFalse();
                }

                @Test
                @DisplayName("a link expiring in the future is still usable")
                void futureExpiryIsUsable() {
                    Link future = link(Instant.now().plus(1, ChronoUnit.HOURS), true);
                    assertThat(future.isUsable(Instant.now())).isTrue();
                }
            }
            """;

    static final String END_TO_END_TEST = """
            package com.example.urlshortener;

            import static org.hamcrest.Matchers.startsWith;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
            import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
            import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
            import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

            import com.fasterxml.jackson.databind.ObjectMapper;
            import org.junit.jupiter.api.DisplayName;
            import org.junit.jupiter.api.Test;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
            import org.springframework.boot.test.context.SpringBootTest;
            import org.springframework.http.MediaType;
            import org.springframework.test.web.servlet.MockMvc;
            import org.springframework.test.web.servlet.MvcResult;

            @SpringBootTest
            @AutoConfigureMockMvc
            class LinkApiIntegrationTest {

                @Autowired
                private MockMvc mockMvc;

                @Autowired
                private ObjectMapper objectMapper;

                private String createLink(String url, String alias) throws Exception {
                    MvcResult result = mockMvc.perform(post("/api/v1/links")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(
                                            new LinkController.CreateLinkRequest(url, alias, null))))
                            .andExpect(status().isCreated())
                            .andReturn();
                    return objectMapper.readTree(result.getResponse().getContentAsString())
                            .path("code").asText();
                }

                @Test
                @DisplayName("shorten then follow the link")
                void shortenAndRedirect() throws Exception {
                    String code = createLink("https://example.com/target", null);

                    mockMvc.perform(get("/" + code))
                            .andExpect(status().isFound())
                            .andExpect(header().string("Location", "https://example.com/target"));
                }

                @Test
                void customAliasIsHonoured() throws Exception {
                    String code = createLink("https://example.com/aliased", "myAlias");

                    mockMvc.perform(get("/" + code))
                            .andExpect(status().isFound())
                            .andExpect(header().string("Location", startsWith("https://example.com")));
                }

                @Test
                void duplicateAliasIsRejected() throws Exception {
                    createLink("https://example.com/one", "takenCode");

                    mockMvc.perform(post("/api/v1/links")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(
                                            new LinkController.CreateLinkRequest(
                                                    "https://example.com/two", "takenCode", null))))
                            .andExpect(status().isConflict());
                }

                @Test
                void invalidTargetIsRejected() throws Exception {
                    mockMvc.perform(post("/api/v1/links")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(
                                            new LinkController.CreateLinkRequest(
                                                    "javascript:alert(1)", null, null))))
                            .andExpect(status().isBadRequest())
                            .andExpect(jsonPath("$.error").exists());
                }

                @Test
                @DisplayName("a deleted link stops redirecting")
                void deletedLinksStopResolving() throws Exception {
                    String code = createLink("https://example.com/gone", null);

                    mockMvc.perform(delete("/api/v1/links/" + code))
                            .andExpect(status().isNoContent());
                    mockMvc.perform(get("/" + code))
                            .andExpect(status().isNotFound());
                }

                @Test
                void unknownCodeIsNotFound() throws Exception {
                    mockMvc.perform(get("/nosuch1")).andExpect(status().isNotFound());
                }
            }
            """;

    static final String README = """
            # URL Shortener

            Generated by the Agentic SDLC Orchestrator.

            ## Run

            ```bash
            mvn spring-boot:run
            ```

            ## API

            | Method | Path | Purpose |
            |---|---|---|
            | `POST` | `/api/v1/links` | Create a short link |
            | `GET` | `/api/v1/links/{code}` | Link metadata |
            | `DELETE` | `/api/v1/links/{code}` | Deactivate a link |
            | `GET` | `/{code}` | 302 redirect to the target |

            ```bash
            curl -s localhost:8090/api/v1/links -H 'Content-Type: application/json' \\
              -d '{"url":"https://example.com/somewhere-long"}'

            curl -i localhost:8090/<code>
            ```

            ## Design notes

            - **Codes are random, not sequential.** A sequential counter encoded in Base62 makes every
              link enumerable from any single link, turning a shortener into a public directory.
            - **Collisions are resolved by retrying an insert**, not by checking first. Check-then-insert
              is a race; letting the primary key reject a duplicate is not.
            - **Targets are restricted to http and https.** Without that check a shortener will mint
              links to `javascript:` and `file:` URLs and become a redirect laundering service.
            - **`resolve` is separate from `find`.** The management API can see a link that a redirect
              must refuse to follow, which is not the same question.

            ## Limitations

            - In-memory H2: data does not survive a restart.
            - No rate limiting, authentication or click analytics yet.
            """;
}
