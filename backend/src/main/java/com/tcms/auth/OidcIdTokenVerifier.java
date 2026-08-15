package com.tcms.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * Whether an {@code id_token} is one this instance may act on — <strong>the security boundary of
 * the whole SSO flow.</strong>
 *
 * <p>Everything downstream treats the {@code sub} it returns as proven, so each of the four checks
 * below is load-bearing and three of them fail open if omitted:
 *
 * <ul>
 *   <li><strong>signature</strong> — against the issuer's published keys, fetched from the
 *       {@code jwks_uri} in its own discovery document;</li>
 *   <li><strong>{@code iss}</strong> — against the configured issuer, so a token minted by a
 *       different party is not accepted merely because it verifies against some key;</li>
 *   <li><strong>{@code aud}</strong> — against this company's {@code client_id}, so a token the
 *       issuer minted for <em>another</em> relying party cannot be replayed here. This is the
 *       standard Cognito trap: its <em>access</em> tokens carry no {@code aud} at all and are
 *       distinguished by {@code token_use}, so a lenient audience check turns "no audience" into
 *       "any audience";</li>
 *   <li><strong>{@code nonce}</strong> — against the value stored with the login attempt, which is
 *       what binds this token to the authorization request that asked for it.</li>
 * </ul>
 *
 * <p>Expiry is checked too, by {@link JwtTimestampValidator}, and it is the one of the five that
 * fails closed on its own.
 *
 * <h2>The decoder is cached and the nonce check deliberately is not in it</h2>
 *
 * <p>Building a {@link NimbusJwtDecoder} per callback refetches the JWKS on every single sign-in.
 * So one decoder is kept per provider, carrying the checks that are constant for that provider —
 * signature, timestamp, issuer, audience. The nonce is per-request by definition and is compared
 * after decoding, which is why it is the one check written out here rather than configured.
 */
@Component
public class OidcIdTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(OidcIdTokenVerifier.class);

    /** What the token must have been minted for. */
    public record Expected(UUID providerId, String issuerUri, String clientId, String jwksUri,
                           String nonce) {
    }

    private final ConcurrentMap<UUID, JwtDecoder> decoders = new ConcurrentHashMap<>();

    /**
     * @return the verified token, or empty for <em>any</em> reason it was not acceptable. One
     *         refusal, per §6r — the caller has nothing finer to tell anybody
     */
    public Optional<Jwt> verify(Expected expected, String idToken) {
        return verifyWith(
                decoders.computeIfAbsent(expected.providerId(), ignored -> decoderFor(expected)),
                expected,
                idToken);
    }

    /**
     * The verification proper, over a decoder somebody else built.
     *
     * <p>Package-private so a test can supply a decoder over a locally generated key pair and
     * exercise these checks without an issuer, a network or a JWKS endpoint — the checks are the
     * subject, and fetching keys is not.
     */
    Optional<Jwt> verifyWith(JwtDecoder decoder, Expected expected, String idToken) {
        if (idToken == null || idToken.isBlank()) {
            return Optional.empty();
        }
        Jwt jwt;
        try {
            jwt = decoder.decode(idToken);
        } catch (JwtException refused) {
            log.info("id_token from provider {} refused: {}", expected.providerId(), refused.getMessage());
            return Optional.empty();
        }
        if (!expected.nonce().equals(jwt.getClaimAsString("nonce"))) {
            // Without this the token proves only that SOMEBODY signed in at that issuer, not that
            // the person completing this callback did. It is what makes the attempt row a
            // single-use binding rather than a bookkeeping entry.
            log.info("id_token from provider {} carried the wrong nonce", expected.providerId());
            return Optional.empty();
        }
        return Optional.of(jwt);
    }

    private static JwtDecoder decoderFor(Expected expected) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(expected.jwksUri()).build();
        decoder.setJwtValidator(validatorFor(expected.issuerUri(), expected.clientId()));
        return decoder;
    }

    /**
     * Timestamp, issuer and audience — the checks that hold for every token from one provider.
     *
     * <p>Shared with the test rather than reachable only through {@link #decoderFor}, because a
     * test that assembled its own validators would be asserting that <em>a</em> correct set
     * refuses these tokens, not that the one production uses does.
     */
    static OAuth2TokenValidator<Jwt> validatorFor(String issuerUri, String clientId) {
        return new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                new JwtIssuerValidator(issuerUri),
                audienceIs(clientId));
    }

    /**
     * Requires {@code aud} to name this client. A token with no audience at all is refused by the
     * same arm, which is the Cognito access-token case the class header describes.
     */
    private static OAuth2TokenValidator<Jwt> audienceIs(String clientId) {
        return jwt -> {
            List<String> audience = jwt.getAudience();
            if (audience != null && audience.contains(clientId)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token", "The id_token was not minted for this client", null));
        };
    }
}
