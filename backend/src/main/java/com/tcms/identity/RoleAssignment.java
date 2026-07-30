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
 * A COMPANY- or PROJECT-scope grant: one role, to a user <em>or</em> a team, on one
 * target.
 *
 * <p><strong>SYSTEM-scope grants are not here</strong> — they live in
 * {@link InstanceRoleAssignment}. That split is not stylistic: the RLS policy is a bare
 * {@code company_id = current_setting(...)} equality, and {@code NULL = anything} is
 * never true, so a SYSTEM grant modelled as a nullable {@code company_id} would be
 * invisible in every company context, permanently. It presents as an authorization
 * denial rather than a schema fault, and the tempting repair — {@code BYPASSRLS} on the
 * app role — destroys isolation for all 56 protected tables. Measured, not reasoned; see
 * {@code docs/audits/2026-07-30-v1-schema-first-execution.md}.
 *
 * <h2>Scope is encoded in {@link #projectId}, not in a column</h2>
 *
 * <ul>
 *   <li>{@code projectId == null} → <strong>COMPANY</strong> scope: covers every project
 *       in the company, <em>present and future</em>. That is what makes Company Admin
 *       work, and why nothing else should be granted at that scope casually.</li>
 *   <li>{@code projectId != null} → <strong>PROJECT</strong> scope.</li>
 * </ul>
 *
 * <p>{@link PermissionEvaluator} reads the two cases with two separate queries rather
 * than one nullable parameter — deliberately, because a bound NULL comparing equal to
 * nothing is precisely the trap that produced the defect above.
 *
 * <p>The database enforces "user xor team" via {@code ck_role_assignment_subject}; the
 * factory methods are the only construction path, so the invariant holds before the
 * insert too.
 */
@Entity
@Table(name = "role_assignment")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class RoleAssignment {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "role_id", nullable = false, updatable = false)
    private UUID roleId;

    /** Exactly one of {@code userId} / {@link #teamId} is set. */
    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Column(name = "team_id", updatable = false)
    private UUID teamId;

    /** Null means COMPANY scope — see the class comment. */
    @Column(name = "project_id", updatable = false)
    private UUID projectId;

    @Column(name = "granted_by")
    private UUID grantedBy;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    /** JPA only. */
    protected RoleAssignment() {
    }

    /** Grants {@code roleId} to a user on one project. */
    public static RoleAssignment forUserOnProject(
            UUID id, UUID companyId, UUID roleId, UUID userId, UUID projectId, UUID grantedBy, Instant now) {
        return create(id, companyId, roleId, userId, null, requireProject(projectId), grantedBy, now);
    }

    /** Grants {@code roleId} to a team on one project. */
    public static RoleAssignment forTeamOnProject(
            UUID id, UUID companyId, UUID roleId, UUID teamId, UUID projectId, UUID grantedBy, Instant now) {
        return create(id, companyId, roleId, null, teamId, requireProject(projectId), grantedBy, now);
    }

    /**
     * Grants {@code roleId} to a user across the whole company — every project, present
     * and future. This is the Company Admin shape; reach for it deliberately.
     */
    public static RoleAssignment forUserOnCompany(
            UUID id, UUID companyId, UUID roleId, UUID userId, UUID grantedBy, Instant now) {
        return create(id, companyId, roleId, userId, null, null, grantedBy, now);
    }

    /** Grants {@code roleId} to a team across the whole company. */
    public static RoleAssignment forTeamOnCompany(
            UUID id, UUID companyId, UUID roleId, UUID teamId, UUID grantedBy, Instant now) {
        return create(id, companyId, roleId, null, teamId, null, grantedBy, now);
    }

    private static RoleAssignment create(
            UUID id,
            UUID companyId,
            UUID roleId,
            UUID userId,
            UUID teamId,
            UUID projectId,
            UUID grantedBy,
            Instant now) {
        if ((userId == null) == (teamId == null)) {
            throw new IllegalArgumentException(
                    "A role assignment names exactly one subject — a user or a team, never both and "
                            + "never neither (ck_role_assignment_subject)");
        }
        RoleAssignment assignment = new RoleAssignment();
        assignment.id = id;
        assignment.companyId = companyId;
        assignment.roleId = roleId;
        assignment.userId = userId;
        assignment.teamId = teamId;
        assignment.projectId = projectId;
        assignment.grantedBy = grantedBy;
        assignment.grantedAt = now;
        return assignment;
    }

    private static UUID requireProject(UUID projectId) {
        if (projectId == null) {
            throw new IllegalArgumentException(
                    "A PROJECT-scope grant needs a project. For a company-wide grant use "
                            + "forUserOnCompany/forTeamOnCompany, so the widening is explicit at the call site");
        }
        return projectId;
    }

    /** COMPANY scope covers every project in the company, present and future. */
    public boolean isCompanyScoped() {
        return projectId == null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public UUID getRoleId() {
        return roleId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getGrantedBy() {
        return grantedBy;
    }

    public Instant getGrantedAt() {
        return grantedAt;
    }
}
