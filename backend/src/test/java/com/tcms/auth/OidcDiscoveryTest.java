package com.tcms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * {@link OidcDiscovery} — the component that had no test.
 *
 * <p>Found while writing {@code SsoFlowIntegrationTest}, which needed an exemption from the https
 * gate here and turned up the fact that <strong>nothing observed this class at all</strong>: not the
 * gate, not the self-declared-issuer refusal, not the completeness check, not the cache. The plan
 * for that slice knew about {@code IdentityProviderService.validate}'s https refusal and did not
 * know there was a second one at runtime — which is exactly the shape of gap an untested class
 * produces.
 *
 * <p>No Spring context: this class takes a {@code RestClient}, an {@code OidcProperties} and a
 * {@code Clock}, all three of which a test can hand it. The counterparty is a loopback
 * {@code com.sun.net.httpserver} like {@link StubIssuer}'s, so the fetch, the parse and the
 * comparisons are the real ones.
 */
@DisplayName("OIDC discovery — the gate, the issuer comparison, and the cache")
class OidcDiscoveryTest {

    private HttpServer server;
    private String origin;

    /**
     * One instance per test, because the cache lives on it — a helper that built a fresh
     * {@code OidcDiscovery} per call would make the two cache assertions unfalsifiable, which is
     * exactly how the first draft of this class reported a cache that was never consulted.
     */
    private OidcDiscovery discovery;

    /** What the next discovery fetch answers with, and how many times it has been asked. */
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicInteger fetches = new AtomicInteger();

    private final AtomicReference<Instant> now =
            new AtomicReference<>(Instant.parse("2026-08-15T00:00:00Z"));

    @BeforeEach
    void startIssuer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/.well-known/openid-configuration", exchange -> {
            fetches.incrementAndGet();
            respond(exchange, body.get());
        });
        server.start();
        // Declares the https-shaped name, because that is what the caller configures and what the
        // self-declared-issuer comparison is made against.
        body.set(complete(https(origin)));
        discovery = newDiscovery();
    }

    @AfterEach
    void stopIssuer() {
        server.stop(0);
    }

    @Test
    @DisplayName("an http:// issuer is refused without a request being made")
    void aPlainHttpIssuerIsRefused() {
        // THE gate. The discovery document names the jwks_uri every signature is checked against,
        // so over plain HTTP anyone on the path nominates the keys that decide who signs in.
        // Refused before the fetch, which is why `fetches` is still zero.
        assertThat(discovery.forIssuer(origin)).isEmpty();
        assertThat(fetches.get())
                .describedAs("refused on the scheme alone — no request is made at all")
                .isZero();
    }

    @Test
    @DisplayName("a null issuer is refused rather than dereferenced")
    void aNullIssuerIsRefused() {
        assertThat(discovery.forIssuer(null)).isEmpty();
    }

    @Test
    @DisplayName("a document declaring a different issuer is refused")
    void aDocumentThatNamesSomebodyElseIsRefused() {
        body.set(complete("https://somebody.else.test"));

        // Refused rather than followed: `iss` on every id_token is validated against the CONFIGURED
        // value, so accepting the document's own claim would let a mistyped or compromised host
        // nominate the identity it is trusted as.
        assertThat(fetchOver(https(origin))).isEmpty();
    }

    @Test
    @DisplayName("a document missing an endpoint the flow needs is refused")
    void anIncompleteDocumentIsRefused() {
        body.set("{\"issuer\":\"%s\",\"authorization_endpoint\":\"%s/authorize\"}"
                .formatted(https(origin), https(origin)));

        assertThat(fetchOver(https(origin))).isEmpty();
    }

    @Test
    @DisplayName("an unreachable issuer is empty, not an exception")
    void anUnreachableIssuerIsEmpty() {
        // Every refusal in this flow is the same refusal (§6r), so a transport failure must arrive
        // as an empty Optional rather than as something the controller has to catch.
        assertThat(fetchOver("https://127.0.0.1:1/realms/nothing")).isEmpty();
    }

    @Test
    @DisplayName("a good document is returned, and the second ask is served from the cache")
    void aGoodDocumentIsCachedPerIssuer() {
        String issuer = https(origin);

        Optional<OidcDiscoveryDocument> first = fetchOver(issuer);
        Optional<OidcDiscoveryDocument> second = fetchOver(issuer);

        assertThat(first).isPresent();
        assertThat(first.orElseThrow().jwksUri()).isEqualTo(https(origin) + "/jwks");
        assertThat(second).isPresent();
        assertThat(fetches.get())
                .describedAs("cached per ISSUER, so two companies pointing at one issuer share it")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the cache expires, and a stale entry is refetched")
    void theCacheExpires() {
        String issuer = https(origin);
        fetchOver(issuer);

        now.set(now.get().plus(Duration.ofDays(1)));
        fetchOver(issuer);

        assertThat(fetches.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("a trailing slash is the same issuer, not a second cache entry")
    void aTrailingSlashIsNormalised() {
        fetchOver(https(origin));
        fetchOver(https(origin) + "/");

        assertThat(fetches.get()).isEqualTo(1);
    }

    // =====================================================================
    // helpers
    // =====================================================================

    /**
     * Runs a fetch against the loopback server while the issuer string reads as {@code https}.
     *
     * <p>The gate is on the scheme of the configured string, and the stub cannot serve TLS without
     * a certificate this project has no dependency to generate. So the https-shaped name is
     * rewritten to the loopback origin at the request factory, one layer below the gate: everything
     * this class does — the gate, the parse, the issuer comparison, the completeness check and the
     * cache — runs on the https string exactly as production would.
     */
    private Optional<OidcDiscoveryDocument> fetchOver(String httpsIssuer) {
        return discovery.forIssuer(httpsIssuer);
    }

    private static String https(String httpOrigin) {
        return "https://" + httpOrigin.substring("http://".length());
    }

    private OidcDiscovery newDiscovery() {
        return new OidcDiscovery(
                RestClient.builder()
                        .requestFactory((uri, method) -> {
                            URI redirected = uri.getScheme().equals("https")
                                    ? URI.create("http://" + uri.getAuthority() + uri.getRawPath())
                                    : uri;
                            return new org.springframework.http.client.SimpleClientHttpRequestFactory()
                                    .createRequest(redirected, method);
                        })
                        .build(),
                properties(),
                movableClock());
    }

    /** A clock the cache-expiry test can move. {@code Clock.fixed} cannot be subclassed. */
    private Clock movableClock() {
        return new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
    }

    private static OidcProperties properties() {
        return new OidcProperties(
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                Duration.ofMinutes(10),
                Duration.ofMinutes(10),
                URI.create("http://localhost:8080"),
                "/sso/callback");
    }

    private static String complete(String issuer) {
        return """
                {
                  "issuer": "%s",
                  "authorization_endpoint": "%s/authorize",
                  "token_endpoint": "%s/token",
                  "jwks_uri": "%s/jwks"
                }""".formatted(issuer, issuer, issuer, issuer);
    }

    private static void respond(HttpExchange exchange, String payload) throws IOException {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
