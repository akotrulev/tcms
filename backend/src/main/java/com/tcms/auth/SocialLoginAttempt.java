package com.tcms.auth;

import com.tcms.identity.SocialProviderKey;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One in-flight social round-trip. {@link SsoLoginAttempt}'s twin, minus the company (there is
 * none until somebody signs in) and plus the {@link Intent} — which travels here, server-side,
 * bound to the presented {@code state}, and never in the URL (the callback argument
 * {@code SsoController#callback} makes, applied to intents: a caller-supplied copy of the
 * intent could only be ignored or trusted).
 *
 * <p><strong>Instance-level: no company filter, no RLS</strong> (ERD §0.1, decided in
 * {@code V58}) — consulted before any session exists, for {@code sso_login_attempt}'s reason.
 */
@Entity
@Table(name = "social_login_attempt")
public class SocialLoginAttempt {

    /**
     * What completing this callback is allowed to do. The shape check in V58 enforces the
     * binding: LINK ⇔ a user, LOGIN ⇔ nobody yet.
     */
    public enum Intent {
        LOGIN,
        LINK
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, updatable = false)
    private SocialProviderKey provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "intent", nullable = false, updatable = false)
    private Intent intent;

    @Column(name = "user_id", updatable = false)
    private UUID userId;

    /** Hex SHA-256 of the {@code state}, never the state itself — {@link SsoLoginAttempt}'s rule. */
    @Column(name = "state_hash", nullable = false, updatable = false)
    private String stateHash;

    /** The PKCE verifier, held server-side — see V53's header for the rejected cookie. */
    @Column(name = "code_verifier", nullable = false, updatable = false)
    private String codeVerifier;

    @Column(name = "nonce", nullable = false, updatable = false)
    private String nonce;

    /** What was sent to the provider, so the exchange can send back the identical string. */
    @Column(name = "redirect_uri", nullable = false, updatable = false)
    private String redirectUri;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    /** JPA only. */
    protected SocialLoginAttempt() {
    }

    public static SocialLoginAttempt begin(
            UUID id,
            SocialProviderKey provider,
            Intent intent,
            UUID userId,
            String stateHash,
            String codeVerifier,
            String nonce,
            String redirectUri,
            Instant now,
            Instant expiresAt) {

        SocialLoginAttempt attempt = new SocialLoginAttempt();
        attempt.id = id;
        attempt.provider = provider;
        attempt.intent = intent;
        attempt.userId = userId;
        attempt.stateHash = stateHash;
        attempt.codeVerifier = codeVerifier;
        attempt.nonce = nonce;
        attempt.redirectUri = redirectUri;
        attempt.createdAt = now;
        attempt.expiresAt = expiresAt;
        return attempt;
    }

    /**
     * Whether this attempt may still be completed. Both halves matter and neither is sufficient:
     * an expired row is a browser that took too long, a consumed row is a replay.
     */
    public boolean isUsable(Instant now) {
        return consumedAt == null && now.isBefore(expiresAt);
    }

    public UUID getId() {
        return id;
    }

    public SocialProviderKey getProvider() {
        return provider;
    }

    public Intent getIntent() {
        return intent;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getStateHash() {
        return stateHash;
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

    public Instant getConsumedAt() {
        return consumedAt;
    }
}
