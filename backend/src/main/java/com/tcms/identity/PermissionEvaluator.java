package com.tcms.identity;

import com.tcms.project.ProjectRepository;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import com.tcms.tenancy.CompanyStatus;
import java.util.HashSet;
import java.util.List;
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
 * <p>One thing this deliberately does not implement, recorded rather than forgotten:
 * <ul>
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
            // Never overridden: SYSTEM (already in `keys`) and COMPANY-scope roles. Authority over
            // the company is not a per-project matter, and making it one would let anybody holding
            // project:members neutralise the company's administrator by granting them Viewer.
            keys.addAll(roleAssignments.findCompanyRolePermissionKeys(companyId, userId));
            keys.addAll(projectRoleKeys(companyId, userId, projectId));
        }
        return PermissionSet.of(catalogue, keys);
    }

    /**
     * The working role that applies <strong>on this project</strong> — the project's own grants if
     * it has any, otherwise the company-wide role inherited into it.
     *
     * <h2>Replace, not union, and that is the whole feature</h2>
     *
     * <p>A grant naming the project <em>replaces</em> the inherited one rather than adding to it,
     * <strong>including when it confers less</strong>: a company-wide Test Lead granted Viewer on
     * one project is a Viewer there. That is the only place in this evaluator where adding a row
     * can <em>remove</em> a permission, and it is deliberate — an explicit project grant is a
     * statement about that project, where {@link PermissionQualifier}'s "an additional grant must
     * never take capability away" is about grants accumulating by accident (two teams, a company
     * role and a project role) with nobody having said anything about this project at all.
     *
     * <p>The switch is {@link RoleAssignmentRepository#hasProjectScopedAssignment}, which asks
     * whether an <em>assignment</em> exists and never what it grants — see its header for why
     * "are there project keys" would silently union the two and undo every restriction.
     *
     * <p>Union is still the rule <em>within</em> a level: two project grants on the same project
     * (directly and through a team) accumulate exactly as before, and so do two company-wide ones.
     * Only the boundary between the two levels is an override.
     */
    private Set<String> projectRoleKeys(UUID companyId, UUID userId, UUID projectId) {
        return new HashSet<>(
                roleAssignments.hasProjectScopedAssignment(companyId, userId, projectId)
                        ? roleAssignments.findProjectScopedPermissionKeys(companyId, userId, projectId)
                        : roleAssignments.findInheritedProjectRolePermissionKeys(companyId, userId));
    }

    /**
     * <strong>Which rows</strong> the caller's grant of one permission covers on one project —
     * {@link PermissionQualifier#NONE} when they do not hold it at all.
     *
     * <h2>Why this is a second method and not a field on {@link PermissionSet}</h2>
     *
     * <p>{@link #evaluate} answers "is it held", which is what every consumer but one asks. This
     * answers "over what", which only a gate standing in front of a specific row can use — and that
     * gate knows which permission it is enforcing, so it asks about one key rather than being handed
     * a map of qualifiers it would discard.
     *
     * <p>The gates are the same three, in the same order, and re-checking them here is not
     * belt-and-braces: this is reachable independently of {@link #evaluate}, and a deactivated
     * account or a foreign project must answer {@code NONE} on this path too. Answering "ALL"
     * because "the caller must already have passed the coarse gate" is an assumption about a caller
     * this class cannot see.
     *
     * <p>{@code NONE} is also what a caller who holds the permission through no grant at all gets,
     * so a gate that consults only this method — without {@link PermissionSet#has} first — is still
     * correct. That is deliberate: two methods that must be called in the right order to be safe are
     * one method with a footgun.
     */
    @Transactional(readOnly = true)
    public PermissionQualifier qualifierOf(UUID userId, UUID projectId, String permissionKey) {
        if (projectId == null) {
            throw new IllegalArgumentException(
                    "projectId must not be null. A qualifier is asked about a row, and every "
                            + "OWN-qualified permission is project-scoped.");
        }
        // Validates the key against the catalogue, so a typo throws here rather than resolving to
        // NONE — which would read as "this user is not allowed" and be investigated against the
        // grants, where nothing is wrong. Same reason PermissionSet#has validates.
        catalogue.requireKnown(permissionKey);

        UUID companyId = CompanyContext.require();
        if (projects.findScopedById(projectId).isEmpty()) {
            return PermissionQualifier.NONE;
        }
        if (!users.existsByIdAndStatus(userId, UserStatus.ACTIVE)) {
            return PermissionQualifier.NONE;
        }

        PermissionQualifier held =
                strongestOf(instanceRoleAssignments.findSystemQualifiers(userId, permissionKey));
        if (companyGateOpen(companyId, userId)) {
            held = held.strongest(strongestOf(
                    roleAssignments.findCompanyRoleQualifiers(companyId, userId, permissionKey)));
            // The same replace-don't-union rule as evaluate(), and it has to be the same or the two
            // disagree about which grant is in force: a company-wide Test Engineer inherits
            // artifact:edit as PLAN_SCOPE, and a project grant of Test Lead must make it ALL there
            // rather than merging to the stronger of the two by accident.
            held = held.strongest(strongestOf(
                    roleAssignments.hasProjectScopedAssignment(companyId, userId, projectId)
                            ? roleAssignments.findProjectScopedQualifiers(
                                    companyId, userId, projectId, permissionKey)
                            : roleAssignments.findInheritedProjectRoleQualifiers(
                                    companyId, userId, permissionKey)));
        }
        return held;
    }

    /** No rows means the term grants nothing; otherwise the strongest qualifier in it. */
    private static PermissionQualifier strongestOf(List<String> stored) {
        PermissionQualifier strongest = PermissionQualifier.NONE;
        for (String value : stored) {
            strongest = strongest.strongest(PermissionQualifier.ofStored(value));
        }
        return strongest;
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
            // COMPANY-scope roles only — deliberately NOT the PROJECT-scope roles held company-wide.
            //
            // This is the same distinction the header above already draws, one case further on.
            // Inheriting Test Lead across every project means holding project:settings on each of
            // them; it does not mean holding company-level authority, and folding it in here would
            // let a company-wide Test Lead create projects because they lead all the existing ones.
            keys.addAll(roleAssignments.findCompanyRolePermissionKeys(companyId, userId));
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
