package com.tcms.execution;

import com.tcms.project.GateResult;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import com.tcms.tenancy.Outbox;
import com.tcms.tenancy.OutboxEvents;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ending a run, and un-ending it — <strong>{@code run:close}'s first consumer in Java</strong>.
 *
 * <p>The permission has been seeded by {@code V3} and correctly granted since M0, and no code had
 * ever asked for it: the fifth instance of that shape after §62's {@code case:approve}, §64's
 * {@code library:manage}, §69's {@code company:vocabulary} and §71's {@code company:teams}. Unlike
 * §71's, this one is <em>discriminating</em> — {@code V3} grants {@code run:execute} and
 * {@code result:submit} to Test Engineer and withholds {@code run:close}, so a persona the suite can
 * actually build tells this gate apart from the ones beside it, and the mutation that swaps them
 * reddens tests rather than nothing.
 *
 * <h2>Why this is a separate service and not a method on {@link RunService}</h2>
 *
 * <p>{@code RunService} is entirely {@code readOnly}, and {@link RunCreateService} already
 * established that a write on this aggregate lives in its own class. The response still comes from
 * {@link RunService#viewOf} rather than a second assembler, so the run a status change returns is
 * byte-for-byte the run the list will show on the next read — {@code PlanService#viewOf}'s rule.
 *
 * <h2>No {@code If-Match}</h2>
 *
 * <p>Every other write in this module carries one. This one does not, and the difference is that
 * the request names an <em>absolute</em> target state rather than a delta against something the
 * client read: two leads both clicking Complete want the same outcome and get it, which
 * {@link RunLifecycle.Decision.Unchanged} answers 200. A lost update needs a value derived from a
 * stale read, and there is none here. The precedent is {@code DocPageWriteService#setStatus}, which
 * takes no {@code If-Match} either, for the same reason.
 */
@Service
public class RunStatusService {

    /** Roles spec §A4 — Lead and up. Deliberately NOT held by Test Engineer: closing is destructive. */
    private static final String CLOSE_RUN = "run:close";

    private static final String AUDIT_TARGET = "test_run";

    private final ProjectService projects;
    private final TestRunRepository runs;
    private final RunService views;
    private final AuditEventRepository auditEvents;
    private final Outbox outbox;
    private final Clock clock;

    public RunStatusService(
            ProjectService projects,
            TestRunRepository runs,
            RunService views,
            AuditEventRepository auditEvents,
            Outbox outbox,
            Clock clock) {

        this.projects = projects;
        this.runs = runs;
        this.views = views;
        this.auditEvents = auditEvents;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** What happened. Sealed, so the controller's switch over it is exhaustive at compile time. */
    public sealed interface Outcome {

        /** Unreachable project or run, or a run in a sibling project — one bodiless 404 (§6t). */
        record NotFound() implements Outcome {
        }

        /** Readable, and {@code run:close} is not held here. */
        record Forbidden() implements Outcome {
        }

        /** A status this lifecycle does not have. 400, on the field. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** A move the run's current state refuses. 409, with the sentence {@link RunLifecycle} chose. */
        record Refused(String message) implements Outcome {
        }

        /** Moved, or already there. 200 with the run either way. */
        record Updated(TestRunView view) implements Outcome {
        }
    }

    /**
     * Closes, aborts or reopens the run.
     *
     * <p>The permission is asked <strong>before</strong> the run is resolved, unlike
     * {@link ExecutionService#record} — there the permission depends on the row, and here it does
     * not. {@link ProjectService#writeGranted} answers the unreachable project with a 404 of its
     * own, so the disclosure rule holds on both paths.
     */
    @Transactional
    public Outcome setStatus(
            UUID userId, UUID projectId, UUID runId, RunStatusRequest request) {

        GateResult<Outcome> gated = projects.writeGranted(userId, projectId, CLOSE_RUN,
                project -> apply(userId, project.getId(), runId, request));

        return switch (gated) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    private Outcome apply(UUID userId, UUID projectId, UUID runId, RunStatusRequest request) {
        RunStatus requested = parse(request == null ? null : request.status());
        if (requested == null) {
            return new Outcome.Invalid(Map.of("status", List.of(
                    "Choose a status: NOT_STARTED, IN_PROGRESS, COMPLETED or ABORTED.")));
        }

        // After the parse, and both inside the gate: a caller who may not close runs learns neither
        // which run ids exist nor what this project's lifecycle accepts.
        Optional<TestRun> found = runs.findLiveInProject(projectId, runId);
        if (found.isEmpty()) {
            return new Outcome.NotFound();
        }
        TestRun run = found.get();

        RunLifecycle.Decision decision = RunLifecycle.decide(
                run.getStatus(), requested, run.getStartedAt() != null);

        return switch (decision) {
            case RunLifecycle.Decision.Refused refused ->
                    new Outcome.Refused(refused.message());
            // 200 with the run, and nothing written: no timestamp re-stamped, no audit row. See
            // Decision.Unchanged — a repeated click must not bury the change that happened.
            case RunLifecycle.Decision.Unchanged ignored ->
                    new Outcome.Updated(views.viewOf(run));
            case RunLifecycle.Decision.Move move -> move(userId, projectId, run, move.target());
        };
    }

    private Outcome move(UUID userId, UUID projectId, TestRun run, RunStatus target) {
        Instant now = clock.instant();
        RunStatus previous = run.getStatus();

        run.applyStatus(target, userId, now);
        runs.saveAndFlush(run);

        auditEvents.save(AuditEvent.record(
                projectId,
                userId,
                action(target),
                AUDIT_TARGET,
                List.of(run.getId()),
                // Both ends of the move, not just the new one. A run that was completed, reopened
                // and aborted is three events, and "aborted" alone does not say which of the two
                // endings the second one replaced — the same argument ExecutionService makes for
                // carrying `from` on an amendment.
                "{\"run\":" + AuditEvent.jsonString(run.getKey())
                        + ",\"from\":" + AuditEvent.jsonString(previous.name())
                        + ",\"to\":" + AuditEvent.jsonString(target.name())
                        + "}",
                now));

        TestRunView view = views.viewOf(run);
        if (target == RunStatus.COMPLETED) {
            announce(projectId, view);
        }
        return new Outcome.Updated(view);
    }

    /**
     * Records that this run finished, for whoever is subscribed to hear about it.
     *
     * <p><strong>Inside this transaction, which is the entire pattern.</strong> The run's new
     * status and the notice of it commit together or neither does — no window in which a run
     * closed and its announcement was lost, none in which a channel was told about a close that
     * rolled back. {@code Outbox#record} throws if it is called outside one rather than trusting
     * this comment.
     *
     * <p><strong>{@code COMPLETED} only.</strong> Aborting is not completing, and a reopen is not
     * an ending. A single "the status moved" event would push the question of which moves matter
     * onto every subscriber, and the first one to get it wrong announces a reopened run as
     * finished.
     *
     * <p>The payload is the message, not a pointer to this row: {@code integration} may not read
     * {@code test_run} and never will be able to, so a consumer that had to look anything up could
     * not exist. {@link RunCompletedEvent} is this module's declaration of that shape and its
     * header says what keeps it honest against the consumer's.
     *
     * <p>Not coalesced. A coalesced event stands for every change since the last delivery, so its
     * payload could name only one of them truthfully — and a notice whose whole content is the
     * payload cannot be sent from an event that has none.
     */
    private void announce(UUID projectId, TestRunView view) {
        outbox.record(
                RunCompletedEvent.AGGREGATE,
                view.id(),
                OutboxEvents.RUN_COMPLETED,
                RunCompletedEvent.of(UUID.randomUUID(), projectId, view));
    }

    /**
     * The audit action, by where the run landed.
     *
     * <p>Three verbs rather than one {@code run.status_changed}: plan §7's audit vocabulary lists
     * {@code run.closed} by name, and an audit reader filtering for "who ended runs last quarter"
     * should not have to parse a payload to exclude the reopenings.
     */
    private static String action(RunStatus target) {
        return switch (target) {
            case COMPLETED -> "run.closed";
            case ABORTED -> "run.aborted";
            case NOT_STARTED, IN_PROGRESS -> "run.reopened";
        };
    }

    /** Null for anything that is not a status — reported as a field error, never thrown. */
    private static RunStatus parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return RunStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException notAStatus) {
            return null;
        }
    }
}
