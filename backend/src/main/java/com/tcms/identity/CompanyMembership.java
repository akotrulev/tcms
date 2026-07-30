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

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private MembershipStatus status;

    @Column(name = "invited_by")
    private UUID invitedBy;

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
