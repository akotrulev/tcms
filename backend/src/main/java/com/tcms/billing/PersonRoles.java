package com.tcms.billing;

import com.tcms.identity.RoleAssignmentRepository;
import com.tcms.tenancy.CompanyContext;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * <strong>Which roles is this person staffed with on this project</strong> — the middle rung of the
 * rate ladder, and a pricing question rather than an authorization one.
 *
 * <h2>Why this reads {@code identity} directly and does not declare a port</h2>
 *
 * <p>{@code identity} is one of this module's four allowed dependencies, so the import is available
 * and the {@code *Facts} inversion has nothing to invert — that pattern exists for crossings the
 * module graph forbids, and this one it permits. What matters is <em>which</em> statement answers
 * it: {@link RoleAssignmentRepository#findProjectRoleNames} and
 * {@link RoleAssignmentRepository#findInheritedRoleNames} are the projections the org chart and
 * {@code Project.myRole} already resolve through, so nothing here is a second query computing who
 * holds what. A native query of this module's own over {@code role_assignment} would be exactly the
 * second answer plan §6b names, on the table where two answers cost the most.
 *
 * <p><strong>It answers "how is this person staffed", never "may they".</strong> The permission
 * question in this module goes through {@code project.ProjectService} in every case, as it does in
 * {@code time}, {@code staffing} and {@code crm}. Nothing here is consulted before a refusal.
 *
 * <h2>Roles spec §A1.1's replace-don't-union override, applied and not re-derived</h2>
 *
 * <p>A grant naming the project wins; otherwise the company-wide {@code PROJECT}-scope role applies;
 * otherwise the person holds no role there. That is the rule {@code identity.EffectiveRoleNames}
 * states — <em>"the one place in this model where adding a grant REMOVES a permission"</em> — and
 * unioning the two instead would price somebody at the rate of a role they were demoted out of.
 * That class is package-private in {@code identity} and reduces to <em>one</em> name per cell, which
 * is what a screen needs and the wrong shape here: 019 D-d prices a person who holds two roles on
 * one project at the <em>higher</em> of the two, so the whole matching set has to survive as far as
 * {@link PricingService}. The override is what is reused; the reduction is not, and the difference
 * is stated rather than left as a divergence somebody finds.
 */
@Component
@Transactional(readOnly = true)
class PersonRoles {

    /**
     * Every role every person in a batch holds, per project, already resolved to role ids.
     *
     * <p>Taken as a value rather than looked up per hour, because pricing a fortnight of a client's
     * work asks this question once per entry and the answer changes for none of them.
     */
    record Held(Map<UUID, Map<UUID, Set<UUID>>> named, Map<UUID, Set<UUID>> inherited) {

        /** The roles this person is staffed with on this project — empty when they hold none. */
        Set<UUID> on(UUID personId, UUID projectId) {
            Set<UUID> onProject = named.getOrDefault(personId, Map.of()).get(projectId);
            return onProject != null ? onProject : inherited.getOrDefault(personId, Set.of());
        }
    }

    private final RoleAssignmentRepository assignments;
    private final RoleNames roles;

    PersonRoles(RoleAssignmentRepository assignments, RoleNames roles) {
        this.assignments = assignments;
        this.roles = roles;
    }

    Held heldBy(Collection<UUID> personIds) {
        if (personIds.isEmpty()) {
            // `IN ()` is a syntax error rather than a predicate matching nothing, and a company
            // whose approved fortnight is entirely meetings and leave arrives here empty.
            return new Held(Map.of(), Map.of());
        }
        UUID companyId = CompanyContext.require();
        Map<String, UUID> idByName = roles.byName();

        Map<UUID, Map<UUID, Set<UUID>>> named = new HashMap<>();
        for (RoleAssignmentRepository.ProjectRoleName row
                : assignments.findProjectRoleNames(companyId, personIds)) {
            UUID roleId = idByName.get(row.getRoleName());
            if (roleId != null) {
                named.computeIfAbsent(row.getUserId(), person -> new HashMap<>())
                        .computeIfAbsent(row.getProjectId(), project -> new HashSet<>())
                        .add(roleId);
            }
        }

        Map<UUID, Set<UUID>> inherited = new HashMap<>();
        for (RoleAssignmentRepository.InheritedRoleName row
                : assignments.findInheritedRoleNames(companyId, personIds)) {
            UUID roleId = idByName.get(row.getRoleName());
            if (roleId != null) {
                inherited.computeIfAbsent(row.getUserId(), person -> new HashSet<>()).add(roleId);
            }
        }
        return new Held(named, inherited);
    }
}
