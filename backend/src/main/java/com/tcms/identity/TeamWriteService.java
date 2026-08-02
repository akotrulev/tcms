package com.tcms.identity;

import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import com.tcms.tenancy.CompanyContext;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creating, editing, deleting and staffing a team — <strong>the writer {@code team} has waited for
 * since {@code V1}.</strong>
 *
 * <p>The table has had readers from the beginning: {@code GET /teams} renders the org chart,
 * {@link RoleGrantService} accepts a team as a grant's subject, and {@link PermissionEvaluator}
 * resolves a caller's teams inside every one of its union queries. It has never had a writer.
 * {@code provision-company} seeds no team, so every real company's org chart was empty and stayed
 * empty, and the grant path's team branch — validated, tested and documented — could not be reached
 * with a {@code teamId} that named anything. {@link Team#create} had only test callers;
 * {@code rename} and {@code reparent} had none at all. That is §34's shape ("a provisioned company
 * plus hand-written SQL") for the org chart, and this class closes it.
 *
 * <h2>The gate is {@code company:teams} at COMPANY scope, and this is its first consumer</h2>
 *
 * <p>Roles spec §A3, Company table: <em>"teams, nesting, leads, org-chart structure"</em> — Company
 * Admin and Instance Admin. The key has been seeded by {@code V3} and correctly granted since M0 and
 * <strong>never once asked</strong>, which is §62's {@code case:approve}, §64's
 * {@code library:manage} and §69's {@code company:vocabulary} for the fourth time. The verdict comes
 * from {@link AdminReach#manageTeams()}, computed from the same company-wide term
 * {@link MembershipInviteService} and {@link RoleGrantService} read, so the three writes on this
 * surface cannot disagree about who administers this company.
 *
 * <p>The gate runs <strong>before the payload</strong>, for {@code RoleGrantService.grant}'s reason:
 * the work below reports whether a name is taken and whether a uuid names a team here, and running
 * it for a caller who may not administer teams would turn a 403 endpoint into a directory probe.
 *
 * <h2>A team confers nothing, until something is granted to it</h2>
 *
 * <p>Worth stating because it decides three of the answers below. Creating a team, putting somebody
 * in it and marking them its lead are all org-chart facts: capability arrives only when
 * {@code POST /role-assignments} names the team. So there is nothing to warn about when deleting a
 * team with members, and everything to warn about when deleting one with grants.
 *
 * <h2>The membership check is the substance, and the read side hides the evidence</h2>
 *
 * <p>{@code team_membership.user_id} references {@code app_user}, which is <strong>instance-level
 * with no RLS</strong> ({@link com.tcms.identity} package comment), while {@code company_id} is
 * taken from {@link CompanyContext}. So a <em>sibling company's</em> user id satisfies the foreign
 * key, the company column, the RLS policy and every constraint on the table: unchecked, the row is
 * simply stored. What happens next is the part that makes this worth a paragraph rather than a
 * line — {@link RoleAssignmentRepository}'s five projections resolve a user's teams through
 * {@code team_membership} alone and <strong>never join {@code company_membership}</strong>, so that
 * person immediately holds whatever the team holds, in a company they are not a member of; while
 * {@link AdminDirectoryService#listTeams} renders them <em>nowhere</em>, because it resolves names
 * through company membership and drops an id it cannot place. An invisible grant is worse than a
 * leaked name. Hence {@link #putMember}'s check, and hence a <strong>404</strong> rather than a
 * field error: an endpoint that distinguished "not in this company" from "no such account" would be
 * an instance-wide membership oracle for guessed uuids (§6t).
 *
 * <p>{@code INVITED} counts and {@code REMOVED} does not. Staffing somebody who has not accepted yet
 * is ordinary onboarding — §55 built the invitation precisely so the rest of the setup need not
 * wait — while a removed membership is the state {@link MembershipRemovalService} puts somebody in
 * to make their grants inert, and re-admitting them through the org chart would undo it silently.
 */
@Service
public class TeamWriteService {

    /**
     * Roles spec §A3, Company table. COMPANY scope, so Company Admin and Instance Admin only.
     * <strong>Its first consumer in Java</strong> — see the class header.
     */
    static final String MANAGE_TEAMS = "company:teams";

    private static final String AUDIT_TARGET = "team";
    private static final String AUDIT_MEMBER_TARGET = "team_membership";

    private final AdminDirectoryService directory;
    private final TeamRepository teams;
    private final TeamMembershipRepository teamMemberships;
    private final CompanyMembershipRepository companyMemberships;
    private final RoleAssignmentRepository roleAssignments;
    private final AuditEventRepository auditEvents;
    private final Clock clock;

    public TeamWriteService(
            AdminDirectoryService directory,
            TeamRepository teams,
            TeamMembershipRepository teamMemberships,
            CompanyMembershipRepository companyMemberships,
            RoleAssignmentRepository roleAssignments,
            AuditEventRepository auditEvents,
            Clock clock) {

        this.directory = directory;
        this.teams = teams;
        this.teamMemberships = teamMemberships;
        this.companyMemberships = companyMemberships;
        this.roleAssignments = roleAssignments;
        this.auditEvents = auditEvents;
        this.clock = clock;
    }

    /** What happened. Sealed, so the controller's mapping over it is exhaustive at compile time. */
    public sealed interface Outcome {

        /** The caller does not hold {@code company:teams} in this company. */
        record Forbidden() implements Outcome {
        }

        /** No such team here, or no such member of this company. Always bodiless 404. */
        record NotFound() implements Outcome {
        }

        /** Field name to messages, for the form to land them on its inputs. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /**
         * The team still holds grants or still has child teams — 409, and the counts are the whole
         * message. Both in one outcome because both can be true and a caller fixing one at a time
         * would learn about the second only after clearing the first.
         */
        record StillInUse(long grants, long children) implements Outcome {
        }

        record Created(TeamView view) implements Outcome {
        }

        record Updated(TeamView view) implements Outcome {
        }

        record Deleted() implements Outcome {
        }
    }

    // =====================================================================
    // the team itself
    // =====================================================================

    /** Creates a team. It holds nothing and reaches nothing until a grant names it. */
    @Transactional
    public Outcome create(UUID callerId, TeamInput request) {
        UUID companyId = CompanyContext.require();

        AdminReach reach = directory.reachOf(callerId);
        if (!reach.manageTeams()) {
            return new Outcome.Forbidden();
        }

        TeamEdit.Outcome validated =
                TeamEdit.validateCreate(request, teams.findAllByOrderByNameAsc());
        if (validated instanceof TeamEdit.Outcome.Invalid invalid) {
            return new Outcome.Invalid(invalid.errors());
        }
        TeamEdit edit = ((TeamEdit.Outcome.Valid) validated).edit();

        Instant now = clock.instant();
        Team team = Team.create(
                UUID.randomUUID(), companyId, edit.name(), edit.description(), edit.parentTeamId(),
                now);

        // Flushed here so V21's unique index and V2's hierarchy trigger fail at their cause, inside
        // this method, rather than on commit under whatever ran next —
        // ClassificationValueWriteService's reason, and the same two backstops behind two checks.
        teams.saveAndFlush(team);
        audit(callerId, "team.created", AUDIT_TARGET, team.getId(), teamJson(team), now);

        return new Outcome.Created(directory.teamViewOf(reach, team));
    }

    /** Renames, re-describes or moves a team. A move is allowed here — see {@link TeamEdit}. */
    @Transactional
    public Outcome update(UUID callerId, UUID teamId, TeamInput request) {
        AdminReach reach = directory.reachOf(callerId);
        if (!reach.manageTeams()) {
            return new Outcome.Forbidden();
        }

        Optional<Team> found = teams.findScopedById(teamId);
        if (found.isEmpty()) {
            return new Outcome.NotFound();
        }
        Team team = found.get();

        TeamEdit.Outcome validated =
                TeamEdit.validateEdit(request, team, teams.findAllByOrderByNameAsc());
        if (validated instanceof TeamEdit.Outcome.Invalid invalid) {
            return new Outcome.Invalid(invalid.errors());
        }
        TeamEdit edit = ((TeamEdit.Outcome.Valid) validated).edit();

        Instant now = clock.instant();
        team.applyEdit(edit.name(), edit.description(), edit.parentTeamId(), now);
        teams.saveAndFlush(team);

        audit(callerId, "team.updated", AUDIT_TARGET, team.getId(), teamJson(team), now);

        return new Outcome.Updated(directory.teamViewOf(reach, team));
    }

    /**
     * Deletes a team — a <strong>hard</strong> delete, refused while anything hangs off it.
     *
     * <p>A team carries no authored content of its own, so unlike a case, a suite or a
     * classification value there is nothing for a soft delete to preserve and nothing that would
     * later have to resolve the row. Its memberships go with it, by the table's own cascade.
     *
     * <h2>Grants and child teams are both refused, and for one reason each</h2>
     *
     * <p>{@code role_assignment.team_id} is {@code ON DELETE CASCADE}, so deleting a team that holds
     * grants would <em>silently revoke every one of them</em> — and those grants are the entire
     * reason a team is a grant subject. Mass-revoking people's access as a side effect of tidying an
     * org chart is exactly the write an administrator must make deliberately, so it is 409 with the
     * count: revoke the grants, then delete the team. {@code ClassificationValueWriteService}'s
     * refusal of a value with live children is the same decision about a different cascade.
     *
     * <p>{@code parent_team_id} has <em>no</em> cascade, so a team with children could not be
     * deleted at all — the alternative to refusing is a foreign-key violation surfacing as a 500, or
     * silently promoting somebody else's sub-teams to the root of the chart.
     *
     * <p>A team with <strong>members</strong> is not refused: membership confers nothing on its own,
     * so there is nothing to revoke and nothing to warn about.
     */
    @Transactional
    public Outcome delete(UUID callerId, UUID teamId) {
        if (!directory.reachOf(callerId).manageTeams()) {
            return new Outcome.Forbidden();
        }

        Optional<Team> found = teams.findScopedById(teamId);
        if (found.isEmpty()) {
            return new Outcome.NotFound();
        }
        Team team = found.get();

        long grants = roleAssignments.countByTeamId(teamId);
        long children = teams.countByParentTeamId(teamId);
        if (grants > 0 || children > 0) {
            return new Outcome.StillInUse(grants, children);
        }

        Instant now = clock.instant();
        // Audited before the delete, not after: the row is about to stop existing, and the name is
        // the only thing that makes the entry readable afterwards.
        audit(callerId, "team.deleted", AUDIT_TARGET, teamId, teamJson(team), now);
        teams.delete(team);

        return new Outcome.Deleted();
    }

    // =====================================================================
    // membership
    // =====================================================================

    /**
     * Puts somebody in a team, or changes whether they lead it.
     *
     * <p>Idempotent by construction — the URL names the membership, so repeating the request asserts
     * the same state. The company-membership check is the substance; see the class header for what
     * the absence of it stores and why the read side would not show it.
     */
    @Transactional
    public Outcome putMember(UUID callerId, UUID teamId, UUID userId, TeamMemberInput request) {
        UUID companyId = CompanyContext.require();

        AdminReach reach = directory.reachOf(callerId);
        if (!reach.manageTeams()) {
            return new Outcome.Forbidden();
        }

        if (request == null || request.lead() == null) {
            // Boxed on TeamMemberInput precisely so this is reachable: a primitive would have read
            // an omitted key as false and silently demoted a lead.
            return new Outcome.Invalid(Map.of(
                    "lead", List.of("Say whether this person leads the team.")));
        }

        Optional<Team> found = teams.findScopedById(teamId);
        if (found.isEmpty()) {
            return new Outcome.NotFound();
        }
        Team team = found.get();

        if (!isStaffable(companyId, userId)) {
            return new Outcome.NotFound();
        }

        Instant now = clock.instant();
        boolean lead = request.lead();

        Optional<TeamMembership> existing = teamMemberships.findByTeamIdAndUserId(teamId, userId);
        if (existing.isPresent()) {
            TeamMembership membership = existing.get();
            if (membership.isLead() == lead) {
                // Nothing changed. Not audited — an audit row per no-op click buries the ones that
                // moved something, which is MembershipInviteService's reading of a repeated invite.
                return new Outcome.Updated(directory.teamViewOf(reach, team));
            }
            membership.setLead(lead);
            teamMemberships.saveAndFlush(membership);
        } else {
            teamMemberships.saveAndFlush(
                    TeamMembership.join(companyId, teamId, userId, lead, now));
        }

        audit(callerId, "team_membership.put", AUDIT_MEMBER_TARGET, teamId,
                "{\"teamId\":\"" + teamId + "\",\"userId\":\"" + userId + "\",\"lead\":" + lead
                        + "}",
                now);

        return new Outcome.Updated(directory.teamViewOf(reach, team));
    }

    /**
     * Takes somebody out of a team.
     *
     * <p>{@code 204} whether they were in it or not, for {@code RoleGrantService.revoke}'s reason: a
     * repeated request, a double-click and an id naming nobody here all end in the state the caller
     * asked for.
     *
     * <p>Whatever the team conferred on them is gone on their next request — the evaluator reads
     * team membership every time and no token carries grant claims. Their session is deliberately
     * <em>not</em> revoked, unlike {@link MembershipRemovalService}'s: there the token itself was
     * the problem, because nothing in the request path re-reads company membership. Here everything
     * that changed is re-resolved from the database anyway, so a forced sign-out would cost a
     * sign-in and change nothing.
     */
    @Transactional
    public Outcome removeMember(UUID callerId, UUID teamId, UUID userId) {
        if (!directory.reachOf(callerId).manageTeams()) {
            return new Outcome.Forbidden();
        }

        if (teams.findScopedById(teamId).isEmpty()) {
            return new Outcome.NotFound();
        }

        Optional<TeamMembership> existing = teamMemberships.findByTeamIdAndUserId(teamId, userId);
        if (existing.isEmpty()) {
            return new Outcome.Deleted();
        }

        Instant now = clock.instant();
        teamMemberships.delete(existing.get());
        audit(callerId, "team_membership.removed", AUDIT_MEMBER_TARGET, teamId,
                "{\"teamId\":\"" + teamId + "\",\"userId\":\"" + userId + "\"}", now);

        return new Outcome.Deleted();
    }

    /**
     * Whether this account is somebody this company may put in a team.
     *
     * <p>Asked of {@code company_membership} — the policed table — and never of {@code app_user},
     * which is the whole point. {@code INVITED} and {@code ACTIVE} both count; see the class header.
     */
    private boolean isStaffable(UUID companyId, UUID userId) {
        if (userId == null) {
            return false;
        }
        return companyMemberships.findByCompanyIdAndUserId(companyId, userId)
                .map(membership -> membership.getStatus() == MembershipStatus.ACTIVE
                        || membership.getStatus() == MembershipStatus.INVITED)
                .orElse(false);
    }

    /**
     * One {@code audit_event} row.
     *
     * <p>{@code projectId} is null: the org chart belongs to the company, and inventing a project to
     * fill the column would file it under one client engagement —
     * {@link MembershipInviteService#invite}'s reason for the same null.
     */
    private void audit(
            UUID actorId,
            String action,
            String target,
            UUID targetId,
            String payload,
            Instant now) {

        auditEvents.save(AuditEvent.record(
                null, actorId, action, target, List.of(targetId), payload, now));
    }

    /** The team as the audit trail records it — the name, because a deleted row has no other. */
    private static String teamJson(Team team) {
        return "{\"name\":" + AuditEvent.jsonString(team.getName())
                + ",\"parentTeamId\":"
                + (team.getParentTeamId() == null
                        ? "null"
                        : AuditEvent.jsonString(team.getParentTeamId().toString()))
                + "}";
    }
}
