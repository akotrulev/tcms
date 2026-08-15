package com.tcms.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An OIDC issuer, in this JVM, on a loopback port.
 *
 * <p>Serves the three documents the flow reads — {@code openid-configuration}, the JWK set, and the
 * token endpoint — signed by a key generated per instance. It exists because §217 shipped a flow
 * that no test drives over HTTP against an issuer no test stands up, and because the interesting
 * cases are the ones a real issuer will not produce on request: an {@code id_token} carrying the
 * wrong {@code nonce}, one from a different {@code iss}, one for a different {@code aud}.
 *
 * <p>Chosen over Testcontainers Keycloak for three reasons, the third deciding: it adds no
 * dependency and no container, it starts in milliseconds, and it can be told to misbehave. Keycloak
 * stays the manual counterparty for the browser queue, where being real is the point.
 *
 * <h2>It speaks plain HTTP, and that costs one deliberate exemption</h2>
 *
 * <p>Two gates refuse a non-https issuer, and §220's plan knew about only one of them.
 * {@code IdentityProviderService.validate} refuses it at <em>registration</em> — which is why the
 * provider row here is written through the repository, the same fact as
 * {@code deploy/keycloak/README.md}'s SQL recipe arriving from the test side. But
 * {@link OidcDiscovery#forIssuer} refuses it again at <em>runtime</em>, on both {@code authorize}
 * and {@code callback}, so bypassing the first gate alone leaves every flow test refused on its
 * first line.
 *
 * <p>{@code SsoFlowIntegrationTest.LoopbackDiscovery} is the exemption: plain HTTP is fetched for
 * {@code 127.0.0.1} and for nothing else, and every other issuer still goes through the real gate.
 * Nothing downstream is stubbed — the token exchange, the JWKS fetch, the signature check, the
 * {@code nonce}/{@code iss}/{@code aud} comparisons, the attempt claiming and the RLS context are
 * all the production path. The gate that is stepped around is observed directly by
 * {@code OidcDiscoveryTest}, written in the same slice because it turned out nothing observed it at
 * all.
 *
 * <p>Uses {@code com.sun.net.httpserver}, in the JDK since 6 — no dependency, no container.
 */
final class StubIssuer implements AutoCloseable {

    private final HttpServer server;
    private final RSAKey key;
    private final String issuer;

    /** What the next token exchange will answer with. Set per test by {@link #willIssue}. */
    private final AtomicReference<JWTClaimsSet.Builder> nextClaims = new AtomicReference<>();

    StubIssuer() throws Exception {
        this.key = new RSAKeyGenerator(2048).keyID("stub").generate();
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.issuer = "http://127.0.0.1:" + server.getAddress().getPort();

        server.createContext("/.well-known/openid-configuration", exchange -> respond(exchange, """
                {
                  "issuer": "%s",
                  "authorization_endpoint": "%s/authorize",
                  "token_endpoint": "%s/token",
                  "jwks_uri": "%s/jwks"
                }""".formatted(issuer, issuer, issuer, issuer)));

        server.createContext("/jwks", exchange ->
                respond(exchange, "{\"keys\":[" + key.toPublicJWK().toJSONString() + "]}"));

        server.createContext("/token", exchange -> respond(exchange, """
                {"access_token":"stub","token_type":"Bearer","id_token":"%s"}"""
                .formatted(sign(nextClaims.get()))));

        server.start();
    }

    String issuer() {
        return issuer;
    }

    /**
     * The claim set the next exchange answers with.
     *
     * <p>{@code nonce} is a parameter rather than something this class invents, and that is the
     * whole point of the type: a test must decide what to echo, and the passing case has to read it
     * out of the authorize redirect. That is the only way §217 FD-11 — nothing proves the service
     * hands the verifier the attempt's own nonce — can be closed.
     */
    void willIssue(String clientId, String subject, String email, boolean emailVerified, String nonce) {
        nextClaims.set(new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(clientId)
                .subject(subject)
                .claim("email", email)
                .claim("email_verified", emailVerified)
                .claim("nonce", nonce)
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(300))));
    }

    /** Overrides one claim on the pending token — how a wrong issuer or a wrong audience is driven. */
    void override(Map<String, Object> claims) {
        JWTClaimsSet.Builder builder = nextClaims.get();
        claims.forEach(builder::claim);
    }

    private String sign(JWTClaimsSet.Builder claims) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                    claims.build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("the stub issuer could not sign", e);
        }
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
