package com.tcms.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A SYSTEM-scope grant — in practice, Instance Admin.
 *
 * <p><strong>Instance-level: no {@code company_id}, no RLS, no company filter</strong>
 * (ERD §0.1 exception list). See {@link RoleAssignment} for why this is a separate table
 * rather than a nullable discriminator, and why widening the policy instead would have
 * broken the "unset GUC yields zero rows" invariant.
 *
 * <p>Being outside RLS is also what makes the login path work: minting a token requires
 * reading SYSTEM grants <em>before</em> any company is active.
 *
 * <h2>Users only — deliberately no team</h2>
 *
 * <p>A team is company-owned, so granting instance-wide power to a company-scoped team
 * would let a client-controlled entity confer operator rights — an escalation path whose
 * membership the client administers. Instance Admin is granted per person by the operator
 * (plan §6s). There is no {@code forTeam} factory here and there must not be one.
 */
@Entity
@Table(name = "instance_role_assignment")
public class InstanceRoleAssignment {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "role_id", nullable = false, updatable = false)
    private UUID roleId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "granted_by")
    private UUID grantedBy;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    /** JPA only. */
    protected InstanceRoleAssignment() {
    }

    /**
     * Grants a SYSTEM-scope role to a person.
     *
     * @param grantedBy the granting operator, or {@code null} for the bootstrapped first
     *                  Instance Admin — there is nobody to attribute that one to
     */
    public static InstanceRoleAssignment forUser(
            UUID id, UUID roleId, UUID userId, UUID grantedBy, Instant now) {
        InstanceRoleAssignment assignment = new InstanceRoleAssignment();
        assignment.id = id;
        assignment.roleId = roleId;
        assignment.userId = userId;
        assignment.grantedBy = grantedBy;
        assignment.grantedAt = now;
        return assignment;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRoleId() {
        return roleId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getGrantedBy() {
        return grantedBy;
    }

    public Instant getGrantedAt() {
        return grantedAt;
    }
}
