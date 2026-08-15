package com.tcms.execution;

import com.tcms.execution.CaseFacts.PinnedCase;
import com.tcms.project.CaseStatusView;
import com.tcms.project.GateResult;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.Outbox;
import com.tcms.tenancy.OutboxEvents;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One execution — the read, and <strong>the write that is the point of this module</strong>.
 *
 * <h2>Three permissions, and collapsing any two undoes a boundary the roles spec draws</h2>
 *
 * <p><strong>Every call needs {@code run:execute}</strong> — added 2026-08-07 as plan §8 #53, the
 * key's first consumer since {@code V3} seeded it in M0. See {@link #EXECUTE_RUN}: it is the only
 * permission in the catalogue that separates a person from a credential rather than one role from
 * another, and it is the reason a leaked CI token can post a red suite and cannot hand-record a
 * green verdict over the top of it.
 *
 * <p>On top of that: recording a verdict where none exists needs {@code result:submit}, which a Test
 * Engineer and even the Automation Service hold. Changing a verdict that is already there needs
 * {@code result:amend}, a <strong>separate key</strong> — <del>which is Lead-only: roles spec §A4
 * lists it beside {@code suite:delete} under "creative vs destructive", and a Test Engineer is never
 * blocked from producing, only from unmaking somebody else's work</del> → <strong>superseded by
 * {@code V52} (§210): a Test Engineer holds it.</strong> The creative-versus-destructive line is
 * retired, not moved. What the split still buys is the whole reason this class reads the row before
 * it knows its permission, and it is unchanged: {@code Automation Service} holds
 * {@code result:submit} and not {@code result:amend}, so gating both on the first lets a leaked CI
 * token rewrite a human's verdict — precisely the smallest-role argument §A6 makes about that
 * token. The key that separated two roles now separates a person from a credential.
 *
 * <p><strong>The three 403s are worded differently</strong>, for §32's reason: telling a tester who
 * records results every day that they may not record results sends them to ask an administrator for
 * a permission they already hold, about a screen they are looking at.
 *
 * <h2>The permission depends on the row, so the row is read first — and that leaks nothing</h2>
 *
 * <p>Every other write in the product knows its permission before it reads anything. This one does
 * not: which permission applies is a property of the execution. So the row is resolved through the
 * <em>read</em> gate first, and only then is the write gate asked. A caller who reaches that point
 * has already passed the read gate, so learning that the execution exists tells them nothing
 * {@code GET} would not — the 404-not-403 rule is about concealing existence from callers who
 * cannot read, and it is intact.
 *
 * <p>The cost is one extra permission evaluation per write, and it buys the property that matters:
 * the verdict is still computed in exactly one place, {@link ProjectService#writeGranted}, rather
 * than by this service deciding for itself once it has looked at the row.
 *
 * <h2>Validation runs inside the gate, never before it</h2>
 *
 * <p>The status check reports whether a status id is real. Run before the gate, this endpoint would
 * answer "unknown status" to a caller who may not record anything — a probe for the project's
 * vocabulary handed to exactly the person just refused (§34's {@code thePermissionIsCheckedFirst}).
 * It is structural rather than remembered: validation lives in the callback {@code writeGranted}
 * invokes only when the caller is allowed. <strong>The run-closed refusal sits in the same
 * callback</strong>, for the same reason and not because it is secret — a caller who may read the
 * execution can already see the run's status.
 *
 * <h2>This method is where a run starts</h2>
 *
 * <p>{@code TestRun.create} has said since §42 that a run begins when somebody records the first
 * result rather than when it is named, and until §72 <strong>nothing implemented it</strong> — so
 * {@code test_run.status} was written once, at creation, and every run in the product read "not
 * started" forever. {@link TestRunRepository#startIfNotStarted} is called on every result and
 * answers whether this one was the first; it needs no permission of its own, because the caller has
 * just passed the gates above and a second one here would let a tester record a result the run then
 * declines to acknowledge.
 *
 * <p>It is a conditional {@code UPDATE} and not an entity mutator <strong>since §155</strong>, and
 * the difference is the whole of that slice: the mutator asked "has this run started" against the
 * snapshot this transaction read, which is a question whose answer can already be out of date by the
 * time the write lands. See the repository method.
 */
@Service
public class ExecutionService {

    /**
     * Roles spec §A4 — <em>"Execute test cases in a run"</em>, held by Test Engineer and up and
     * withheld from <strong>Automation Service alone</strong>.
     *
     * <p>That single withholding is the entire value of this key, and it is why it is worth having
     * beside {@code result:submit} rather than folded into it. Every other execution permission
     * separates a lead from an engineer; this one separates a <em>person</em> from a
     * <em>credential</em>, which no other key in the catalogue does.
     */
    private static final String EXECUTE_RUN = "run:execute";

    private final ProjectService projects;
    private final ExecutionTargets targets;
    private final TestRunRepository runs;
    private final TestExecutionRepository executions;
    private final StepOutcomeRepository stepOutcomes;
    private final ResultStatuses statuses;
    private final ExecutionViews views;
    private final EvidenceViews evidenceViews;
    private final EvidenceRepository evidenceRows;
    private final CaseFacts cases;
    private final AuditEventRepository auditEvents;

    /**
     * The transactional outbox — how the rest of the product learns that this company's results
     * moved (round 3 #52, phase 2).
     *
     * <p>Recorded <strong>inside this transaction</strong>, which is the whole of the pattern: the
     * verdict and the notice of it commit together, so there is no window in which a result was
     * written and nothing downstream was ever told. Neither consumer is reachable from here —
     * {@code reporting}'s fact log and {@code repository}'s health signals are both modules this
     * one may not import (plan §3) — and that is not a limitation being worked around. It is why
     * the notice is a row rather than a call.
     */
    private final Outbox outbox;

    /**
     * The application clock, not {@code Instant.now()} — it is truncated to the resolution
     * of a {@code timestamptz} (see {@code TcmsApplication#clock()}), and a result whose
     * {@code executedAt} is stamped at nanosecond precision comes back from a re-read as a
     * different value than the one this method returned.
     */
    private final Clock clock;

    public ExecutionService(
            ProjectService projects,
            ExecutionTargets targets,
            TestRunRepository runs,
            TestExecutionRepository executions,
            StepOutcomeRepository stepOutcomes,
            ResultStatuses statuses,
            ExecutionViews views,
            EvidenceViews evidenceViews,
            EvidenceRepository evidenceRows,
            CaseFacts cases,
            AuditEventRepository auditEvents,
            Outbox outbox,
            Clock clock) {

        this.projects = projects;
        this.targets = targets;
        this.runs = runs;
        this.executions = executions;
        this.stepOutcomes = stepOutcomes;
        this.statuses = statuses;
        this.views = views;
        this.evidenceViews = evidenceViews;
        this.evidenceRows = evidenceRows;
        this.cases = cases;
        this.auditEvents = auditEvents;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** The outcome of a write, so the controller maps statuses and never re-decides anything. */
    public sealed interface Outcome {

        /** Unreachable project, run or execution — one bodiless 404 (§6t). */
        record NotFound() implements Outcome {
        }

        /**
         * Readable, and the permission this action needs is not held.
         *
         * @param amending true ⇒ the refusal was {@code result:amend} on a result that already
         *                 exists, which needs a different sentence from "you cannot record results"
         */
        record Forbidden(boolean amending) implements Outcome {
        }

        /**
         * Readable, and this caller may not <em>execute</em> the run at all — the refusal that
         * separates a person working through a checklist from a credential posting a CI report.
         *
         * <p>A separate outcome from {@link Forbidden} because the two need opposite advice. A
         * caller refused {@code result:amend} should ask a lead; a caller refused here should stop
         * using this endpoint and use ingestion, and telling them "you do not have permission to
         * record results" would be actively misleading, since the thing they are trying to do is
         * supported and this is not the door for it.
         */
        record NotExecuting() implements Outcome {
        }

        /** Field name to messages, for the form to land on its inputs. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** The {@code If-Match} did not match the stored {@code lockVersion}. */
        record Stale() implements Outcome {
        }

        /**
         * The run has ended, so its results are frozen — {@code V1}'s "immutable once the run
         * closes", which §67 recorded as deliberately unimplemented and §72 implements over both
         * writes at once. A <strong>409</strong>: the caller's permissions are not the problem and
         * the payload is not the problem, the target's state is, and reopening the run is the way
         * forward.
         */
        record RunClosed() implements Outcome {
        }

        /** Recorded. 200, with the whole execution. */
        record Recorded(TestExecutionView view) implements Outcome {
        }
    }

    /** One execution with its pinned steps, or empty for any of the ways it is unreachable. */
    @Transactional(readOnly = true)
    public Optional<TestExecutionView> findOne(
            UUID userId, UUID projectId, UUID runId, UUID executionId) {

        UUID companyId = CompanyContext.require();

        return targets.resolve(userId, projectId, runId, executionId)
                .map(target -> detail(companyId, target.execution()));
    }

    /**
     * Records or amends the verdict.
     *
     * @param expectedVersion the {@code If-Match} the client sent, or null for last-write-wins —
     *                        correct for an importer with no prior read, and the SPA always sends it
     */
    @Transactional
    public Outcome record(
            UUID userId,
            UUID projectId,
            UUID runId,
            UUID executionId,
            ExecutionResultRequest request,
            Long expectedVersion) {

        UUID companyId = CompanyContext.require();
        Instant now = clock.instant();

        Optional<ExecutionTargets.Target> found =
                targets.resolve(userId, projectId, runId, executionId);
        if (found.isEmpty()) {
            return new Outcome.NotFound();
        }
        ExecutionTargets.Target target = found.get();
        TestExecution execution = target.execution();
        boolean amending = execution.hasResult();
        String permission = amending ? "result:amend" : "result:submit";

        // `run:execute`'s first consumer since V3 seeded it in M0 (plan §8 #53) — asked INSIDE
        // the verdict gate, which is the whole design of it.
        //
        // The two keys answer different questions and V3 gives them to different principals.
        // `result:submit` is "may a result for this project be written at all", and Automation
        // Service holds it: ingesting a CI report is its entire purpose. `run:execute` is "may
        // this caller work a run by hand", and Automation Service is the ONE role V3 withholds it
        // from. Without this a leaked CI token could PATCH executions here one at a time — with an
        // If-Match, with evidence, indistinguishable in the audit trail from a tester — which is
        // the laundering §117 and §121 each closed one door on.
        //
        // ORDER IS THE POINT, and the first draft had it wrong. Asked before the gate, a Viewer —
        // who holds neither key — would be told to use the ingestion endpoint, which they may not
        // use either. NotExecuting means "you have the other door", so it may only be raised for a
        // caller who demonstrably does: past the verdict gate, the sentence is true by construction.
        GateResult<Outcome> gate = projects.writeGranted(userId, projectId, permission,
                project -> projects.holds(userId, projectId, EXECUTE_RUN)
                        ? apply(companyId, userId, target, request, expectedVersion, now)
                        : new Outcome.NotExecuting());

        return switch (gate) {
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden(amending);
            // Unreachable is answered by the read above, which ran first and returned NotFound for
            // every unreachable target. The arm stays written and returns the same 404 rather than
            // throwing, because the two gates could disagree only if a grant were revoked between
            // them — in which case 404 is still the right answer.
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
        };
    }

    private Outcome apply(
            UUID companyId,
            UUID userId,
            ExecutionTargets.Target target,
            ExecutionResultRequest request,
            Long expectedVersion,
            Instant now) {

        TestExecution execution = target.execution();
        TestRun run = target.run();

        // Before the version check as well as before validation: a stale If-Match and a malformed
        // step id are both things the caller could fix and resubmit, and neither is worth reporting
        // to somebody whose save cannot succeed at all until the run is reopened.
        if (run.isClosed()) {
            return new Outcome.RunClosed();
        }
        if (expectedVersion != null && expectedVersion != execution.getLockVersion()) {
            // Before validation: a caller working from a stale read may be sending step ids from a
            // version somebody has since repinned, and reporting those as field errors would send
            // them to fix inputs that are not the problem.
            return new Outcome.Stale();
        }

        Map<UUID, CaseStatusView> vocabulary = statuses.byId(execution.getProjectId());
        PinnedCase pin = cases.pinnedCases(List.of(execution.getCaseVersionId()))
                .get(execution.getCaseVersionId());

        Map<String, List<String>> errors = validate(request, vocabulary, pin);
        if (!errors.isEmpty()) {
            return new Outcome.Invalid(errors);
        }

        CaseStatusView previous = execution.getStatusId() == null
                ? null
                : vocabulary.get(execution.getStatusId());

        execution.record(request.statusId(), userId, request.comment(), now);
        // saveAndFlush, not save: @Version is bumped at flush, and the response carries lockVersion
        // for the client to send back as the next If-Match. Deferring the flush to the end of the
        // transaction would answer with the version the caller already had — so their very next save
        // would 409 against a change that was their own.
        executions.saveAndFlush(execution);
        recordSteps(companyId, execution, request);

        // The run starts here or it never starts. Asked unconditionally — the statement's own WHERE
        // clause answers whether this call was the first — and inside the result's own transaction,
        // so a run cannot end up marked as started by a result that rolled back.
        //
        // §155: a conditional UPDATE rather than TestRun.begin, which asked the same question in
        // Java against a snapshot that could already be out of date. Two testers' first results no
        // longer collide, exactly one of them writes the audit row, and a run a lead closed while
        // this result was in flight is not put back to IN_PROGRESS. See the repository method.
        if (runs.startIfNotStarted(run.getProjectId(), run.getId(), userId, now) == 1) {
            auditEvents.save(AuditEvent.record(
                    execution.getProjectId(),
                    userId,
                    "run.started",
                    // "test_run", matching RunCreateService's constant for the same table rather
                    // than the "TEST_EXECUTION" written three lines below: two audit rows about one
                    // table must not spell it two ways, whatever this file already does. (That
                    // outlier predates §72 and is left alone — see the section.)
                    "test_run",
                    List.of(run.getId()),
                    // The key rather than the id, as every other audit row in this module does: a
                    // reader reconstructing "when did TR-4 begin" should not have to join to find
                    // out which run this was.
                    "{\"run\":" + AuditEvent.jsonString(run.getKey()) + "}",
                    now));
        }

        auditEvents.save(AuditEvent.record(
                execution.getProjectId(),
                userId,
                previous == null ? "execution.recorded" : "execution.amended",
                "TEST_EXECUTION",
                List.of(execution.getId()),
                // The case key and both verdicts. An audit reader needs to know WHICH case changed
                // and what it changed FROM — an amendment recorded as "status: Passed" is
                // indistinguishable from the original submission, which is the one distinction the
                // Lead-only permission exists to make visible.
                "{\"case\":" + AuditEvent.jsonString(pin == null ? "" : pin.caseKey())
                        + ",\"from\":" + (previous == null
                                ? "null" : AuditEvent.jsonString(previous.name()))
                        + ",\"to\":" + AuditEvent.jsonString(
                                vocabulary.get(request.statusId()).name())
                        + "}",
                now));

        // The fact log and the health signals both derive from this row and neither module can be
        // called from here. COALESCED, not one event per verdict: both handlers are whole-company
        // recomputes that take no arguments by design, and a tester working through a run records
        // fifty results in a few minutes — fifty identical full projections would be the cost of
        // saying one thing fifty times. See Outbox#recordCoalesced.
        outbox.recordCoalesced(OutboxEvents.RESULTS_CHANGED);

        // Re-read through the same renderer the GET uses, so the response to a write is the response
        // to the read that follows it — the property §34 names about ProjectService#viewOfCreated.
        return new Outcome.Recorded(detail(companyId, execution));
    }

    /**
     * Every field error the payload has, gathered rather than reported one at a time.
     *
     * <p>A form that fixes one message and resubmits to find a second is a form that takes three
     * round trips to satisfy — and the caller here is a tester at the end of a test, which is the
     * worst moment to be sent round a loop.
     */
    private static Map<String, List<String>> validate(
            ExecutionResultRequest request,
            Map<UUID, CaseStatusView> vocabulary,
            PinnedCase pin) {

        Map<String, List<String>> errors = new LinkedHashMap<>();

        if (request == null || request.statusId() == null) {
            errors.put("statusId", List.of("Choose a result."));
            return errors;
        }
        if (!vocabulary.containsKey(request.statusId())) {
            // A status from another project resolves to nothing here — the vocabulary is
            // project-scoped — so this covers "no such status" and "not this project's status"
            // alike, and both are the same mistake from the caller's side.
            errors.put("statusId", List.of("That result status is not one this project uses."));
        }

        List<StepFault> faults = stepFaults(request, vocabulary, pin);
        if (!faults.isEmpty()) {
            errors.put("stepResults", faults.stream().map(StepFault::message).toList());
        }
        return errors;
    }

    private record StepFault(String message) {
    }

    private static List<StepFault> stepFaults(
            ExecutionResultRequest request,
            Map<UUID, CaseStatusView> vocabulary,
            PinnedCase pin) {

        List<StepFault> faults = new ArrayList<>();
        if (request.stepResults() == null || request.stepResults().isEmpty()) {
            return faults;
        }

        List<UUID> pinnedStepIds = pin == null
                ? List.of()
                : pin.steps().stream().map(CaseFacts.PinnedStep::stepId).toList();

        for (ExecutionResultRequest.StepResultRequest step : request.stepResults()) {
            if (step.stepId() == null) {
                faults.add(new StepFault("A step result is missing its stepId."));
                continue;
            }
            if (!pinnedStepIds.contains(step.stepId())) {
                // Named rather than dropped: the tester marked something, and a save that reports
                // success while discarding a mark is the failure mode this endpoint most has to
                // avoid — the mark is the record of work already done.
                faults.add(new StepFault(
                        "Step " + step.stepId() + " is not part of the version this run pinned."));
                continue;
            }
            if (step.statusId() != null && !vocabulary.containsKey(step.statusId())) {
                faults.add(new StepFault(
                        "Step " + step.stepId() + " names a result status this project does not use."));
            }
        }
        return faults;
    }

    /**
     * Writes the step verdicts, updating what exists and inserting what does not.
     *
     * <p>{@code uq_step_result (execution_id, step_id)} makes the update-or-insert mandatory rather
     * than stylistic: inserting unconditionally makes a tester's second save on the same step a
     * constraint violation, which arrives as a 500 in the middle of recording a result.
     */
    private void recordSteps(
            UUID companyId, TestExecution execution, ExecutionResultRequest request) {

        if (request.stepResults() == null || request.stepResults().isEmpty()) {
            return;
        }
        Map<UUID, StepOutcome> existing = existingSteps(execution.getId());
        List<StepOutcome> written = new ArrayList<>();

        for (ExecutionResultRequest.StepResultRequest step : request.stepResults()) {
            StepOutcome outcome = existing.get(step.stepId());
            if (outcome == null) {
                written.add(StepOutcome.record(
                        companyId, execution.getId(), step.stepId(), step.statusId(),
                        step.comment()));
            } else {
                outcome.amend(step.statusId(), step.comment());
                written.add(outcome);
            }
        }
        stepOutcomes.saveAll(written);
    }

    private Map<UUID, StepOutcome> existingSteps(UUID executionId) {
        Map<UUID, StepOutcome> byStep = new HashMap<>();
        for (StepOutcome outcome : stepOutcomes.findForExecutions(List.of(executionId))) {
            byStep.put(outcome.getStepId(), outcome);
        }
        return byStep;
    }

    /** The whole execution, rendered through the one assembler both endpoints share. */
    private TestExecutionView detail(UUID companyId, TestExecution execution) {
        Map<UUID, CaseStatusView> vocabulary = statuses.byId(execution.getProjectId());
        PinnedCase pin = cases.pinnedCases(List.of(execution.getCaseVersionId()))
                .get(execution.getCaseVersionId());

        TestExecutionSummaryView summary =
                views.render(companyId, List.of(execution), vocabulary).getFirst();

        // One read, partitioned in one pass — evidence on the execution as a whole
        // (step_result_id IS NULL) and evidence on a particular step. Two reads or a per-step
        // lookup would be the N+1 that looks harmless because each query is fast.
        EvidenceViews.Partitioned evidence = evidenceViews.partition(
                companyId,
                execution.getProjectId(),
                execution.getRunId(),
                evidenceRows.findByExecutionIdOrderByCreatedAtAsc(execution.getId()));

        return TestExecutionView.of(
                summary,
                execution.getComment(),
                execution.getReleaseVersion(),
                pin == null ? StepFormat.STRUCTURED : pin.stepFormat(),
                pin == null ? List.of() : pin.preconditions(),
                pin == null ? List.of() : pin.postconditions(),
                views.steps(pin, existingSteps(execution.getId()), evidence.byStepResult()),
                evidence.onExecution(),
                execution.getLockVersion());
    }
}
