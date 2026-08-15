package com.tcms.identity;

import com.tcms.project.ProjectRepository;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import com.tcms.tenancy.CompanyStatus;
import com.tcms.tenancy.TokenContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
 * <p>Since M3 there is a fourth step, and it is the only one that <em>removes</em>: when the request
 * is authenticated by an API token, the answer is intersected with what that token's role confers
 * and bounded to the project it names. See {@link #cappedToToken} — including why it is an
 * intersection over this class's own answer rather than a second resolution beside it.
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
 * <h2>Memoized per transaction — which is NOT the cache the table comment specifies</h2>
 *
 * <p>Within one transaction, the same question is answered once: a resolved
 * {@link PermissionSet} is kept in a map bound to the transaction and read back for a repeated
 * key. It exists because one request legitimately asks several times — a gated write asks
 * {@code canView}, then {@code canDo}, then often {@code qualifierOf}, each a full resolution of
 * the same terms — and {@code AdminReach} multiplies that by every project in the company. The
 * memo dies with the transaction, so it cannot serve one request's answer to another, cannot
 * outlive a grant change (the next request re-reads), and needs no invalidation protocol —
 * with <strong>one exception</strong>: a service that mutates a grant-bearing table and asks
 * again <em>in the same transaction</em> must call {@link #forgetMemoizedAnswers()} between the
 * write and the question, or it is answered from before its own write. {@code RoleGrantService}
 * is the one such service today, and its self-lockout check is the call site.
 *
 * <h2>Not yet done here</h2>
 *
 * <p>One thing this deliberately does not implement, recorded rather than forgotten:
 * <ul>
 *   <li><strong>Cross-request caching</strong>. The table comment specifies a cache keyed on
 *       {@code (company_id, user_id, project_id)} — company id included — invalidated on
 *       any write to the five grant-bearing tables. Correctness first: an uncached
 *       evaluator is slow, a wrongly-keyed cache is a cross-company leak. The memo above is
 *       not it and must not grow into it — a lifetime longer than the transaction is what
 *       requires the invalidation protocol, and the safe place to add that is a deliberate
 *       slice, not a widened scope here.</li>
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
    private final RoleRepository roles;
    private final PermissionCatalogue catalogue;

    public PermissionEvaluator(
            AppUserRepository users,
            CompanyRepository companies,
            CompanyMembershipRepository memberships,
            ProjectRepository projects,
            RoleAssignmentRepository roleAssignments,
            InstanceRoleAssignmentRepository instanceRoleAssignments,
            RoleRepository roles,
            PermissionCatalogue catalogue) {
        this.users = users;
        this.companies = companies;
        this.memberships = memberships;
        this.projects = projects;
        this.roleAssignments = roleAssignments;
        this.instanceRoleAssignments = instanceRoleAssignments;
        this.roles = roles;
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
        return memoized(
                new MemoKey(MemoKey.Scope.PROJECT, companyId, userId, projectId,
                        TokenContext.currentOrNull()),
                () -> resolveOnProject(companyId, userId, projectId));
    }

    /** {@link #evaluate}'s resolution, behind the memo. Every comment in it predates the split. */
    private PermissionSet resolveOnProject(UUID companyId, UUID userId, UUID projectId) {
        // A token that names a project is inert on every other one, and the refusal happens here
        // rather than after the intersection: the project bound is about WHICH project, not about
        // which permissions, so intersecting first would answer "you hold case:read" about a
        // project this credential may not touch at all.
        TokenContext.ApiTokenPrincipal token = TokenContext.currentOrNull();
        if (token != null && token.projectId() != null && !token.projectId().equals(projectId)) {
            return PermissionSet.empty(catalogue);
        }

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

        Optional<Map<String, PermissionQualifier>> systemTerm = systemTermIfAccountActive(userId);
        if (systemTerm.isEmpty()) {
            return PermissionSet.empty(catalogue);
        }
        Map<String, PermissionQualifier> held = systemTerm.get();
        if (companyGateOpen(companyId, userId)) {
            // Never overridden: SYSTEM (already in `held`) and COMPANY-scope roles. Authority over
            // the company is not a per-project matter, and making it one would let anybody holding
            // project:members neutralise the company's administrator by granting them Viewer.
            merge(held, roleAssignments.findCompanyRoleHeldPermissions(companyId, userId));
            merge(held, projectTerm(companyId, userId, projectId));
        }
        return PermissionSet.of(catalogue, cappedToToken(held));
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
    private List<HeldPermissionRow> projectTerm(UUID companyId, UUID userId, UUID projectId) {
        return roleAssignments.hasProjectScopedAssignment(companyId, userId, projectId)
                ? roleAssignments.findProjectScopedHeldPermissions(companyId, userId, projectId)
                : roleAssignments.findInheritedProjectRoleHeldPermissions(companyId, userId);
    }

    /**
     * Folds one term's rows into the accumulating answer, <strong>strongest qualifier winning per
     * key</strong>.
     *
     * <p>This is the union step, and since §65 it is the <em>only</em> one — the qualifier merge and
     * the key union are the same operation over the same rows, where they used to be two traversals
     * of the same three terms in two methods that had to agree.
     *
     * <p>Note that a single term can itself contain one key twice: two grants (directly and through
     * a team, or through two teams) conferring it with different qualifiers. That is why the merge
     * is per row rather than per term, and why the projection's {@code DISTINCT} covers both columns
     * — collapsing to one row per key would decide it by row order.
     *
     * @throws IllegalStateException via {@link PermissionQualifier#strongest} if one key arrives
     *                               with two <em>different restrictive</em> qualifiers. Since §65
     *                               that surfaces here — on any permission resolution — rather than
     *                               only at the gate for that one key, and it should: the answer
     *                               now carries a qualifier per key over the wire, so a key whose
     *                               qualifier is inexpressible has nothing honest to serialize.
     *                               {@code SeedDataTest} keeps it unreachable against seeded data.
     */
    private static void merge(Map<String, PermissionQualifier> held, List<HeldPermissionRow> rows) {
        for (HeldPermissionRow row : rows) {
            held.merge(
                    row.getPermissionKey(),
                    PermissionQualifier.ofStored(row.getQualifier()),
                    PermissionQualifier::strongest);
        }
    }

    /**
     * <strong>Which rows</strong> the caller's grant of one permission covers on one project —
     * {@link PermissionQualifier#NONE} when they do not hold it at all.
     *
     * <h2>Why this is still its own method after §65 collapsed the computation</h2>
     *
     * <p>It used to resolve the three terms itself, per key. It no longer does — it reads
     * {@link #evaluate}'s answer — but the <em>signature</em> is the point and is unchanged: a gate
     * standing in front of a row knows which permission it is enforcing, and handing it a whole
     * {@link PermissionSet} to interrogate would put the choice of what to ask at the call site.
     * {@code ProjectAccess.canDoOnAuthored} and {@code canDoOnScope} are the only callers and both
     * want exactly this shape.
     *
     * <p>What the collapse bought: the replace-don't-union boundary and the strongest-wins merge are
     * now decided <strong>once</strong>. The old duplicate had to stay in step with {@link #evaluate}
     * by hand — a company-wide Test Engineer inherits {@code artifact:edit} as {@code PLAN_SCOPE} and
     * a project grant of Test Lead must replace it with {@code ALL}, a rule that was written out
     * twice — and nothing failed if one copy was edited. (That grant was de-qualified by
     * {@code V52}, §210, so the example no longer occurs; the rule it names is unchanged.)
     *
     * <p>The account gate, the company gate and the foreign-project check are all still applied,
     * because {@link #evaluate} applies them: a deactivated account or a project in another company
     * resolves to the empty set, and {@link PermissionSet#qualifierOf} answers {@code NONE} for
     * every key of it.
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
        return evaluate(userId, projectId).qualifierOf(permissionKey);
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
        return memoized(
                new MemoKey(MemoKey.Scope.COMPANY, companyId, userId, null,
                        TokenContext.currentOrNull()),
                () -> resolveCompanyWide(companyId, userId));
    }

    /** {@link #evaluateCompanyWide}'s resolution, behind the memo. */
    private PermissionSet resolveCompanyWide(UUID companyId, UUID userId) {
        Optional<Map<String, PermissionQualifier>> systemTerm = systemTermIfAccountActive(userId);
        if (systemTerm.isEmpty()) {
            return PermissionSet.empty(catalogue);
        }
        Map<String, PermissionQualifier> held = systemTerm.get();
        if (companyGateOpen(companyId, userId)) {
            // COMPANY-scope roles only — deliberately NOT the PROJECT-scope roles held company-wide.
            //
            // This is the same distinction the header above already draws, one case further on.
            // Inheriting Test Lead across every project means holding project:settings on each of
            // them; it does not mean holding company-level authority, and folding it in here would
            // let a company-wide Test Lead create projects because they lead all the existing ones.
            merge(held, roleAssignments.findCompanyRoleHeldPermissions(companyId, userId));
        }
        return PermissionSet.of(catalogue, cappedToToken(held));
    }

    /**
     * SYSTEM-scope permissions only. Callable with <strong>no company context open</strong>
     * — the login path needs it before any company is active, and
     * {@code instance_role_assignment} carries no RLS precisely so this works.
     */
    @Transactional(readOnly = true)
    public PermissionSet evaluateInstanceWide(UUID userId) {
        // No company in the key on purpose: this answer never reads CompanyContext, so keying it
        // on whichever company happens to be open would compute one answer and store it twice.
        return memoized(
                new MemoKey(MemoKey.Scope.SYSTEM, null, userId, null, TokenContext.currentOrNull()),
                () -> systemTermIfAccountActive(userId)
                        .map(held -> PermissionSet.of(catalogue, cappedToToken(held)))
                        .orElseGet(() -> PermissionSet.empty(catalogue)));
    }

    /**
     * Answers a repeated question from the transaction's memo instead of re-resolving it.
     *
     * <p>The map is bound to the current transaction through
     * {@link TransactionSynchronizationManager} and unbound when the transaction completes, so its
     * lifetime is exactly the transaction's — on a pooled thread nothing survives to the next
     * request. Outside any transaction (which no production path reaches; every entry point here
     * is {@code @Transactional}) it resolves directly and memoizes nothing, so correctness never
     * depends on the memo existing.
     *
     * <p>{@link PermissionSet} is immutable, so handing the same instance to every asker in the
     * transaction is safe. The key carries the token principal (a record, compared by value)
     * because the answer is capped to it — two resolutions under different credentials must not
     * share an entry, even though no request today swaps its credential mid-transaction.
     */
    private PermissionSet memoized(MemoKey key, Supplier<PermissionSet> resolve) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return resolve.get();
        }
        Map<MemoKey, PermissionSet> memo = boundMemo();
        if (memo == null) {
            memo = new HashMap<>();
            TransactionSynchronizationManager.bindResource(MEMO, memo);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(MEMO);
                }
            });
        }
        PermissionSet remembered = memo.get(key);
        if (remembered != null) {
            return remembered;
        }
        PermissionSet resolved = resolve.get();
        memo.put(key, resolved);
        return resolved;
    }

    /**
     * Drops every memoized answer for the rest of the current transaction.
     *
     * <p><strong>The one obligation the memo imposes:</strong> a service that mutates a
     * grant-bearing table ({@code role_assignment}, {@code instance_role_assignment},
     * {@code team_membership}, {@code role_permission}, {@code company_membership}) and asks this
     * evaluator again <em>inside the same transaction</em> must call this between the write and
     * the question — otherwise the question is answered from before its own write.
     * {@code RoleGrantService} is the only such service today, and its self-lockout check is what
     * breaks first if the call goes missing: the check would read the caller's pre-revoke
     * permissions and never trip, which {@code RoleGrantEndpointTest}'s lockout tests catch.
     */
    public void forgetMemoizedAnswers() {
        Map<MemoKey, PermissionSet> memo = boundMemo();
        if (memo != null) {
            memo.clear();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<MemoKey, PermissionSet> boundMemo() {
        return TransactionSynchronizationManager.isSynchronizationActive()
                ? (Map<MemoKey, PermissionSet>) TransactionSynchronizationManager.getResource(MEMO)
                : null;
    }

    /** The memo's resource binding key — one per process, private to this class. */
    private static final Object MEMO = new Object();

    /**
     * One question's identity in the memo. The scope discriminator keeps the three entry points
     * from colliding on the nullable columns alone; the token principal is part of the identity
     * because {@link #cappedToToken} makes it part of the answer.
     */
    private record MemoKey(
            Scope scope,
            UUID companyId,
            UUID userId,
            UUID projectId,
            TokenContext.ApiTokenPrincipal token) {

        private enum Scope { PROJECT, COMPANY, SYSTEM }
    }

    /**
     * Intersects the answer with what an API token may confer — <strong>the one narrowing in this
     * class, and the only place a token's bound is applied.</strong>
     *
     * <p>A no-op for an ordinary session, which is every request that is not from a CI job.
     *
     * <h2>Intersection, never union, and never a resolution of its own</h2>
     *
     * <p>{@code SessionTokens}' header refuses to put grant claims in the session JWT, quoting plan
     * §6b: <em>"a permission resolved inconsistently between two code paths is the bug class the
     * design exists to prevent."</em> A token principal that resolved its own permissions would be
     * that second path. So the terms above are resolved exactly as they are for the token's
     * <em>owner</em> — same gates, same union, same replace-don't-union boundary — and this method
     * only removes. Three properties follow by construction rather than by remembering:
     *
     * <ul>
     *   <li><strong>A token can never exceed its role.</strong> Roles spec §A6's list is an upper
     *       bound because it is one side of an intersection, not because somebody granted exactly
     *       it.</li>
     *   <li><strong>A token can never exceed its owner.</strong> A Test Engineer cannot mint
     *       themselves a Company Admin credential, and demoting a person shrinks every token they
     *       issued — on the next request, with no revocation step.</li>
     *   <li><strong>Every gate in the product is covered.</strong> Not the ones somebody remembered
     *       to guard: a new endpoint asks this evaluator, so it is capped the day it is written.</li>
     * </ul>
     *
     * <p>The qualifier is intersected too, through {@link PermissionQualifier#narrowedBy} — a
     * company-wide {@code ALL} capped by a role's {@code OWN} is {@code OWN}, so a token cannot use
     * its owner's seniority to reach rows its role is restricted from. Taking the owner's qualifier
     * whole would make the key-level intersection decorative on every {@code OWN}-qualified key.
     */
    private Map<String, PermissionQualifier> cappedToToken(Map<String, PermissionQualifier> held) {
        TokenContext.ApiTokenPrincipal token = TokenContext.currentOrNull();
        if (token == null) {
            return held;
        }

        Map<String, PermissionQualifier> roleGrant = new HashMap<>();
        merge(roleGrant, roles.findHeldPermissionsForRole(token.roleId()));

        Map<String, PermissionQualifier> capped = new HashMap<>();
        for (Map.Entry<String, PermissionQualifier> entry : held.entrySet()) {
            PermissionQualifier bound = roleGrant.get(entry.getKey());
            if (bound == null) {
                // Not conferred by the role at all. Dropped rather than kept-and-qualified: NONE
                // and "absent" are the same answer to PermissionSet, and building the map from the
                // intersection keeps that true without relying on it.
                continue;
            }
            PermissionQualifier narrowed = entry.getValue().narrowedBy(bound);
            if (narrowed != PermissionQualifier.NONE) {
                capped.put(entry.getKey(), narrowed);
            }
        }
        return capped;
    }

    /**
     * The SYSTEM term, or {@link Optional#empty()} when the account gate is shut.
     *
     * <p>The two outcomes are genuinely different answers and must not collapse into one
     * empty map: an empty <em>map</em> means "active, holds nothing yet" and the caller
     * goes on to read the company terms, while an empty <em>Optional</em> means "this
     * account holds nothing anywhere" and the caller must stop. Collapsing them would let
     * a DEACTIVATED user pick up company grants.
     *
     * <p>Mutable on purpose — the caller folds the two company terms into it.
     */
    private Optional<Map<String, PermissionQualifier>> systemTermIfAccountActive(UUID userId) {
        if (!users.existsByIdAndStatus(userId, UserStatus.ACTIVE)) {
            return Optional.empty();
        }
        Map<String, PermissionQualifier> held = new HashMap<>();
        merge(held, instanceRoleAssignments.findSystemHeldPermissions(userId));
        return Optional.of(held);
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
