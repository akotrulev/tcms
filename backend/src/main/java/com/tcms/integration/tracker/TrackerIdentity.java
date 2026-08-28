package com.tcms.integration.tracker;

import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * {@code tracker_identity} — <strong>one person's</strong> connection to one tracker host
 * ({@code V73}, 004 D2).
 *
 * <h2>RLS is company isolation. This row needs person isolation too</h2>
 *
 * <p>The policy on this table keeps Acme from reading Globex's credentials, which is necessary and
 * is not the whole requirement: within one company, a Company Admin must not be able to read a
 * colleague's row through any endpoint. That half is {@link TrackerIdentityService} filtering every
 * query on {@code user_id = the caller}, and it is stated here because a reader who sees
 * {@code FORCE ROW LEVEL SECURITY} in {@code V73} might reasonably conclude the database has it
 * covered.
 *
 * <h2>{@code user_id} is a {@code UUID} this module never resolves</h2>
 *
 * <p>{@code integration} may not import {@code identity}, and does not want to: it is
 * {@code storage}'s pattern verbatim — <em>"it holds no domain concept, so an {@code owner_id} is a
 * {@code UUID} it never resolves"</em>. The id arrives from {@code @AuthenticationPrincipal Jwt} in
 * the controller, exactly as {@code NotificationChannelService} already takes one. That is what
 * makes D4 survive the per-user inversion: the module's {@code allowedDependencies} does not widen.
 *
 * <h2>{@code expiresAt} is not optional metadata</h2>
 *
 * <p>Jira Cloud has enforced a maximum one-year API-token life since December 2024 and retired
 * non-expiring tokens in March 2025, so <em>every</em> Jira Cloud connection in this product dies
 * within a year by construction. Together with {@link #lastError} and {@link #lastCheckedAt} it is
 * the difference between the connections page saying "reconnect your account" and the product
 * silently no longer filing defects while runs keep passing and failing.
 */
@Entity
@Table(name = "tracker_identity")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class TrackerIdentity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, updatable = false)
    private TrackerProvider provider;

    @Column(name = "base_url", nullable = false, updatable = false)
    private String baseUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_method", nullable = false)
    private TrackerAuthMethod authMethod;

    /** AES-GCM, nonce prepended — {@code SecretCipher}. Never leaves this module as plaintext. */
    @Column(name = "secret", nullable = false)
    private byte[] secret;

    @Column(name = "secret_hint")
    private String secretHint;

    @Column(name = "account_id")
    private String accountId;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    /** JPA only. */
    protected TrackerIdentity() {
    }

    static TrackerIdentity connect(
            UUID userId,
            TrackerProvider provider,
            String baseUrl,
            byte[] secret,
            String secretHint,
            String accountId,
            Instant expiresAt,
            Instant now) {

        TrackerIdentity created = new TrackerIdentity();
        created.id = UUID.randomUUID();
        created.companyId = CompanyContext.require();
        created.userId = userId;
        created.provider = provider;
        created.baseUrl = baseUrl;
        created.authMethod = TrackerAuthMethod.PAT;
        created.secret = secret;
        created.secretHint = secretHint;
        created.accountId = accountId;
        created.expiresAt = expiresAt;
        created.enabled = true;
        created.createdAt = now;
        created.updatedAt = now;
        return created;
    }

    /**
     * Replaces the credential — what a second {@code POST} for an already-connected host does.
     *
     * <p><strong>{@code lastError} and {@code lastCheckedAt} are cleared</strong>, and that is the
     * decision this method exists to make in one place: a rotation answers the question the old
     * error was about, so leaving "401 Unauthorized" on the row would show a person who has just
     * fixed their connection a red row about a token that no longer exists.
     */
    void rotate(byte[] secret, String secretHint, String accountId, Instant expiresAt, Instant now) {
        this.secret = secret;
        this.secretHint = secretHint;
        this.accountId = accountId;
        this.expiresAt = expiresAt;
        this.lastError = null;
        this.lastCheckedAt = null;
        this.enabled = true;
        this.updatedAt = now;
    }

    /**
     * Records what the tracker said — the {@code …/test} endpoint's whole durable effect, and the
     * one every real call makes too.
     *
     * @param error the tracker's message, already redacted, or null for a success
     */
    void recordCheck(String error, Instant now) {
        this.lastError = error;
        this.lastCheckedAt = now;
        this.updatedAt = now;
    }

    /**
     * Whether this connection is in the state {@link TrackerConnectionState#NEEDS_ATTENTION} names.
     *
     * <p>Derived rather than stored, so it cannot disagree with the columns it is derived from —
     * and computed <strong>here</strong> rather than in the two views that render it, because the
     * connected-members list and the person's own page must not be able to say different things
     * about the same row.
     *
     * @param now the clock's instant, passed rather than read so an expiry is evaluated against the
     *            same moment the rest of the response was
     */
    boolean needsAttention(Instant now) {
        return !enabled
                || lastError != null
                || (expiresAt != null && !expiresAt.isAfter(now));
    }

    public UUID getId() {
        return id;
    }

    UUID getUserId() {
        return userId;
    }

    TrackerProvider getProvider() {
        return provider;
    }

    String getBaseUrl() {
        return baseUrl;
    }

    TrackerAuthMethod getAuthMethod() {
        return authMethod;
    }

    byte[] getSecret() {
        return secret;
    }

    String getSecretHint() {
        return secretHint;
    }

    String getAccountId() {
        return accountId;
    }

    Instant getExpiresAt() {
        return expiresAt;
    }

    String getLastError() {
        return lastError;
    }

    Instant getLastCheckedAt() {
        return lastCheckedAt;
    }

    boolean isEnabled() {
        return enabled;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
