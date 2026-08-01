package com.tcms.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.stereotype.Component;

/**
 * Mints session tokens. The only place a claim set is assembled.
 *
 * <h2>The claim set, and what it deliberately does not carry yet</h2>
 *
 * <pre>
 * iss   this instance                         so a token from elsewhere is refused
 * sub   user id
 * co    active company id                     absent for an operator with no membership
 * pv    permission version                    the revocation epoch, plan §6b
 * iat / exp / jti
 * </pre>
 *
 * <p>Plan §6b also specifies {@code sys} / {@code cmp} / {@code prj} grant claims so that
 * no request resolves permissions from the database. <strong>Those are not here yet, and
 * the names are reserved rather than forgotten.</strong> The reasoning is in
 * {@code docs/plans/2026-07-30-tcms-invitation-activation-login.md} §2.1: the grant claims
 * are an optimization that removes a query from a hot path nobody has measured, while
 * shipping them creates a second authorization-resolution path alongside
 * {@code PermissionEvaluator} — and §6b's own warning is that a permission resolved
 * inconsistently between two code paths is the bug class the design exists to prevent.
 *
 * <p>{@code pv} ships now regardless, because it is the revocation channel whatever the
 * token carries, and retrofitting it would mean invalidating every token in flight.
 *
 * <h2>Symmetric signing, deliberately</h2>
 *
 * <p>HS256 with a configured secret. One deployable issues and verifies its own tokens, so
 * there is no second party needing a public key, and an asymmetric key pair would add key
 * management for no verifier. M4's external providers do not change this: they bring their
 * own issuer and their own decoder, alongside rather than instead of this one.
 */
@Component
public class SessionTokens {

    /** The active company. Absent for an account with no ACTIVE membership anywhere. */
    public static final String CLAIM_COMPANY = "co";

    /** The revocation epoch (plan §6b). */
    public static final String CLAIM_PERMISSION_VERSION = "pv";

    private final JwtEncoder encoder;
    private final JwtProperties properties;

    /**
     * The application clock rather than {@code Instant.now()}, so this agrees with every
     * other timestamp the instance produces and a session can be issued at a controlled
     * moment in a test. See {@code TcmsApplication#clock()} for why it is truncated.
     */
    private final Clock clock;

    public SessionTokens(JwtEncoder encoder, JwtProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    /** A minted token and the moment it stops being accepted. */
    public record Session(String token, Instant expiresAt) {
    }

    /**
     * @param companyId the session's active company, or {@code null} for an account with no
     *                  ACTIVE membership — an Instance Admin before they have provisioned
     *                  anything. Such a session is company-context-less and holds SYSTEM
     *                  permissions only, which is the correct answer rather than a failure.
     */
    public Session issue(UUID userId, UUID companyId, int permissionVersion, boolean rememberMe) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.ttlFor(rememberMe));

        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                // A unique id per token, so a future denylist has something to name. Cheap
                // now; impossible to add retroactively to tokens already in circulation.
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_PERMISSION_VERSION, permissionVersion);

        if (companyId != null) {
            claims.claim(CLAIM_COMPANY, companyId.toString());
        }

        String token = encoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();
        return new Session(token, expiresAt);
    }
}
