package com.tcms.identity;

import com.tcms.project.ProjectAccess;
import com.tcms.tenancy.CompanyContext;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code identity}'s answer to {@code project}'s question — the adapter that keeps the module
 * edge one-way.
 *
 * <p>{@code project} declares {@link ProjectAccess} because it cannot import this module (that
 * would close a cycle; see the interface's header). This class lives on the side that already
 * depends on {@code project}, so implementing it adds no edge to the graph.
 *
 * <p>It is deliberately thin. Every authorization decision is delegated to
 * {@link PermissionEvaluator} — the single place effective permissions are computed — and the
 * only logic here is choosing which of several role names to display, which is not an
 * authorization decision at all.
 */
@Service
public class IdentityProjectAccess implements ProjectAccess {

    /**
     * The permission that gates seeing a project.
     *
     * <p>Held by all six built-in roles (roles spec §6b), so any project grant makes a project
     * visible — the intent. Expressed as a permission rather than as "holds any grant" because
     * the latter keeps quietly working if a role is ever defined without it.
     */
    private static final String VIEW_PROJECT = "project:read";

    private final PermissionEvaluator permissions;
    private final RoleAssignmentRepository roleAssignments;

    public IdentityProjectAccess(
            PermissionEvaluator permissions, RoleAssignmentRepository roleAssignments) {
        this.permissions = permissions;
        this.roleAssignments = roleAssignments;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean canView(UUID userId, UUID projectId) {
        return canDo(userId, projectId, VIEW_PROJECT);
    }

    /**
     * {@inheritDoc}
     *
     * <p>One line, and it is the same line {@link #canView} used to be — which is the point of
     * routing both through here rather than giving the write its own evaluator call. The two gates
     * differ in the permission they name and in nothing else, so there is one place the evaluator
     * is asked and one place a mistake in asking it could live.
     */
    @Override
    @Transactional(readOnly = true)
    public boolean canDo(UUID userId, UUID projectId, String permission) {
        // The evaluator already returns the empty set for a project outside the active
        // company, so a foreign id needs no separate check here — and adding one would be a
        // second place the boundary is decided.
        return permissions.evaluate(userId, projectId).has(permission);
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@link PermissionEvaluator#evaluateCompanyWide} rather than {@link
     * PermissionEvaluator#evaluate} over every project: that method exists precisely for the
     * company-level capabilities its own javadoc names, {@code project:create} among them, and it
     * returns the SYSTEM and COMPANY terms with no project's grants folded in. Asking the
     * project-scoped question here and OR-ing the answers would make leading one project a licence
     * to create another.
     */
    @Override
    @Transactional(readOnly = true)
    public boolean canDoInCompany(UUID userId, String permission) {
        return permissions.evaluateCompanyWide(userId).has(permission);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Three lines, and each is a decision:
     *
     * <ul>
     *   <li>{@link PermissionQualifier#NONE} is a refusal — the caller does not hold the permission
     *       at all, so this method needs no separate {@code canDo} call in front of it and callers
     *       cannot get the order wrong.</li>
     *   <li>{@link PermissionQualifier#ALL} is a yes regardless of who wrote the row. A Test Lead
     *       deleting a colleague's case is the ordinary case, not an exception.</li>
     *   <li>{@link PermissionQualifier#OWN} compares the row's author. {@code authorId} may be null
     *       — a row written before authorship was recorded — and {@code equals} on the caller's id
     *       answers false for it, which is the intended direction: a grant that covers "your own
     *       work" must not stretch to work nobody can attribute.
     *       <p><strong>No seeded grant carries {@code OWN} since {@code V51}</strong> (§207: CRUD on
     *       plans, cases and steps belongs to every non-Viewer contributor no matter who created the
     *       row), so this arm is unreachable from the shipped catalogue in the same way
     *       {@code PLAN_SCOPE} is below. It stays wired rather than being removed because the
     *       qualifier is DATA: deleting the arm would make a future {@code OWN} grant fail
     *       <em>open</em> at whichever gate forgot it, and the whole point of resolving this from
     *       {@code role_permission} is that a grant means what the row says. {@code SeedDataTest}
     *       pins the empty population so the emptiness cannot drift back silently.</p></li>
     * </ul>
     */
    @Override
    @Transactional(readOnly = true)
    public boolean canDoOnAuthored(
            UUID userId, UUID projectId, String permission, UUID authorId) {

        return switch (permissions.qualifierOf(userId, projectId, permission)) {
            case NONE -> false;
            case ALL -> true;
            case OWN -> userId.equals(authorId);
            // A grant restricted by row scope, asked about a row's author. The two questions do not
            // meet: this method has no scope to compare, so neither answer would be about the
            // caller's actual grant. Refusing loudly rather than returning false — which reads as a
            // clean authorization denial and would be investigated against the grants, where
            // nothing is wrong — for PermissionQualifier#ofStored's reason. Unreachable today, and
            // MORE unreachable than it was: artifact:edit was the only PLAN_SCOPE grant and V52
            // (§210) de-qualified it, so NO grant carries any qualifier now. The arm stays for the
            // reason the whole mechanism stays — deleting it is what makes a future qualified grant
            // fail OPEN at whichever gate forgot it. Roles spec §A5.
            case PLAN_SCOPE -> throw new IllegalStateException(
                    "'" + permission + "' is PLAN_SCOPE-qualified for this caller, so authorship "
                            + "does not decide it. Use canDoOnScope — see the roles spec's "
                            + "Artifacts table and V19.");
        };
    }

    /**
     * {@inheritDoc}
     *
     * <p>The same three lines as {@link #canDoOnAuthored}, over the other axis:
     *
     * <ul>
     *   <li>{@link PermissionQualifier#NONE} is a refusal, so this needs no {@code canDo} call in
     *       front of it and callers cannot get the order wrong.</li>
     *   <li>{@link PermissionQualifier#ALL} is a yes at either scope. A Test Lead editing the
     *       project's test strategy is the ordinary case, not an exception.</li>
     *   <li>{@link PermissionQualifier#PLAN_SCOPE} is a yes only for a plan's own row. Note it does
     *       <em>not</em> consult the caller: a Test Engineer may supersede a colleague's snapshot
     *       inside the cycle, which is the difference from {@code OWN} and the reason V19 added a
     *       value rather than reusing one.</li>
     * </ul>
     */
    @Override
    @Transactional(readOnly = true)
    public boolean canDoOnScope(
            UUID userId, UUID projectId, String permission, boolean rowIsPlanScoped) {

        return switch (permissions.qualifierOf(userId, projectId, permission)) {
            case NONE -> false;
            case ALL -> true;
            case PLAN_SCOPE -> rowIsPlanScoped;
            // Mirror of the arm above, and unreachable for the same reason: no key is granted with
            // two different restrictive qualifiers (V19, SeedDataTest), so a scope question never
            // meets an authorship-restricted grant.
            case OWN -> throw new IllegalStateException(
                    "'" + permission + "' is OWN-qualified for this caller, so row scope does not "
                            + "decide it. Use canDoOnAuthored — see roles spec §A5 and V12.");
        };
    }

    /**
     * {@inheritDoc}
     *
     * <p>Two queries and no arithmetic: the ranking and roles spec §A1.1's override both live in
     * {@link EffectiveRoleNames}, which is also what the org chart resolves its cells through. That
     * is the whole reason this method replaced the pair it did — the fallback used to be a line in
     * {@code ProjectService.view}, and a second screen needing the same rule would have made it a
     * second copy on the far side of a module boundary, where nothing could compare them.
     *
     * <p>The queries are asked with a single-element id list, which is the same statement the chart
     * runs for a whole company. One query family, so a change to the resolution cannot reach one
     * caller and miss the other.
     */
    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> effectiveProjectRoleNames(UUID userId, Collection<UUID> projectIds) {
        UUID companyId = CompanyContext.require();

        return EffectiveRoleNames.of(
                        roleAssignments.findProjectRoleNames(companyId, userId),
                        roleAssignments.findInheritedRoleNames(companyId, userId))
                .forPerson(userId, projectIds);
    }
}
