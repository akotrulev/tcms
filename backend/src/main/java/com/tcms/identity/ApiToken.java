package com.tcms.identity;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * A non-human credential: the way a CI job reaches this instance.
 *
 * <p><strong>The table has been in the schema since {@code V1} and had no writer until M3.</strong>
 * Two foreign keys have been pointing at it the whole time — {@code test_execution
 * .executed_by_token} and {@code audit_event.actor_token_id} — so the product has been able to
 * <em>record</em> that a token did something since M0 and has never been able to issue one.
 *
 * <h2>A token carries a ROLE, never the rights of the person who issued it</h2>
 *
 * <p>{@link #roleId} is the whole security argument, and {@code V1}'s own column comment states it:
 * a CI token holds Automation Service and nothing more. The resolution is in
 * {@link PermissionEvaluator} and it is an <em>intersection</em> — the token's role capped by its
 * owner's own grants, never a union — so three properties hold by construction rather than by
 * remembering:
 *
 * <ul>
 *   <li>a token can never exceed its role;</li>
 *   <li>a token can never exceed its owner, so demoting a person shrinks the tokens they
 *       issued;</li>
 *   <li>{@link #projectId}, when set, is a hard scope: the token is inert on every other
 *       project.</li>
 * </ul>
 *
 * <h2>The secret is not here, and neither is its digest</h2>
 *
 * <p>{@code V36} moved the digest to {@code api_token_lookup}, which is instance-level and
 * readable before any company context exists — the one question that must be answered before
 * this row can be read at all. What stays here is the <strong>lifecycle</strong>:
 * {@link #expiresAt} and {@link #revokedAt} are checked <em>after</em> the company is open, so
 * revocation is enforced inside the isolation boundary rather than outside it. {@link #tokenPrefix}
 * is a deliberate partial disclosure, and exists because a list otherwise has nothing to identify a
 * row by except a name somebody typed.
 *
 * <h2>No {@code @Version}</h2>
 *
 * <p>{@code V1} gave this table no {@code lock_version} and this class does not add one. The only
 * mutation is {@link #revoke}, which is idempotent and monotonic: two administrators revoking the
 * same token concurrently both want it revoked, and a 409 telling one of them to reload and reapply
 * would be advice to do the thing that already happened. That is the opposite of {@code Condition},
 * where a lost update silently discards somebody's edit.
 */
@Entity
@Table(name = "api_token")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class ApiToken {

    /** {@code V36}'s prefix length. Long enough to recognise, far too short to guess from. */
    public static final int PREFIX_LENGTH = 12;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    /** Unique per company among live tokens ({@code V36}) — a revoked name is reusable. */
    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "token_prefix", nullable = false, updatable = false)
    private String tokenPrefix;

    /**
     * The person answerable for it. Their grants cap what the token can do, so this is a
     * permission input and not merely provenance.
     */
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    /** The role the token carries. Not the owner's roles — see the class comment. */
    @Column(name = "role_id", nullable = false, updatable = false)
    private UUID roleId;

    /** One project, or null for every project the owner can reach. */
    @Column(name = "project_id", updatable = false)
    private UUID projectId;

    @Column(name = "expires_at", updatable = false)
    private Instant expiresAt;

    /**
     * Written by the ingestion path, not by this slice.
     *
     * <p>Mapped so the column is not invisible to {@code ddl-auto: validate}'s counterpart —
     * an unmapped column reads as a schema nobody owns — and deliberately never set: a write on
     * every authenticated request is a write-amplification decision that wants the batching answer
     * {@code plan_snapshot}'s tick already has, not a naive {@code UPDATE} inside a servlet filter.
     */
    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA only. */
    protected ApiToken() {
    }

    /**
     * @param tokenPrefix the leading characters of the secret. This type never sees the secret
     *                    itself and never sees its digest, so there is no path by which either
     *                    could be logged or persisted here — {@code ApiTokenSecrets} holds one and
     *                    {@code ApiTokenLookup} holds the other.
     * @param companyId   taken from {@link com.tcms.tenancy.CompanyContext}, never from a request
     * @param expiresAt   null for a token that does not expire. Permitted, because a CI token that
     *                    silently stops working mid-release is worse than one an operator revokes.
     */
    public static ApiToken issue(
            UUID id,
            UUID companyId,
            String name,
            String tokenPrefix,
            UUID ownerId,
            UUID roleId,
            UUID projectId,
            Instant expiresAt,
            Instant now) {

        ApiToken token = new ApiToken();
        token.id = id;
        token.companyId = companyId;
        token.name = name;
        token.tokenPrefix = tokenPrefix;
        token.ownerId = ownerId;
        token.roleId = roleId;
        token.projectId = projectId;
        token.expiresAt = expiresAt;
        token.createdAt = now;
        return token;
    }

    /**
     * Withdraws the credential. Idempotent, and the first revocation is the one that counts —
     * re-revoking must not move the timestamp, or the audit trail would report the moment somebody
     * clicked a second time as the moment the token stopped working.
     */
    public void revoke(Instant now) {
        if (revokedAt == null) {
            this.revokedAt = now;
        }
    }

    /**
     * Whether the credential may authenticate a request right now.
     *
     * <p>Both halves are compared against the caller's clock rather than {@code now()} in SQL, for
     * {@link MembershipInvitation#isRedeemable}'s reason: a predicate split between the query and
     * the code is one that can disagree with itself across a transaction boundary.
     */
    public boolean isUsable(Instant now) {
        return revokedAt == null && (expiresAt == null || now.isBefore(expiresAt));
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public String getName() {
        return name;
    }

    public String getTokenPrefix() {
        return tokenPrefix;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public UUID getRoleId() {
        return roleId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
