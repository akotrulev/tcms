package com.tcms.identity;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * A user's membership of one company — and <strong>the gate every permission passes
 * through</strong>.
 *
 * <p>This is the one place the permission model is not a pure union, deliberately
 * (plan §6b). Membership is a <em>precondition</em> and contributes no permission of its
 * own: a non-member resolves to the empty set regardless of how many stale
 * {@code role_assignment} rows still name them, so offboarding is one status update
 * rather than a hunt across grants. See {@link PermissionEvaluator}.
 *
 * <p><strong>Membership conveys authentication into a company and zero project
 * visibility.</strong> A regular user sees only projects they hold a grant on — not the
 * company's project list, not names in a picker, not counts on a dashboard. With a
 * project being a whole client engagement, that is a confidentiality boundary rather
 * than tidiness (roles spec A1).
 */
@Entity
@Table(name = "company_membership")
@IdClass(CompanyMembershipId.class)
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class CompanyMembership {

    @Id
    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /**
     * Per-membership, not global (§6u). Neither client can see or overwrite the other's,
     * which is why the rendering path resolves through the <em>active</em> company.
     */
    @Column(name = "display_name", nullable = false)
    private String displayName;

    /**
     * {@code displayName}'s per-membership sibling, and <strong>nothing writes it</strong> (§156,
     * {@code ArchitectureRulesTest.WRITERLESS_BY_DECISION}): there is no upload, no URL field on
     * any request body, and no default derived from the address.
     *
     * <p>Unlike {@code Project.archivedAt} this one is already stated on the side that matters —
     * {@code api.UserRef}'s javadoc calls it "null for everyone today: nothing uploads one", so the
     * ~25 places a person appears on the wire are honest about it. It is mapped rather than dropped
     * because the read path is the finished half: resolve through the active company (§6u) and the
     * column is the only place a per-company picture could go. Adding one is a storage operation on
     * the contract, which is {@code IngestionJob.rawStorageKey}'s shape and not a wiring fix.
     */
    @Column(name = "avatar_url")
    private String avatarUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private MembershipStatus status;

    /**
     * Who invited this person — <strong>written from a real actor id and read by nothing</strong>
     * (§154, {@code ArchitectureRulesTest.READERLESS_BY_DECISION}).
     *
     * <p>The worse half of the pair with {@link #invitedAt}, because this one is not a clock
     * stamp: {@link #invite} and {@link #reinvite} both take it as an argument, so two call sites
     * carry an actor's id all the way to the database for a fact nothing has ever read back. It is
     * kept rather than dropped because {@code UserView} is the contract's {@code User} and carries
     * neither field — the reader this wants is an administrative column on the members screen, and
     * that is a contract change.
     */
    @Column(name = "invited_by")
    private UUID invitedBy;

    /** When, for {@link #invitedBy}'s reasons — written by two call sites, read by none (§154). */
    @Column(name = "invited_at", nullable = false)
    private Instant invitedAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    /** JPA only. */
    protected CompanyMembership() {
    }

    /**
     * Invites {@code userId} into {@code companyId}. Starts {@link MembershipStatus#INVITED}
     * and therefore conveys nothing until accepted.
     *
     * @param companyId taken from the operator or the inviting admin's active company,
     *                  never from a request body
     */
    public static CompanyMembership invite(
            UUID companyId, UUID userId, String displayName, UUID invitedBy, Instant now) {
        CompanyMembership membership = new CompanyMembership();
        membership.companyId = companyId;
        membership.userId = userId;
        membership.displayName = displayName;
        membership.status = MembershipStatus.INVITED;
        membership.invitedBy = invitedBy;
        membership.invitedAt = now;
        return membership;
    }

    /**
     * Accepting grants Viewer with no project access — the same default as SSO JIT
     * provisioning, so SSO cannot become a side door to more capability than the invite
     * flow grants (roles spec A7). The Viewer grant itself is a
     * {@link RoleAssignment}; this only opens the gate.
     */
    public void accept(Instant now) {
        this.status = MembershipStatus.ACTIVE;
        this.acceptedAt = now;
    }

    /** Offboards. Every grant in this company becomes inert without being deleted. */
    public void remove() {
        this.status = MembershipStatus.REMOVED;
    }

    /**
     * Brings an offboarded person back to {@link MembershipStatus#INVITED} so they can accept
     * again — the case {@code POST /users} would otherwise answer wrongly.
     *
     * <p>A {@code REMOVED} membership is filtered out of the directory, so an administrator
     * re-inviting a returning contractor sees an empty list and types the address as if it were
     * new. Without this the row is found, is not {@code ACTIVE}, and
     * {@link InvitationService#issueIfPending} happily mints a token that
     * {@code InvitationService#accept} then refuses — because redemption requires
     * {@code INVITED}. A <strong>dead link</strong>, reported to the inviter as a success.
     *
     * <p>{@code acceptedAt} is cleared: it would otherwise say the person accepted before they
     * were re-invited. Their old {@code role_assignment} rows are left alone and become live
     * again on acceptance, which is what {@link #remove()} promises — offboarding is one status
     * update, and so is its reversal.
     */
    public void reinvite(String displayName, UUID invitedBy, Instant now) {
        this.displayName = displayName;
        this.status = MembershipStatus.INVITED;
        this.invitedBy = invitedBy;
        this.invitedAt = now;
        this.acceptedAt = null;
    }

    public void rename(String newDisplayName) {
        this.displayName = newDisplayName;
    }

    public boolean isActive() {
        return status == MembershipStatus.ACTIVE;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public MembershipStatus getStatus() {
        return status;
    }

    public UUID getInvitedBy() {
        return invitedBy;
    }

    public Instant getInvitedAt() {
        return invitedAt;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public long getLockVersion() {
        return lockVersion;
    }
}
