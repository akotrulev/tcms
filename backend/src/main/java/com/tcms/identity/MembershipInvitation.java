package com.tcms.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * A redeemable invitation into one company: the bridge between the {@code INVITED}
 * membership {@code provision-company} creates and an account someone can actually sign
 * in with.
 *
 * <p><strong>Instance-level: no {@code company_id} discriminator in the tenancy sense, no
 * RLS, no company filter</strong> (ERD §0.1 exception list, decided in {@code V6}). The
 * accept path runs before authentication, so no company context can exist when the token
 * is looked up, and {@link com.tcms.tenancy.CompanyContext} is never populated from
 * request input. {@link #companyId} names the company the invitation admits the holder
 * <em>to</em> — a target, exactly like {@code InstanceAuditEvent.companyId}, not a
 * discriminator. {@code CompanyFilterTest} derives the entities that must carry the filter
 * from {@code pg_class.relrowsecurity}, so this one is required <em>not</em> to have it.
 *
 * <h2>The token is never stored</h2>
 *
 * <p>Only {@link #tokenHash} — a hex SHA-256 — lives here. The plaintext secret is
 * returned to the issuer once, at {@link #issue}, and cannot be recovered afterwards. A
 * database read therefore yields nothing redeemable, which is the property that makes an
 * un-RLS-protected table holding a capability acceptable at all.
 *
 * <h2>Single-use, and the two ways it can be spent</h2>
 *
 * <p>Redemption sets {@link #consumedAt} rather than deleting the row, so a second attempt
 * with the same token is distinguishable <em>in the audit trail</em> from a token that
 * never existed. The API response deliberately cannot tell them apart — invalid, expired
 * and already-consumed are one outcome to a caller, because three outcomes are an oracle
 * for whether an address is on the instance (§6t's rule, applied to a pre-auth surface).
 */
@Entity
@Table(name = "membership_invitation")
public class MembershipInvitation {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** The company this admits the holder to. A target, not a tenancy discriminator. */
    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Hex SHA-256 of the secret. The secret itself is never persisted. */
    @Column(name = "token_hash", nullable = false, updatable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** The issuer, or null for an unattended CLI run — no synthetic actor is invented. */
    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    /** JPA only. */
    protected MembershipInvitation() {
    }

    /**
     * Issues an invitation.
     *
     * @param tokenHash the hex SHA-256 of the secret. The caller holds the plaintext and
     *                  is responsible for delivering it; this type never sees it, so
     *                  there is no path by which it could be logged or persisted here.
     */
    public static MembershipInvitation issue(
            UUID id,
            UUID companyId,
            UUID userId,
            String tokenHash,
            Instant expiresAt,
            UUID createdBy,
            Instant now) {
        MembershipInvitation invitation = new MembershipInvitation();
        invitation.id = id;
        invitation.companyId = companyId;
        invitation.userId = userId;
        invitation.tokenHash = tokenHash;
        invitation.expiresAt = expiresAt;
        invitation.createdBy = createdBy;
        invitation.createdAt = now;
        return invitation;
    }

    /**
     * Spends the invitation. Idempotence is deliberately <em>not</em> offered: a second
     * call is a second redemption attempt and the caller must have rejected it already via
     * {@link #isRedeemable}.
     */
    public void consume(Instant now) {
        if (consumedAt != null) {
            throw new IllegalStateException(
                    "Invitation " + id + " was already consumed at " + consumedAt
                            + ". Check isRedeemable() before consuming — a silent re-consume "
                            + "would let one token activate an account twice.");
        }
        this.consumedAt = now;
    }

    /**
     * Unconsumed and unexpired.
     *
     * <p>Expiry is compared against the caller's clock rather than {@code now()} in SQL so
     * the whole redemption decision is made in one place with one time source — a
     * predicate split between the query and the code is one that can disagree with itself
     * across a transaction boundary.
     */
    public boolean isRedeemable(Instant now) {
        return consumedAt == null && now.isBefore(expiresAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public long getLockVersion() {
        return lockVersion;
    }
}
