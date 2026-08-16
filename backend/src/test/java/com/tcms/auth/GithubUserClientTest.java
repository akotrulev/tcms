package com.tcms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Drives {@link GithubUserClient} over real loopback HTTP — {@code StubIssuer}'s approach, minus
 * the OIDC half GitHub does not speak. The interesting cases are the ones GitHub actually
 * produces and a real OAuth app will not produce on request: an error inside an HTTP 200, and a
 * {@code /user/emails} read refused because the scope was withheld.
 */
class GithubUserClientTest {

    private HttpServer server;
    private String base;
    private GithubUserClient client;

    private final AtomicReference<String> tokenBody = new AtomicReference<>();
    private final AtomicReference<String> userBody = new AtomicReference<>();
    private final AtomicReference<Integer> emailsStatus = new AtomicReference<>(200);
    private final AtomicReference<String> emailsBody = new AtomicReference<>("[]");

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/login/oauth/access_token", exchange ->
                respond(exchange, 200, tokenBody.get()));
        server.createContext("/user/emails", exchange ->
                respond(exchange, emailsStatus.get(), emailsBody.get()));
        server.createContext("/user", exchange -> respond(exchange, 200, userBody.get()));
        server.start();
        client = new GithubUserClient(RestClient.builder().build(), base, base);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    @DisplayName("a happy exchange yields the numeric id as subject, plus name and verified primary email")
    void happyPath() {
        tokenBody.set("{\"access_token\":\"gho_x\",\"token_type\":\"bearer\"}");
        userBody.set("{\"id\": 583231, \"login\": \"octocat\", \"name\": \"The Octocat\"}");
        emailsBody.set("[{\"email\":\"octocat@github.com\",\"primary\":true,\"verified\":true},"
                + "{\"email\":\"other@github.com\",\"primary\":false,\"verified\":true}]");

        Optional<GithubUserClient.GithubIdentity> identity =
                client.identityFor("id", "secret", "code", "https://api/callback");

        assertThat(identity).isPresent();
        assertThat(identity.get().subject()).isEqualTo("583231");
        assertThat(identity.get().name()).isEqualTo("The Octocat");
        assertThat(identity.get().email()).isEqualTo("octocat@github.com");
        assertThat(identity.get().emailVerified()).isTrue();
    }

    @Test
    @DisplayName("an error body from the token endpoint is one empty result — GitHub answers 200 with {\"error\":...}")
    void errorBodyIsRefusal() {
        tokenBody.set("{\"error\":\"bad_verification_code\"}");

        assertThat(client.identityFor("id", "secret", "stale", "https://api/callback")).isEmpty();
    }

    @Test
    @DisplayName("a failed /user/emails read degrades to no email, not to failure")
    void emailLookupIsBestEffort() {
        tokenBody.set("{\"access_token\":\"gho_x\",\"token_type\":\"bearer\"}");
        userBody.set("{\"id\": 583231, \"login\": \"octocat\", \"name\": \"The Octocat\"}");
        emailsStatus.set(403);
        emailsBody.set("{\"message\":\"scope withheld\"}");

        Optional<GithubUserClient.GithubIdentity> identity =
                client.identityFor("id", "secret", "code", "https://api/callback");

        assertThat(identity).isPresent();
        assertThat(identity.get().email()).isNull();
        assertThat(identity.get().emailVerified()).isFalse();
    }

    @Test
    @DisplayName("a missing id refuses — the subject is the key and there is no fallback to login or email")
    void noIdNoIdentity() {
        tokenBody.set("{\"access_token\":\"gho_x\",\"token_type\":\"bearer\"}");
        userBody.set("{\"login\": \"octocat\", \"name\": \"The Octocat\"}");

        assertThat(client.identityFor("id", "secret", "code", "https://api/callback")).isEmpty();
    }

    @Test
    @DisplayName("a blank name falls back to the login for display, never for the subject")
    void nameFallsBackToLogin() {
        tokenBody.set("{\"access_token\":\"gho_x\",\"token_type\":\"bearer\"}");
        userBody.set("{\"id\": 583231, \"login\": \"octocat\", \"name\": null}");

        Optional<GithubUserClient.GithubIdentity> identity =
                client.identityFor("id", "secret", "code", "https://api/callback");

        assertThat(identity).isPresent();
        assertThat(identity.get().subject()).isEqualTo("583231");
        assertThat(identity.get().name()).isEqualTo("octocat");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        exchange.close();
    }
}
