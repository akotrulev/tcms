package com.tcms.identity;

import com.tcms.project.ProjectRepository;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import com.tcms.tenancy.CompanyStatus;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single place effective permissions are computed (plan §6b, roles spec A1).
 *
 * <p>Nothing else may answer "may this user do X". Code checks permissions, never role
 * names, and there is no wildcard — both rules exist so that adding a custom-role
 * designer later is additive rather than a rewrite of every call site.
 *
 * <h2>The algorithm</h2>
 *
 * <p>A union of allows with no explicit deny, behind three gates:
 *
 * <pre>
 * 0.  account gate    the account is ACTIVE                      -> else {} everywhere
 * 1.  SYSTEM term     instance_role_assignment for the user      &lt;- NOT company-gated
 * 2.  company gate    ACTIVE membership AND the company is ACTIVE
 * 3.  COMPANY term      role_assignment, project_id IS NULL      | user's own grants
 * 4.  PROJECT term      role_assignment, project_id = :project   | + any of their teams'
 * </pre>
 *
 * <h2>Why the SYSTEM term is not behind the company gate</h2>
 *
 * <p>{@code role_assignment}'s table comment lists the membership gate above the SYSTEM
 * term, which reads as though it gates everything. It cannot, and this is the one place
 * the implementation resolves an ambiguity in the written spec rather than transcribing
 * it:
 *
 * <ul>
 *   <li>An Instance Admin <strong>provisions</strong> companies (plan §6s). At that
 *       moment there is no membership row to gate on — they would be locked out of the
 *       company they just created.</li>
 *   <li>Instance Admin is defined as "everything, every company" (roles spec A2), and
 *       reaching into client content is a deliberate, disclosed, audited decision
 *       (handoff §3) — not something a membership row mediates.</li>
 *   <li>Suspension is recoverable only if SYSTEM survives it. If the company gate
 *       covered SYSTEM, suspending a company would make it permanently unadministrable,
 *       and the policy is explicitly <em>suspend, never delete</em>.</li>
 * </ul>
 *
 * <p>The account gate (0) does still cover SYSTEM: a deactivated operator holds nothing,
 * or deactivation would not be the instance-wide off switch it is documented to be.
 *
 * <h2>Why the company id is not a parameter</h2>
 *
 * <p>It is read from {@link CompanyContext}, which is populated from the authenticated
 * session and never from client input. A {@code companyId} argument could disagree with
 * the open context; RLS would then scope the rows to the context while the predicate
 * scoped them to the argument, and the caller would get an empty set that looks like a
 * missing grant. Taking one source removes the disagreement rather than detecting it.
 *
 * <h2>Not yet done here</h2>
 *
 * <p>Two things this deliberately does not implement, both recorded rather than
 * forgotten:
 * <ul>
 *   <li><strong>The {@code OWN} qualifier</strong> (roles spec A5) — {@code case:edit},
 *       {@code case:delete} and {@code evidence:delete} apply to content the caller
 *       authored when held via Test Engineer. That is resolved against {@code created_by}
 *       at the call site, because this type cannot see the row being acted on.</li>
 *   <li><strong>Caching</strong>. The table comment specifies a cache keyed on
 *       {@code (company_id, user_id, project_id)} — company id included — invalidated on
 *       any write to the five grant-bearing tables. Correctness first: an uncached
 *       evaluator is slow, a wrongly-keyed cache is a cross-company leak.</li>
 * </ul>
 */
@Service
public class PermissionEvaluator {

    private final AppUserRepository users;
    private final CompanyRepository companies;
    private final CompanyMembershipRepository memberships;
    private final ProjectRepository projects;
    private final RoleAssignmentRepository roleAssignments;
    private final InstanceRoleAssignmentRepository instanceRoleAssignments;
    private final PermissionCatalogue catalogue;

    public PermissionEvaluator(
            AppUserRepository users,
            CompanyRepository companies,
            CompanyMembershipRepository memberships,
            ProjectRepository projects,
            RoleAssignmentRepository roleAssignments,
            InstanceRoleAssignmentRepository instanceRoleAssignments,
            PermissionCatalogue catalogue) {
        this.users = users;
        this.companies = companies;
        this.memberships = memberships;
        this.projects = projects;
        this.roleAssignments = roleAssignments;
        this.instanceRoleAssignments = instanceRoleAssignments;
        this.catalogue = catalogue;
    }

    /**
     * Effective permissions on one project, in the currently active company.
     *
     * @param projectId the project; must not be null — use
     *                  {@link #evaluateCompanyWide(UUID)} for a company-level question,
     *                  so widening the scope is always explicit at the call site
     * @throws IllegalStateException if no company context is open. Company-owned data
     *                               must never be reached without one.
     */
    @Transactional(readOnly = true)
    public PermissionSet evaluate(UUID userId, UUID projectId) {
        if (projectId == null) {
            throw new IllegalArgumentException(
                    "projectId must not be null. Call evaluateCompanyWide(userId) for a "
                            + "company-level decision — a null here would silently answer a different "
                            + "question than the caller asked.");
        }
        UUID companyId = CompanyContext.require();

        // The project must belong to the ACTIVE company, and this check is not redundant
        // with the query predicates below. COMPANY-scope grants carry no project id — they
        // apply to every project in the company — so without this, asking about a FOREIGN
        // project while Acme is active would union in Acme's company-wide grants and answer
        // "yes". That answers "does this user hold X somewhere in Acme", not the question
        // the caller asked. Resolved through the company-filtered lookup, so RLS and the
        // Hibernate filter both apply; an unreachable project is indistinguishable from a
        // non-existent one, which is the same rule the API surface follows (§6t: NOT FOUND,
        // never FORBIDDEN, so the response cannot be used as an oracle).
        if (projects.findScopedById(projectId).isEmpty()) {
            return PermissionSet.empty(catalogue);
        }

        Optional<Set<String>> systemKeys = systemKeysIfAccountActive(userId);
        if (systemKeys.isEmpty()) {
            return PermissionSet.empty(catalogue);
        }
        Set<String> keys = systemKeys.get();
        if (companyGateOpen(companyId, userId)) {
            keys.addAll(roleAssignments.findCompanyScopedPermissionKeys(companyId, userId));
            keys.addAll(roleAssignments.findProjectScopedPermissionKeys(companyId, userId, projectId));
        }
        return PermissionSet.of(catalogue, keys);
    }

    /**
     * Effective permissions at company level — the COMPANY and SYSTEM terms only, with no
     * project's grants folded in.
     *
     * <p>This is the right question for company-level capabilities
     * ({@code company:settings}, {@code project:create}). It is deliberately <em>not</em>
     * "permissions on every project": a Test Lead on one project holds
     * {@code project:settings} there and must not appear to hold it company-wide.
     */
    @Transactional(readOnly = true)
    public PermissionSet evaluateCompanyWide(UUID userId) {
        UUID companyId = CompanyContext.require();
        Optional<Set<String>> systemKeys = systemKeysIfAccountActive(userId);
        if (systemKeys.isEmpty()) {
            return PermissionSet.empty(catalogue);
        }
        Set<String> keys = systemKeys.get();
        if (companyGateOpen(companyId, userId)) {
            keys.addAll(roleAssignments.findCompanyScopedPermissionKeys(companyId, userId));
        }
        return PermissionSet.of(catalogue, keys);
    }

    /**
     * SYSTEM-scope permissions only. Callable with <strong>no company context open</strong>
     * — the login path needs it before any company is active, and
     * {@code instance_role_assignment} carries no RLS precisely so this works.
     */
    @Transactional(readOnly = true)
    public PermissionSet evaluateInstanceWide(UUID userId) {
        return systemKeysIfAccountActive(userId)
                .map(keys -> PermissionSet.of(catalogue, keys))
                .orElseGet(() -> PermissionSet.empty(catalogue));
    }

    /**
     * The SYSTEM term, or {@link Optional#empty()} when the account gate is shut.
     *
     * <p>The two outcomes are genuinely different answers and must not collapse into one
     * empty set: an empty <em>set</em> means "active, holds nothing yet" and the caller
     * goes on to read the company terms, while an empty <em>Optional</em> means "this
     * account holds nothing anywhere" and the caller must stop. Collapsing them would let
     * a DEACTIVATED user pick up company grants.
     */
    private Optional<Set<String>> systemKeysIfAccountActive(UUID userId) {
        if (!users.existsByIdAndStatus(userId, UserStatus.ACTIVE)) {
            return Optional.empty();
        }
        return Optional.of(new HashSet<>(instanceRoleAssignments.findSystemPermissionKeys(userId)));
    }

    /**
     * Membership must be ACTIVE and the company must not be suspended.
     *
     * <p>Both are gates, not terms: neither contributes a permission, and either being
     * shut zeroes the COMPANY and PROJECT terms while leaving SYSTEM intact. Suspension is
     * honoured here because the policy is suspend-never-delete (plan §6s) — a suspended
     * company keeps all its rows, so nothing else would stop its users working.
     */
    private boolean companyGateOpen(UUID companyId, UUID userId) {
        return companies.existsByIdAndStatus(companyId, CompanyStatus.ACTIVE)
                && memberships.existsByCompanyIdAndUserIdAndStatus(
                        companyId, userId, MembershipStatus.ACTIVE);
    }
}
