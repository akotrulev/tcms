package com.tcms.identity;

import com.tcms.project.ProjectAccess;
import com.tcms.tenancy.CompanyContext;
import java.util.Comparator;
import java.util.HashMap;
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
     *       work" must not stretch to work nobody can attribute.</li>
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
            // nothing is wrong — for PermissionQualifier#ofStored's reason. Unreachable today:
            // artifact:edit is the only PLAN_SCOPE grant and no caller asks it about an author.
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
     * <p>"Strongest" is the count of permissions the role confers, read from the same
     * {@code role_permission} table authorization reads — <strong>derived, not a hard-coded
     * ranking of role names</strong>, so a role added later sorts correctly without this method
     * being edited. A name-ordered rank is the same mistake as the {@code '*'} sentinel in
     * handoff §6 lesson 10: it reads correctly until the set it describes changes.
     *
     * <p>A caller can genuinely hold two grants on one project — directly and through a team,
     * or through two teams — and the contract has room for one string. Ties break on the name
     * so the answer is stable between requests rather than dependent on row order; picking
     * either of two equally-strong roles is defensible, picking a different one each time is not.
     */
    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> projectRoleNames(UUID userId) {
        UUID companyId = CompanyContext.require();

        Comparator<RoleAssignmentRepository.ProjectRoleName> byStrength =
                Comparator.comparingLong(RoleAssignmentRepository.ProjectRoleName::getPermissionCount)
                        .thenComparing(RoleAssignmentRepository.ProjectRoleName::getRoleName);

        Map<UUID, RoleAssignmentRepository.ProjectRoleName> strongest = new HashMap<>();
        for (var row : roleAssignments.findProjectRoleNames(companyId, userId)) {
            strongest.merge(row.getProjectId(), row,
                    (existing, candidate) ->
                            byStrength.compare(candidate, existing) > 0 ? candidate : existing);
        }

        Map<UUID, String> names = new HashMap<>();
        strongest.forEach((projectId, row) -> names.put(projectId, row.getRoleName()));
        return names;
    }
}
