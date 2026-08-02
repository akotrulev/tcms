package com.tcms.identity;

import com.tcms.project.ProjectRepository;
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
 * Granting and revoking a role — <strong>the last thing standing between a provisioned company and
 * a staffed one.</strong>
 *
 * <p>{@code provision-company} creates exactly one usable account, the first Company Admin. A
 * second person could be invited and could sign in, and <em>nothing over HTTP could give them
 * sight of a project</em>, because company membership grants authentication and zero project
 * visibility (roles spec §A1) and a grant could only be written by hand in SQL. That is the same
 * shape of blocker handoff §34 closed one level up, and this closes the last one.
 *
 * <h2>The gate depends on the scope of the grant, not on one permission</h2>
 *
 * <p>This is the only write in the application whose gate is chosen by the payload:
 *
 * <ul>
 *   <li>{@code projectId} present → a PROJECT-scope grant, gated on {@code project:members}
 *       <strong>on that project</strong>. A Test Lead staffs the project they run and not another
 *       one — which is exactly the assertion that separates this from "does this caller look
 *       powerful".</li>
 *   <li>{@code projectId} null → a COMPANY-scope grant, covering every project present and future,
 *       gated on {@code company:members} — Company Admin and Instance Admin only. A grant that
 *       spans every project cannot be authorised by a permission held on one, the same argument
 *       {@code project:create} rests on (handoff §34).</li>
 * </ul>
 *
 * <p>Both verdicts come from {@link AdminReach#mayStaff}, so the read filter, the grant and the
 * revoke cannot disagree about who may do what. Two implementations of "may they?" diverge
 * eventually and the quiet one decides what a client can change (plan §6b).
 *
 * <h2>SYSTEM-scope roles are refused here, and that is a decision rather than an omission</h2>
 *
 * <p>{@code Instance Admin} is a {@link RoleScope#SYSTEM} role and its grants live in
 * {@code instance_role_assignment}, a separate instance-level table — because a nullable
 * {@code company_id} under RLS is permanently unreachable ({@link RoleAssignment}'s header;
 * measured in {@code docs/audits/2026-07-30-v1-schema-first-execution.md}). Writing one into
 * {@code role_assignment} would not merely be in the wrong table: it would confer instance-wide
 * powers <em>scoped to one company</em>, which is a tier that does not exist in the model, and it
 * would be done by a Company Admin, who may not appoint operators at all.
 *
 * <p>So a SYSTEM-scope {@code roleId} is a {@code 400} on {@code roleId} with a message that says
 * where those grants live. There is no HTTP path to a SYSTEM grant in this slice and there should
 * not be one until an instance-admin surface exists to hold it; the operator CLI is the only way
 * today.
 *
 * <h2>The membership baseline is refused in both directions</h2>
 *
 * <p>{@link Role#COMPANY_MEMBER_ID} — the COMPANY-scope grant of {@code company:read} every member
 * receives on joining (V15, handoff §43) — cannot be granted here and cannot be revoked here. It is
 * a consequence of membership rather than a decision about a person: granting it is a no-op on a
 * row that already exists, and revoking it removes a colleague's sight of the company entirely,
 * with no repair short of the operator CLI. It is also absent from {@code GET /roles} and from
 * {@code GET /role-assignments}, so nothing legitimate names it.
 *
 * <h2>Re-granting is idempotent, and a duplicate is impossible rather than merely unlikely</h2>
 *
 * <p>An identical grant returns the existing row with {@code 200}. The caller's intent — "this
 * person holds this role here" — is already true, and a second row would give the same user two
 * grants to revoke, so revoking the one on the screen would leave them holding the role.
 *
 * <p>The lookup is the fast path; {@code V13}'s two partial unique indexes are the guarantee. Two
 * admins clicking Grant at the same instant both read nothing and one of them loses at the index —
 * {@code AdminController.onDuplicateGrant} turns that loss back into the 200 the lookup would have
 * produced a moment earlier, which is the arrangement {@code ProjectController.onKeyRace}
 * established for {@code uq_project_key}.
 *
 * <h2>No session is revoked, and the class that asks for it is wrong about this one</h2>
 *
 * <p>{@link AppUser#revokeSessions()}'s javadoc names "a grant change" as a call site that must
 * bump the {@code pv} epoch, and says those call sites do not exist yet. This is the first grant
 * mutation in the product and it deliberately does <strong>not</strong> bump it: the session token
 * carries no grant claims (handoff §18), so {@link PermissionEvaluator} reads the database on every
 * request and a revoke is already effective on the very next one. Bumping would sign the target out
 * — of a session whose permissions are correct — for no gain.
 *
 * <p>It becomes necessary the day plan §10's JWT grant claims land, and that is the moment to add
 * it here rather than now. Recorded rather than silently skipped, because "the epoch was never
 * bumped anywhere" and "the epoch is not needed for this write" look identical in a diff.
 */
@Service
public class RoleGrantService {

    private static final String AUDIT_GRANTED = "role_assignment.granted";
    private static final String AUDIT_REVOKED = "role_assignment.revoked";
    private static final String AUDIT_TARGET = "role_assignment";

    private final AdminDirectoryService directory;
    private final PermissionEvaluator permissions;
    private final ProjectRepository projects;
    private final RoleRepository roles;
    private final RoleAssignmentRepository roleAssignments;
    private final CompanyMembershipRepository memberships;
    private final TeamRepository teams;
    private final AuditEventRepository auditEvents;
    private final Clock clock;

    public RoleGrantService(
            AdminDirectoryService directory,
            PermissionEvaluator permissions,
            ProjectRepository projects,
            RoleRepository roles,
            RoleAssignmentRepository roleAssignments,
            CompanyMembershipRepository memberships,
            TeamRepository teams,
            AuditEventRepository auditEvents,
            Clock clock) {

        this.directory = directory;
        this.permissions = permissions;
        this.projects = projects;
        this.roles = roles;
        this.roleAssignments = roleAssignments;
        this.memberships = memberships;
        this.teams = teams;
        this.auditEvents = auditEvents;
        this.clock = clock;
    }

    /** What happened. Sealed, so the controller's mapping over it is exhaustive at compile time. */
    public sealed interface Grant {

        /** The named project does not exist, or is not reachable from this session (§6t). */
        record NotFound() implements Grant {
        }

        /** The caller does not hold the permission this grant's scope needs. */
        record Forbidden() implements Grant {
        }

        /** Field name to messages, for the form to land them on its inputs. */
        record Invalid(Map<String, List<String>> errors) implements Grant {
        }

        /** It was already true. The existing row, unchanged — 200, not 409. */
        record Existing(RoleAssignmentView view) implements Grant {
        }

        /** Written — 201. */
        record Created(RoleAssignmentView view) implements Grant {
        }
    }

    /** What a revoke did. {@link Revoked} covers "it was not there", per the contract. */
    public sealed interface Revoke {

        record NotFound() implements Revoke {
        }

        record Forbidden() implements Revoke {
        }

        record Invalid(Map<String, List<String>> errors) implements Revoke {
        }

        /** Gone — or never there. Both are 204. */
        record Revoked() implements Revoke {
        }
    }

    /**
     * Thrown after a revoke that would have removed the caller's own ability to revoke, so the
     * transaction rolls back.
     *
     * <h2>Why the check is "do it and ask" rather than a second computation</h2>
     *
     * <p>The alternative is to work out, before deleting, whether the caller holds the gating
     * permission through some <em>other</em> grant — which means re-deriving the union the
     * evaluator computes: direct grants, team grants, the COMPANY term, the SYSTEM term, the
     * membership gate. That is a second implementation of the permission algebra (plan §6b), and it
     * would have to be kept in step with the first forever.
     *
     * <p>So the row is deleted, the session is flushed, and {@link PermissionEvaluator} is asked
     * again — the same evaluator, seeing the same transaction, answering the same question the gate
     * asked a moment earlier. A "no" means the caller just locked themselves out, and this
     * exception unwinds it. The check is therefore correct for team grants, for callers holding
     * two grants, and for an Instance Admin (whose SYSTEM term survives anything done here) without
     * any of those cases being enumerated.
     */
    static final class SelfLockout extends RuntimeException {

        private static final long serialVersionUID = 1L;

        SelfLockout() {
            super("The caller revoked the grant carrying their own ability to revoke");
        }
    }

    /**
     * Grants {@code request.roleId} to a user or a team, in the company the session is open on.
     *
     * <p><strong>The gate runs before the payload is examined</strong>, exactly as
     * {@code ProjectCreateService} does and for the same reason: the validation below reports
     * whether a person is a member of this company and whether they already hold the role, and
     * running it for a caller who may not staff anything turns a 403 endpoint into a membership
     * probe. The one exception is the scope itself — {@code projectId} has to be read to know
     * <em>which</em> permission the gate names, and it is read for nothing else.
     */
    @Transactional
    public Grant grant(UUID userId, RoleGrantRequest request) {
        UUID companyId = CompanyContext.require();
        UUID projectId = request.projectId();

        if (projectId != null && projects.findScopedById(projectId).isEmpty()) {
            // Unreachable before denied, the order writeGranted established: a caller who cannot
            // see the project must not tell a non-existent id from one they hold no grant on by
            // observing 403 rather than 404.
            return new Grant.NotFound();
        }
        if (!directory.reachOf(userId).mayStaff(projectId)) {
            return new Grant.Forbidden();
        }

        Optional<Map<String, List<String>>> invalid = validate(companyId, request);
        if (invalid.isPresent()) {
            return new Grant.Invalid(invalid.get());
        }

        Optional<RoleAssignment> existing = findIdentical(companyId, request);
        if (existing.isPresent()) {
            return new Grant.Existing(directory.viewOf(existing.get(), userId));
        }

        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        RoleAssignment granted = request.userId() != null
                ? (projectId == null
                        ? RoleAssignment.forUserOnCompany(
                                id, companyId, request.roleId(), request.userId(), userId, now)
                        : RoleAssignment.forUserOnProject(
                                id, companyId, request.roleId(), request.userId(), projectId, userId, now))
                : (projectId == null
                        ? RoleAssignment.forTeamOnCompany(
                                id, companyId, request.roleId(), request.teamId(), userId, now)
                        : RoleAssignment.forTeamOnProject(
                                id, companyId, request.roleId(), request.teamId(), projectId, userId, now));

        // saveAndFlush so a duplicate that lost the race surfaces here, inside this method, rather
        // than on commit after the response has been assembled — the controller's handler catches
        // it either way, but a flush keeps the failure next to the statement that caused it.
        RoleAssignment saved = roleAssignments.saveAndFlush(granted);
        audit(AUDIT_GRANTED, saved, userId, now);
        return new Grant.Created(directory.viewOf(saved, userId));
    }

    /**
     * Revokes one grant.
     *
     * <p>Four answers and only one of them is a refusal the caller can act on:
     *
     * <ul>
     *   <li><strong>Not there → 204.</strong> A double-click and a retried request both end in the
     *       state the caller asked for. A grant in another company is <em>also</em> not there —
     *       RLS and the company filter both refuse it — so isolation and idempotence give the same
     *       answer here, which is the one §6t wants.</li>
     *   <li><strong>Its project is unreachable → 404</strong>, before the gate, for the reason
     *       {@link #grant} gives.</li>
     *   <li><strong>The caller may not staff that scope → 403.</strong></li>
     *   <li><strong>It carries the caller's own ability to revoke → 400 on
     *       {@code assignmentId}</strong>, and nothing is written. The alternative is a company
     *       with no administrator and no way back in without the operator CLI.</li>
     * </ul>
     */
    @Transactional
    public Revoke revoke(UUID userId, UUID assignmentId) {
        Optional<RoleAssignment> found = roleAssignments.findScopedById(assignmentId);
        if (found.isEmpty()) {
            return new Revoke.Revoked();
        }
        RoleAssignment grant = found.get();
        UUID projectId = grant.getProjectId();

        if (Role.COMPANY_MEMBER_ID.equals(grant.getRoleId())) {
            // The baseline every member holds (V15). It is never listed, so no screen can offer
            // this — but an id typed into a request must not be able to take away a colleague's
            // sight of the company, whose only repair is re-running the operator CLI. Removing it
            // is what removing the MEMBERSHIP means, and that is a different endpoint.
            return new Revoke.Forbidden();
        }
        if (projectId != null && projects.findScopedById(projectId).isEmpty()) {
            return new Revoke.NotFound();
        }
        if (!directory.reachOf(userId).mayStaff(projectId)) {
            return new Revoke.Forbidden();
        }

        Instant now = clock.instant();
        audit(AUDIT_REVOKED, grant, userId, now);
        roleAssignments.delete(grant);
        roleAssignments.flush();

        if (!stillHoldsTheGate(userId, projectId)) {
            // Rolls the delete AND its audit row back — an audit entry for a revoke that did not
            // happen is worse than none, because it is the record everybody trusts.
            throw new SelfLockout();
        }
        return new Revoke.Revoked();
    }

    /**
     * Asks the evaluator the gate's own question again, now that the row is gone.
     *
     * <p>Not "was the caller the subject of that grant": a grant to a team the caller belongs to
     * takes their permission away just as effectively, and so does one of two grants when the
     * other was the redundant one. Asking the question rather than reasoning about the row is what
     * makes those cases free.
     */
    private boolean stillHoldsTheGate(UUID userId, UUID projectId) {
        return projectId == null
                ? permissions.evaluateCompanyWide(userId)
                        .has(AdminDirectoryService.MANAGE_COMPANY_MEMBERS)
                : permissions.evaluate(userId, projectId)
                        .has(AdminDirectoryService.MANAGE_PROJECT_MEMBERS);
    }

    // =====================================================================
    // validation
    // =====================================================================

    /**
     * Everything about the payload that can be wrong, as field errors — empty when it is fine.
     *
     * <p>All of it is reported at once. A form that highlights one input at a time makes the user
     * submit three times to learn three things, which is why {@code Problem.errors} is a map.
     */
    private Optional<Map<String, List<String>>> validate(UUID companyId, RoleGrantRequest request) {
        Map<String, List<String>> errors = new java.util.LinkedHashMap<>();

        boolean hasUser = request.userId() != null;
        boolean hasTeam = request.teamId() != null;
        if (hasUser == hasTeam) {
            // ck_role_assignment_subject in V1, restated so the caller is told which field is wrong
            // instead of receiving a driver exception naming a constraint. Keyed on `userId`, per
            // the contract, because the form's subject picker is one control.
            errors.put("userId", List.of(hasUser
                    ? "Grant the role to a person or to a team, not to both."
                    : "Choose a person or a team to grant the role to."));
        }

        Optional<Role> role = request.roleId() == null
                ? Optional.empty()
                : roles.findById(request.roleId());
        if (role.isEmpty() || Role.COMPANY_MEMBER_ID.equals(request.roleId())) {
            // The baseline is refused as an unknown role rather than with its own message. It is
            // not offered by GET /roles, so no legitimate client can name it, and telling a caller
            // that a role they cannot see exists is the disclosure this endpoint avoids elsewhere.
            errors.put("roleId", List.of("Choose a role."));
        } else {
            scopeError(role.get(), request.projectId()).ifPresent(
                    message -> errors.put("roleId", List.of(message)));
        }

        if (hasUser && !hasTeam
                && memberships.findByCompanyIdAndUserId(companyId, request.userId()).isEmpty()) {
            // The same message for "not a member of this company", "a member of another company"
            // and "no such account", deliberately: told apart, this endpoint becomes a directory
            // oracle for the instance, which is the disclosure channel AppUser's header names.
            errors.put("userId", List.of("That person is not a member of this company."));
        }
        if (hasTeam && !hasUser && teams.findScopedById(request.teamId()).isEmpty()) {
            errors.put("teamId", List.of("That team does not exist in this company."));
        }

        return errors.isEmpty() ? Optional.empty() : Optional.of(Map.copyOf(errors));
    }

    /**
     * Whether the role's own scope agrees with the grant being made.
     *
     * <p>A silent coercion is the tempting alternative and it is wrong in both directions: pinning
     * a COMPANY-scope role to one project quietly demotes a Company Admin to that project, and
     * widening a PROJECT-scope role to the company hands someone every project present and future.
     * The two mean materially different access, so the caller is told rather than guessed at.
     *
     * <p>~~<strong>The consequence worth knowing:</strong> a company-wide grant of a PROJECT-scope
     * role is not expressible here. That arrangement exists in the codebase — the "stakeholder
     * Viewer over the whole company" persona {@code ProjectCreateEndpointTest} seeds directly
     * through the repository, noting that {@code role_assignment} does not enforce scope. It is
     * seeded, not granted, and the roles spec gives each of the six roles exactly one scope, so
     * refusing it here follows the spec. If the product wants a company-wide read-only
     * stakeholder, that is a seventh role in the catalogue, not a relaxation of this check.~~
     *
     * <p><strong>→ Superseded: a PROJECT-scope role may now be granted with no project</strong>, and
     * it means <em>this role, on every project in the company, unless that project says
     * otherwise</em>. The prediction above was half right — the arrangement was indeed already in
     * the codebase and only reachable by seeding — but the conclusion was wrong: a seventh role
     * would have solved it for Viewer alone, and the same want exists for every working role
     * ("she runs testing across all of our projects"). It is a property of the <em>grant</em>, not
     * of the role, which is why it belongs here rather than in the catalogue.
     *
     * <p>The demotion direction is what makes this safe to allow. The old comment's fear —
     * "widening a PROJECT-scope role to the company hands someone every project present and future"
     * — is still exactly what this does, and it is now the stated intent rather than an accident;
     * what makes it governable is that a project-level grant <strong>overrides</strong> the
     * inherited one, including downward, so a company-wide Test Lead can still be reduced to Viewer
     * on one sensitive project. {@code PermissionEvaluator#projectRoleKeys} is that rule.
     *
     * <p>The other two arms are unchanged, and the COMPANY one still matters for the same reason:
     * pinning a Company Admin to one project would quietly demote them, and a COMPANY-scope role is
     * authority over the company rather than a default for its projects.
     */
    private static Optional<String> scopeError(Role role, UUID projectId) {
        return switch (role.getScope()) {
            case SYSTEM -> Optional.of(
                    "Instance-wide roles are granted by the operator, not from here.");
            case COMPANY -> projectId == null
                    ? Optional.empty()
                    : Optional.of("This role applies to the whole company, so it cannot be "
                            + "granted on a single project.");
            // Both are valid now: with a project it applies there; without one it is inherited by
            // every project that has no grant of its own.
            case PROJECT -> Optional.empty();
        };
    }

    /** The identical grant if it already exists — the 200 path. See the class header. */
    private Optional<RoleAssignment> findIdentical(UUID companyId, RoleGrantRequest request) {
        if (request.userId() != null) {
            return request.projectId() == null
                    ? roleAssignments.findByCompanyIdAndRoleIdAndUserIdAndProjectIdIsNull(
                            companyId, request.roleId(), request.userId())
                    : roleAssignments.findByCompanyIdAndRoleIdAndUserIdAndProjectId(
                            companyId, request.roleId(), request.userId(), request.projectId());
        }
        return request.projectId() == null
                ? roleAssignments.findByCompanyIdAndRoleIdAndTeamIdAndProjectIdIsNull(
                        companyId, request.roleId(), request.teamId())
                : roleAssignments.findByCompanyIdAndRoleIdAndTeamIdAndProjectId(
                        companyId, request.roleId(), request.teamId(), request.projectId());
    }

    /** Re-reads the grant a race lost, so the loser gets the 200 the winner's row earns. */
    @Transactional(readOnly = true)
    public Optional<RoleAssignmentView> existingGrant(UUID userId, RoleGrantRequest request) {
        return findIdentical(CompanyContext.require(), request)
                .map(grant -> directory.viewOf(grant, userId));
    }

    /**
     * One {@code audit_event} row.
     *
     * <p>{@code projectId} is null for a company-wide grant, which the column permits — the event
     * belongs to the company rather than to any project, and inventing one to fill the column would
     * put a company-wide appointment in one project's history.
     */
    private void audit(String action, RoleAssignment grant, UUID actorId, Instant now) {
        String subject = grant.getUserId() != null
                ? "\"userId\":" + AuditEvent.jsonString(grant.getUserId().toString())
                : "\"teamId\":" + AuditEvent.jsonString(grant.getTeamId().toString());
        String scope = grant.isCompanyScoped()
                ? "\"scope\":\"COMPANY\""
                : "\"scope\":\"PROJECT\",\"projectId\":"
                        + AuditEvent.jsonString(grant.getProjectId().toString());

        auditEvents.save(AuditEvent.record(
                grant.getProjectId(),
                actorId,
                action,
                AUDIT_TARGET,
                List.of(grant.getId()),
                "{" + subject + ",\"roleId\":" + AuditEvent.jsonString(grant.getRoleId().toString())
                        + "," + scope + "}",
                now));
    }
}
