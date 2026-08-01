package com.tcms.execution;

import com.tcms.execution.CaseFacts.PinnedCase;
import com.tcms.project.CaseStatusView;
import com.tcms.project.GateResult;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import com.tcms.tenancy.CompanyContext;
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
 * <h2>Two permissions, and collapsing them undoes a boundary the roles spec draws</h2>
 *
 * <p>Recording a verdict where none exists needs {@code result:submit}, which a Test Engineer and
 * even the Automation Service hold. Changing a verdict that is already there needs
 * {@code result:amend}, which is <strong>Lead-only</strong> — roles spec §A4 lists it beside
 * {@code suite:delete} under "creative vs destructive": a Test Engineer is never blocked from
 * producing, only from unmaking somebody else's work, and overwriting a recorded result is unmaking
 * it. Gate both on {@code result:submit} and a leaked CI token can rewrite a human's verdict, which
 * is precisely the smallest-role argument §A6 makes about that token.
 *
 * <p><strong>The two 403s are worded differently</strong>, for §32's reason: telling a tester who
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
 * invokes only when the caller is allowed.
 */
@Service
public class ExecutionService {

    private final ProjectService projects;
    private final TestRunRepository runs;
    private final TestExecutionRepository executions;
    private final StepOutcomeRepository stepOutcomes;
    private final ResultStatuses statuses;
    private final ExecutionViews views;
    private final CaseFacts cases;
    private final AuditEventRepository auditEvents;

    /**
     * The application clock, not {@code Instant.now()} — it is truncated to the resolution
     * of a {@code timestamptz} (see {@code TcmsApplication#clock()}), and a result whose
     * {@code executedAt} is stamped at nanosecond precision comes back from a re-read as a
     * different value than the one this method returned.
     */
    private final Clock clock;

    public ExecutionService(
            ProjectService projects,
            TestRunRepository runs,
            TestExecutionRepository executions,
            StepOutcomeRepository stepOutcomes,
            ResultStatuses statuses,
            ExecutionViews views,
            CaseFacts cases,
            AuditEventRepository auditEvents,
            Clock clock) {

        this.projects = projects;
        this.runs = runs;
        this.executions = executions;
        this.stepOutcomes = stepOutcomes;
        this.statuses = statuses;
        this.views = views;
        this.cases = cases;
        this.auditEvents = auditEvents;
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

        /** Field name to messages, for the form to land on its inputs. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** The {@code If-Match} did not match the stored {@code lockVersion}. */
        record Stale() implements Outcome {
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

        return resolve(userId, projectId, runId, executionId)
                .map(execution -> detail(companyId, execution));
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

        Optional<TestExecution> found = resolve(userId, projectId, runId, executionId);
        if (found.isEmpty()) {
            return new Outcome.NotFound();
        }
        TestExecution execution = found.get();
        boolean amending = execution.hasResult();
        String permission = amending ? "result:amend" : "result:submit";

        GateResult<Outcome> gate = projects.writeGranted(userId, projectId, permission,
                project -> apply(companyId, userId, execution, request, expectedVersion, now));

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

    /**
     * The row, through both read gates.
     *
     * <p>Three predicates, and each one closes a different path: the project gate (may they see this
     * project), the run's project (is that run in it), and the execution's run (is that execution in
     * <em>that</em> run). Dropping the last would serve an execution from a different run through a
     * path naming the run the client is looking at — a result on the wrong screen, with no error.
     */
    private Optional<TestExecution> resolve(
            UUID userId, UUID projectId, UUID runId, UUID executionId) {

        return projects.readVisible(userId, projectId, project -> project)
                .flatMap(project -> runs.findLiveInProject(project.getId(), runId))
                .flatMap(run -> executions.findInRun(run.getProjectId(), run.getId(), executionId));
    }

    private Outcome apply(
            UUID companyId,
            UUID userId,
            TestExecution execution,
            ExecutionResultRequest request,
            Long expectedVersion,
            Instant now) {

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

        return TestExecutionView.of(
                summary,
                execution.getComment(),
                execution.getReleaseVersion(),
                pin == null ? StepFormat.STRUCTURED : pin.stepFormat(),
                pin == null ? List.of() : pin.preconditions(),
                pin == null ? List.of() : pin.postconditions(),
                views.steps(pin, existingSteps(execution.getId())),
                execution.getLockVersion());
    }
}
