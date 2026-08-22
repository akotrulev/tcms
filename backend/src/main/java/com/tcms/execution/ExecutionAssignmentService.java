package com.tcms.execution;

import com.tcms.project.GateResult;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.AuditEventRepository;
import com.tcms.tenancy.CompanyContext;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Giving one row of a run's checklist to somebody — <strong>{@code test_execution.assignee_id}'s
 * second writer</strong>.
 *
 * <p>The first is {@link TestExecution#materialise}, called by {@link RunCreateService} with the
 * whole-run {@code assigneeId}: two hundred rows to one person, or every one of them unassigned. So
 * until this class existed the column was set once, at create, and the SPA — which had no control
 * for it at all — could only ever produce the second state. The field was rendered on the run detail
 * and on the execution queue, and nothing in the product could change it.
 *
 * <h2>The gate is {@code run:execute}, and picking it was the decision</h2>
 *
 * <p>Three keys were candidates and two of them are wrong in a way that matters.
 *
 * <ul>
 *   <li>{@code run:create} is what set this column on the create path — and
 *       {@code Automation Service} holds it, because opening the run it is about to post results
 *       into is a CI credential's job. Reusing it here would let a leaked token reorganise a team's
 *       workload.</li>
 *   <li>{@code result:submit} is about the verdict, which this write deliberately does not touch;
 *       {@code Automation Service} holds that one too.</li>
 *   <li>{@code run:execute} is <em>"may this caller work a run by hand"</em>, and it is the one key
 *       in the catalogue that separates a <strong>person</strong> from a <strong>credential</strong>
 *       rather than one role from another — see {@link ExecutionService}, which wired it. Handing a case to
 *       a tester is part of working a run by hand.</li>
 * </ul>
 *
 * <p><strong>No new permission was seeded</strong>, and that is a rule rather than a shortcut: the
 * catalogue has taken no new key since {@code V3}, and a key seeded to nobody is a 403 no
 * administrator can grant their way out of (roles spec §A6).
 *
 * <h2>Why this is not a method on {@link ExecutionService}</h2>
 *
 * <p>That class's whole shape is the three-permission verdict story — which key applies is a
 * property of the row, so the row is read before the permission is known, and its {@code Outcome}
 * carries {@code amending} and {@code NotExecuting} because the two refusals need opposite advice.
 * None of that is true here: one key, known before anything is read, and a refusal that means "ask a
 * lead" rather than "use the other door". Folding this in would have widened a sealed interface
 * whose arms are load-bearing, and every controller switch over it, to express a case with nothing
 * in common with them.
 *
 * <p>What <em>is</em> shared is shared rather than copied: {@link ExecutionTargets} resolves the
 * three-segment path (§72's extraction), {@link ExecutionPeople#isActiveMember} is the same
 * predicate {@link RunCreateService} applies to the same column, and the response body comes from
 * {@link ExecutionService#detail} so a write answers what the read that follows it answers.
 */
@Service
public class ExecutionAssignmentService {

    private static final String PERMISSION = "run:execute";

    private final ProjectService projects;
    private final ExecutionTargets targets;
    private final TestExecutionRepository executions;
    private final ExecutionPeople people;

    /** Injected for {@code detail} alone — the one assembler every response on this row shares. */
    private final ExecutionService rendering;

    private final AuditEventRepository auditEvents;
    private final Clock clock;

    public ExecutionAssignmentService(
            ProjectService projects,
            ExecutionTargets targets,
            TestExecutionRepository executions,
            ExecutionPeople people,
            ExecutionService rendering,
            AuditEventRepository auditEvents,
            Clock clock) {

        this.projects = projects;
        this.targets = targets;
        this.executions = executions;
        this.people = people;
        this.rendering = rendering;
        this.auditEvents = auditEvents;
        this.clock = clock;
    }

    /** What happened. Sealed, so the controller's mapping is exhaustive at compile time. */
    public sealed interface Outcome {

        /** Unreachable project, run or execution — one bodiless 404 (§6t). */
        record NotFound() implements Outcome {
        }

        /** Readable, and {@code run:execute} is not held. Bodiless 403. */
        record Forbidden() implements Outcome {
        }

        /** Field name to messages, for the picker to land its sentence on itself. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /**
         * The run has ended, so its checklist is frozen — the same {@code V1} rule the verdict and
         * its evidence are under, and a <strong>409</strong> for the same reason: nothing is wrong
         * with the caller or the payload, and reopening the run is the way forward.
         *
         * <p>Freezing assignment as well as the verdict is a judgement and it is worth stating.
         * Assignment is scheduling rather than a record of what happened, so it could defensibly
         * stay open. It does not, because a closed run has no work left to hand out: an assignment
         * written into one would be a task nobody can act on, appearing on somebody's queue, which
         * is a worse answer than a refusal naming the way back.
         */
        record RunClosed() implements Outcome {
        }

        /** Assigned — or unassigned. 200, with the whole execution. */
        record Assigned(TestExecutionView view) implements Outcome {
        }
    }

    /**
     * Sets — or clears — the assignee.
     *
     * <p>Idempotent, and an assignment that changes nothing writes no audit row: two people handing
     * the same case to the same tester are agreeing, not conflicting, and a row per repeated click
     * buries the change that happened ({@code ArtifactWriteService.setStatus}'s call, one module
     * over).
     *
     * <p><strong>No {@code If-Match}.</strong> The verdict PATCH takes one because two testers
     * recording results on one row is the ordinary case and a lost verdict is unrecoverable.
     * Requiring it here would refuse a coordinator's reassignment because somebody recorded a result
     * in between — a conflict about a field neither call touched. The save still bumps
     * {@code lockVersion}, so a tester holding a stale read gets the ordinary 409 on their next save
     * and reloads, which is the behaviour any concurrent write already produces.
     */
    @Transactional
    public Outcome assign(
            UUID userId,
            UUID projectId,
            UUID runId,
            UUID executionId,
            ExecutionAssigneeRequest request) {

        UUID companyId = CompanyContext.require();

        // The read gate first, so an unreachable target is a 404 before the permission is consulted
        // — ExecutionService's ordering, and for its reason: 403 on a path naming a sibling
        // project's run would confirm that run exists.
        Optional<ExecutionTargets.Target> found =
                targets.resolve(userId, projectId, runId, executionId);
        if (found.isEmpty()) {
            return new Outcome.NotFound();
        }
        ExecutionTargets.Target target = found.get();

        GateResult<Outcome> gate = projects.writeGranted(userId, projectId, PERMISSION,
                project -> apply(companyId, userId, target, request));

        return switch (gate) {
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            // Answered by the read above. The arm stays written and returns the same 404 rather
            // than throwing, because the two gates could disagree only if a grant were revoked
            // between them — in which case 404 is still the right answer.
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
        };
    }

    private Outcome apply(
            UUID companyId,
            UUID userId,
            ExecutionTargets.Target target,
            ExecutionAssigneeRequest request) {

        TestExecution execution = target.execution();

        // Before validation, as on the verdict: a member id the caller could fix is not worth
        // reporting to somebody whose save cannot succeed at all until the run is reopened.
        if (target.run().isClosed()) {
            return new Outcome.RunClosed();
        }

        UUID assigneeId = request == null ? null : request.assigneeId();
        if (assigneeId != null && !people.isActiveMember(companyId, assigneeId)) {
            // isActiveMember, NOT resolve — RunCreateService's own comment says why, and it is the
            // whole reason this predicate is read from there rather than re-spelled: app_user is
            // instance-level (ERD §0.1's exception list), so "the account exists" accepts a user id
            // belonging to another client entirely and puts a stranger's name on this company's
            // screens.
            return new Outcome.Invalid(Map.of(
                    "assigneeId", List.of("That person is not a member of this company.")));
        }

        if (Objects.equals(execution.getAssigneeId(), assigneeId)) {
            // Idempotent: the row already says this. Answered with the execution and no audit row.
            return new Outcome.Assigned(rendering.detail(companyId, execution));
        }

        Instant now = clock.instant();
        UUID previous = execution.getAssigneeId();
        execution.assignTo(assigneeId, now);
        // saveAndFlush, not save: @Version is bumped at flush and the response carries lockVersion
        // for the client to send back as its next If-Match on the verdict.
        executions.saveAndFlush(execution);

        // The action name and the changes document are ExecutionAssignmentAudit's since §244 —
        // the bulk endpoint writes the same fact about a row and the two must read identically in
        // the trail, which a second concatenation here could not guarantee.
        auditEvents.save(ExecutionAssignmentAudit.row(
                execution.getProjectId(), userId, execution.getId(), previous, assigneeId, now));

        return new Outcome.Assigned(rendering.detail(companyId, execution));
    }
}
