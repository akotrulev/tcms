package com.tcms.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One authorization request in flight — the few seconds between sending a browser to an issuer and
 * that browser coming back with a code.
 *
 * <p><strong>Instance-level: no company filter, no RLS</strong> (ERD §0.1 exception list, decided in
 * {@code V53}). This is {@link com.tcms.identity.ApiTokenLookup}'s decision a third time and for the
 * same reason: a callback arrives carrying a {@code state} and nothing else — no session, no company
 * — so {@code app.current_company} is unset and an RLS-protected row would be unreadable in exactly
 * the one place it must be read. That is {@code V7}'s defect, and it presents as "wrong credential"
 * rather than as a schema fault.
 *
 * <p>{@link #companyId} is the company the attempt is <em>against</em> — a target, like
 * {@code api_token_lookup}'s, not a tenancy discriminator. Every read that follows it happens inside
 * a context opened from it.
 *
 * <h2>It holds nothing identifying, and that is what makes the exception cheap</h2>
 *
 * <p>No email, no subject, no user id — not because they are withheld but because at insert time
 * none of them is known: the person has not authenticated yet. A reader who obtains this table
 * learns that somebody, somewhere, is part-way through signing in to some company.
 *
 * <h2>Single-use, and the row is consumed before the token exchange</h2>
 *
 * <p>{@link #consume} stamps {@link #consumedAt} <em>before</em> the code is exchanged rather than
 * after. Stamping it after leaves a window in which two concurrent callbacks carrying the same
 * {@code state} both pass the check — and the winner of that race is whoever replayed it.
 */
@Entity
@Table(name = "sso_login_attempt")
public class SsoLoginAttempt {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "provider_id", nullable = false, updatable = false)
    private UUID providerId;

    /**
     * Hex SHA-256 of the {@code state}, never the state itself.
     *
     * <p>Digested for {@code api_token_lookup}'s reason: the value travels in a URL, so it lands in
     * browser history and in any proxy log that records query strings, and a database read should
     * not also yield the live handles for every sign-in currently in flight.
     */
    @Column(name = "state_hash", nullable = false, updatable = false)
    private String stateHash;

    /** The PKCE verifier, held server-side — see the {@code V53} header for the rejected cookie. */
    @Column(name = "code_verifier", nullable = false, updatable = false)
    private String codeVerifier;

    @Column(name = "nonce", nullable = false, updatable = false)
    private String nonce;

    /** What was sent to the issuer, so the exchange can send back the identical string. */
    @Column(name = "redirect_uri", nullable = false, updatable = false)
    private String redirectUri;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    /** JPA only. */
    protected SsoLoginAttempt() {
    }

    public static SsoLoginAttempt begin(
            UUID id,
            UUID companyId,
            UUID providerId,
            String stateHash,
            String codeVerifier,
            String nonce,
            String redirectUri,
            Instant now,
            Instant expiresAt) {

        SsoLoginAttempt attempt = new SsoLoginAttempt();
        attempt.id = id;
        attempt.companyId = companyId;
        attempt.providerId = providerId;
        attempt.stateHash = stateHash;
        attempt.codeVerifier = codeVerifier;
        attempt.nonce = nonce;
        attempt.redirectUri = redirectUri;
        attempt.createdAt = now;
        attempt.expiresAt = expiresAt;
        return attempt;
    }

    /**
     * Whether this attempt may still be completed.
     *
     * <p>Both halves matter and neither is sufficient: an expired row is a browser that took too
     * long or a link somebody kept, and a consumed row is a replay.
     */
    public boolean isUsable(Instant now) {
        return consumedAt == null && now.isBefore(expiresAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public UUID getProviderId() {
        return providerId;
    }

    public String getCodeVerifier() {
        return codeVerifier;
    }

    public String getNonce() {
        return nonce;
    }

    public String getRedirectUri() {
        return redirectUri;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
