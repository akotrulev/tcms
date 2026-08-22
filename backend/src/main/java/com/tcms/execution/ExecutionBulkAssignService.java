package com.tcms.execution;

import com.tcms.api.UserRef;
import com.tcms.project.GateResult;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.AuditEventRepository;
import com.tcms.tenancy.CompanyContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handing many rows of a run's checklist out in one act.
 *
 * <h2>Why this is its own class and not a method on {@link ExecutionAssignmentService}</h2>
 *
 * <p>That class answers about ONE row and its {@code Outcome} is shaped for one: {@code Assigned}
 * carries a whole {@link TestExecutionView}, and {@code Invalid} names one field. A bulk act's
 * answer is three lists, its refusals are act-shaped rather than row-shaped, and it needs a
 * transaction that spans 500 writes. What is shared is shared rather than copied — {@link
 * ExecutionPeople#isActiveMember} for the predicate, {@link ExecutionAssignmentAudit} for the trail,
 * {@link CaseFacts#pinnedCases} for the display key — which is the arrangement §72 established here.
 *
 * <h2>The whole act is one transaction, and that is a requirement rather than a default</h2>
 *
 * <p>The partial-failure arm depends on it: a stranger in {@code assigneeIds} is found while
 * validating, and nothing may be written including no half-used {@code bulkOperationId}. A service
 * that saved as it went would leave a group whose members are the rows it got to before the fault.
 *
 * <h2>Determinism is this operation's idempotency</h2>
 *
 * <p>There is no {@code Idempotency-Key}. Nothing is created here, so a replay would have no
 * resource to be answered with — the mechanism this repository calls idempotency is a unique
 * {@code idempotency_key} column on a created row. Instead the mapping is a pure function of the
 * run's row order and the caller's assignee order, and a row already in the requested state is
 * reported {@code unchanged} and writes no audit row — so a replay is a no-op that can be asserted.
 * The contract states this where a client can read it; {@code
 * ExecutionBulkAssignTest.aRetryChangesNothingAndWritesNoSecondOperation} is what keeps it true.
 */
@Service
public class ExecutionBulkAssignService {

    /** The same key the single-row write takes — see {@link ExecutionAssignmentService}. */
    private static final String PERMISSION = "run:execute";

    private static final int MAX_SELECTION = 500;
    private static final int MAX_ASSIGNEES = 50;

    private static final String ASSIGN = "ASSIGN";
    private static final String DISTRIBUTE = "DISTRIBUTE";

    private final ProjectService projects;
    private final TestRunRepository runs;
    private final TestExecutionRepository executions;
    private final ExecutionPeople people;
    private final CaseFacts cases;
    private final AuditEventRepository auditEvents;
    private final Clock clock;

    public ExecutionBulkAssignService(
            ProjectService projects,
            TestRunRepository runs,
            TestExecutionRepository executions,
            ExecutionPeople people,
            CaseFacts cases,
            AuditEventRepository auditEvents,
            Clock clock) {

        this.projects = projects;
        this.runs = runs;
        this.executions = executions;
        this.people = people;
        this.cases = cases;
        this.auditEvents = auditEvents;
        this.clock = clock;
    }

    /** What happened. Sealed, so {@link RunController}'s mapping is exhaustive at compile time. */
    public sealed interface Outcome {

        /** Unreachable project or run — one bodiless 404 (§6t). */
        record NotFound() implements Outcome {
        }

        /** Readable, and {@code run:execute} is not held. Bodiless 403. */
        record Forbidden() implements Outcome {
        }

        /** Field name to messages. Act-shaped faults only; a bad ROW is a skip, not a fault. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** The run has ended, so its checklist is frozen. 409, and reopening is the way forward. */
        record RunClosed() implements Outcome {
        }

        /** 200 — the result, or the preview. */
        record Applied(ExecutionBulkAssignResult result) implements Outcome {
        }
    }

    /**
     * Assigns, or distributes, over a selection.
     *
     * <p>The read gate first, so an unreachable target is a 404 before the permission is consulted
     * — {@link ExecutionTargets}' ordering and its reason: a 403 on a path naming a sibling
     * project's run would confirm that run exists. The execution segment is not in this path, so
     * the resolution stops at the run rather than reusing {@code ExecutionTargets}.
     */
    @Transactional
    public Outcome assign(
            UUID userId, UUID projectId, UUID runId, ExecutionBulkAssignRequest request) {

        UUID companyId = CompanyContext.require();

        Optional<TestRun> found = projects.readVisible(userId, projectId, project -> project)
                .flatMap(project -> runs.findLiveInProject(project.getId(), runId));
        if (found.isEmpty()) {
            return new Outcome.NotFound();
        }
        TestRun run = found.get();

        GateResult<Outcome> gate = projects.writeGranted(userId, projectId, PERMISSION,
                project -> apply(companyId, userId, run, request));

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
            UUID companyId, UUID userId, TestRun run, ExecutionBulkAssignRequest request) {

        // Before validation, as on the verdict and the single assign: a field the caller could fix
        // is not worth reporting to somebody whose save cannot succeed at all until the run is
        // reopened.
        if (run.isClosed()) {
            return new Outcome.RunClosed();
        }

        Map<String, List<String>> errors = validate(companyId, request);
        if (!errors.isEmpty()) {
            return new Outcome.Invalid(errors);
        }

        // Duplicates collapsed, order irrelevant here — the rows are re-ordered by the run below.
        List<UUID> selection = List.copyOf(new LinkedHashSet<>(request.executionIds()));
        List<TestExecution> rows =
                executions.findAllInRun(run.getProjectId(), run.getId(), selection);

        // Anything the query did not return is not in this run: a stale selection, which is an
        // ordinary event and a SKIP rather than a 404 over the other 299 rows.
        Set<UUID> present = new HashSet<>();
        rows.forEach(row -> present.add(row.getId()));
        List<ExecutionBulkAssignResult.SkippedRow> skipped = new ArrayList<>();
        for (UUID id : selection) {
            if (!present.contains(id)) {
                skipped.add(new ExecutionBulkAssignResult.SkippedRow(
                        id, "That execution is not part of this run."));
            }
        }

        // The mapping. ORDERED BY THE RUN, never by the request array — two coordinators selecting
        // the same rows must get the same split, and a set has no order of its own.
        Map<UUID, CaseFacts.PinnedCase> pinned =
                cases.pinnedCases(rows.stream().map(TestExecution::getCaseVersionId).toList());
        List<TestExecution> ordered = inRunOrder(rows, pinned);
        Map<UUID, UUID> destination = destinations(ordered, request);

        Instant now = clock.instant();
        UUID bulkOperationId = UUID.randomUUID();

        List<ExecutionBulkAssignResult.AssignmentChange> changed = new ArrayList<>();
        List<String> unchanged = new ArrayList<>();
        Map<UUID, UserRef> names = people.resolve(companyId, new LinkedHashSet<>(
                destination.values().stream().filter(Objects::nonNull).toList()));

        for (TestExecution execution : ordered) {
            UUID to = destination.get(execution.getId());
            if (Objects.equals(execution.getAssigneeId(), to)) {
                // Already there. No write, no audit row — the single-row endpoint's rule, and what
                // makes a replay a no-op.
                unchanged.add(keyOf(execution, pinned));
                continue;
            }
            UserRef landing = to == null ? null : names.get(to);
            changed.add(new ExecutionBulkAssignResult.AssignmentChange(
                    execution.getId(),
                    keyOf(execution, pinned),
                    to,
                    landing == null ? null : landing.displayName()));

            if (!request.previewing()) {
                UUID from = execution.getAssigneeId();
                execution.assignTo(to, now);
                executions.save(execution);
                auditEvents.save(ExecutionAssignmentAudit.bulkRow(
                        execution.getProjectId(), userId, execution.getId(), from, to, now,
                        bulkOperationId));
            }
        }

        return new Outcome.Applied(new ExecutionBulkAssignResult(
                request.previewing(),
                selection.size(),
                List.copyOf(changed),
                List.copyOf(unchanged),
                List.copyOf(skipped),
                // Null on a preview, and null when nothing moved: an id naming a group with no
                // members is a claim about rows that do not exist.
                request.previewing() || changed.isEmpty() ? null : bulkOperationId));
    }

    /**
     * The act-shaped faults, collected rather than short-circuited: a coordinator at the end of a
     * long selection should not be sent round the loop twice, which is why {@code
     * ExecutionService.validate} returns a map too.
     */
    private Map<String, List<String>> validate(UUID companyId, ExecutionBulkAssignRequest request) {
        Map<String, List<String>> errors = new LinkedHashMap<>();

        if (request == null || request.executionIds() == null || request.executionIds().isEmpty()) {
            errors.put("executionIds", List.of("Select at least one row of the checklist."));
        } else if (new LinkedHashSet<>(request.executionIds()).size() > MAX_SELECTION) {
            errors.put("executionIds",
                    List.of("Assign at most " + MAX_SELECTION + " rows at a time."));
        }
        if (request == null || request.action() == null) {
            errors.put("action", List.of("Choose whether to assign or to distribute."));
            return errors;
        }

        ExecutionBulkAssignPayload payload =
                request.payload() == null
                        ? new ExecutionBulkAssignPayload(null, null)
                        : request.payload();

        switch (request.action()) {
            case ASSIGN -> {
                if (payload.assigneeIds() != null) {
                    errors.put("payload.assigneeIds",
                            List.of("A list of people is only meaningful when distributing."));
                }
                // assigneeId null is UNASSIGN and is legal — the one place absence is a value.
                if (payload.assigneeId() != null
                        && !people.isActiveMember(companyId, payload.assigneeId())) {
                    errors.put("payload.assigneeId",
                            List.of("That person is not a member of this company."));
                }
            }
            case DISTRIBUTE -> {
                if (payload.assigneeId() != null) {
                    errors.put("payload.assigneeId",
                            List.of("Distributing takes a list of people, not one."));
                }
                List<UUID> assignees = payload.assigneeIds() == null
                        ? List.of()
                        : List.copyOf(new LinkedHashSet<>(payload.assigneeIds()));
                if (assignees.isEmpty()) {
                    errors.put("payload.assigneeIds", List.of("Choose at least one person."));
                } else if (assignees.size() > MAX_ASSIGNEES) {
                    errors.put("payload.assigneeIds",
                            List.of("Distribute across at most " + MAX_ASSIGNEES + " people."));
                } else {
                    // isActiveMember, NOT resolve: app_user is instance-level (ERD §0.1's
                    // exception list), so "the account exists" accepts a user id belonging to
                    // another client entirely and puts a stranger's name on this company's screens.
                    List<String> strangers = assignees.stream()
                            .filter(id -> !people.isActiveMember(companyId, id))
                            .map(UUID::toString)
                            .toList();
                    if (!strangers.isEmpty()) {
                        errors.put("payload.assigneeIds", List.of(
                                "These people are not members of this company: "
                                        + String.join(", ", strangers)));
                    }
                }
            }
            default -> errors.put("action", List.of("Unknown action."));
        }
        return errors;
    }

    /**
     * Which person each row lands on.
     *
     * <p><strong>Round-robin over the RUN's order, across the CALLER's list.</strong> Both halves
     * are deliberate and each closes a different defect:
     *
     * <ul>
     *   <li>Ordering the rows by the run rather than by the request array makes the split a
     *       property of the selection rather than of how it was typed — two coordinators selecting
     *       the same rows get the same answer, and neither could have detected otherwise.</li>
     *   <li>Preserving the caller's assignee order is what decides who takes the remainder. Sorting
     *       it would silently overrule a coordinator who listed the senior tester first, and no
     *       screen would show that it had happened.</li>
     * </ul>
     *
     * <p>Together they make the whole operation a pure function of its input, which is this
     * endpoint's answer to idempotency — see the class header.
     */
    private Map<UUID, UUID> destinations(
            List<TestExecution> ordered, ExecutionBulkAssignRequest request) {

        Map<UUID, UUID> destination = new LinkedHashMap<>();
        ExecutionBulkAssignPayload payload = request.payload();

        if (DISTRIBUTE.equals(request.action())) {
            List<UUID> assignees = List.copyOf(new LinkedHashSet<>(payload.assigneeIds()));
            for (int index = 0; index < ordered.size(); index++) {
                destination.put(
                        ordered.get(index).getId(), assignees.get(index % assignees.size()));
            }
            return destination;
        }

        UUID one = payload == null ? null : payload.assigneeId();
        for (TestExecution execution : ordered) {
            destination.put(execution.getId(), one);
        }
        return destination;
    }

    /**
     * The run's own order — the pinned display-key sequence, tie-broken by id.
     *
     * <p>{@link ExecutionViews#render} sorts the checklist by exactly this key, so the preview
     * reads in the order of the screen behind the dialog. The tiebreak is what makes it
     * <em>total</em>: two rows whose pins are both unresolvable share {@code Long.MAX_VALUE}, and a
     * split that depended on which of them the database returned first would not be reproducible.
     */
    private static List<TestExecution> inRunOrder(
            List<TestExecution> rows, Map<UUID, CaseFacts.PinnedCase> pinned) {

        List<TestExecution> ordered = new ArrayList<>(rows);
        ordered.sort(Comparator
                .comparingLong((TestExecution row) -> keySeqOf(row, pinned))
                .thenComparing(TestExecution::getId));
        return ordered;
    }

    /** An unresolvable pin sorts last — {@link ExecutionViews}' rule, for its reason. */
    private static long keySeqOf(TestExecution row, Map<UUID, CaseFacts.PinnedCase> pinned) {
        CaseFacts.PinnedCase pin = pinned.get(row.getCaseVersionId());
        return pin == null ? Long.MAX_VALUE : pin.keySeq();
    }

    /** The display key the checklist shows, blank for a pin this company cannot read. */
    private static String keyOf(TestExecution row, Map<UUID, CaseFacts.PinnedCase> pinned) {
        CaseFacts.PinnedCase pin = pinned.get(row.getCaseVersionId());
        return pin == null ? "" : pin.caseKey();
    }
}
