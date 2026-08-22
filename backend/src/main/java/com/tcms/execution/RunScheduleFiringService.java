package com.tcms.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.project.GateResult;
import com.tcms.project.Project;
import com.tcms.project.ProjectService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fires one due {@link RunSchedule} (§247).
 *
 * <h2>Separate from the poller on purpose</h2>
 *
 * <p>{@code CaseHealthScheduler}'s split, and its header carries the reason: the
 * {@code @Transactional} belongs here so each schedule commits on its own. A transaction spanning
 * the loop would have to change {@code app.current_company} inside itself, which is the one thing
 * {@code CompanyAwareTransactionManager} exists to prevent, and one schedule's failure would roll
 * back every schedule fired before it.
 *
 * <h2>Run creation is not reimplemented</h2>
 *
 * <p>{@code RunCreateService#createScheduled} is the only way in — the third door beside
 * {@code create} and {@code createIngested} — so the version pin, the display-key allocation, §6g's
 * approval gate, the checklist and the audit row all stay in the one class that has ever written a
 * run. A copy of that here would be a second implementation of the pin.
 *
 * <h2>Every refusal is RECORDED, never thrown</h2>
 *
 * <p>The owner lost the grant, the project was archived, the resolved scope came back empty, §6g's
 * gate refused — each is a legitimate answer and each is written to {@code last_fire_error}, with
 * {@code next_fire_at} still advanced. <strong>A schedule that stops silently is the failure mode
 * of scheduling anything</strong>, and that column is the only place a reader could find out.
 *
 * <h2>The archived refusal lives here, not in {@code RunCreateService}</h2>
 *
 * <p>Archiving a project blocks no other write in this product — {@code Project#isArchived} is read
 * by the archive toggle and two view projections and nothing else — so <em>"do not fire into an
 * archived project"</em> is a rule about <strong>schedules</strong>, not about run creation.
 * Putting it in {@code RunCreateService} would add a sealed {@code Outcome} arm that
 * {@code RunController}'s exhaustive switch must handle and that the HTTP path can never produce: a
 * dead arm on a shipped endpoint. The cost of keeping it here is one extra permission evaluation
 * per fire, because {@code createScheduled} re-gates inside this lambda — per <em>fire</em>, not
 * per request, and the evaluator memoizes per transaction anyway (§225).
 *
 * <h2>The double-fire guard is the idempotency key that already exists</h2>
 *
 * <p>{@code RunCreateService} refuses a second checklist for a repeated {@code Idempotency-Key},
 * so the key here is {@code schedule:<scheduleId>:<slot>} — deterministic in the schedule and the
 * <strong>slot</strong>, never in {@code now}. Two application instances polling the same tick, or
 * a retry after a partial failure, therefore produce <strong>one</strong> run and a
 * {@code Replayed} for the loser. Nothing new is built for it: a second guard would be a second
 * implementation of the one property whose absence that class's comment calls <em>"a second
 * CHECKLIST"</em>.
 */
@Service
class RunScheduleFiringService {

    /** ~200 characters is {@code RunCreateService}'s cap and this key is far inside it. */
    private static final String IDEMPOTENCY_PREFIX = "schedule:";

    private static final String ARCHIVED =
            "This project is archived, so the schedule did not run. Unarchive the project to "
                    + "start it again.";

    private static final String LOST_GRANT =
            "The schedule's owner no longer has permission to create runs in this project, so it "
                    + "did not run. Re-create the schedule as somebody who does.";

    private static final String LOST_PROJECT =
            "The project is no longer visible to the schedule's owner, so the schedule did not "
                    + "run.";

    private final ProjectService projects;
    private final RunScheduleRepository schedules;
    private final RunScheduleService scheduleService;
    private final RunCreateService runs;
    private final ObjectMapper json;

    RunScheduleFiringService(
            ProjectService projects,
            RunScheduleRepository schedules,
            RunScheduleService scheduleService,
            RunCreateService runs,
            ObjectMapper json) {

        this.projects = projects;
        this.schedules = schedules;
        this.scheduleService = scheduleService;
        this.runs = runs;
        this.json = json;
    }

    /**
     * Fires one schedule, if it is still due.
     *
     * <p>Takes an <strong>id</strong> and re-reads the row inside this transaction. The poller
     * loaded it in its own non-transactional read, and passing that detached entity across the
     * boundary is how a stale {@code nextFireAt} gets written back over a fresher one.
     *
     * <p>{@code now} is the poller's tick, shared by every schedule in the sweep so a company's
     * fires are stamped from one instant — {@code AuditEvent}'s writers all do the same, and it is
     * what makes {@code (occurredAt DESC, id DESC)} group one sweep's rows together.
     */
    @Transactional
    void fire(UUID scheduleId, Instant now) {
        Optional<RunSchedule> found = schedules.findById(scheduleId);
        if (found.isEmpty()) {
            return;
        }
        RunSchedule schedule = found.get();

        // Re-checked inside the transaction, because the poller's read and this write are two
        // statements and two application instances can select the same row between them. The
        // idempotency key below makes a lost race harmless; this makes it rare.
        Instant slot = schedule.getNextFireAt();
        if (!schedule.isEnabled() || slot == null || slot.isAfter(now)) {
            return;
        }

        // Captured BEFORE anything advances it. `slot` is half the idempotency key, so it must be
        // the slot and never `now` — keyed on `now`, two instances one millisecond apart mint two
        // different keys and the run is created twice.
        GateResult<String> gated = projects.writeGranted(
                schedule.getCreatedBy(),
                schedule.getProjectId(),
                RunCreateService.CREATE_RUN,
                project -> attempt(schedule, project, slot, now));

        String failure = switch (gated) {
            case GateResult.Unreachable<String> ignored -> LOST_PROJECT;
            case GateResult.Denied<String> ignored -> LOST_GRANT;
            case GateResult.Allowed<String> allowed -> allowed.value();
        };
        if (failure != null) {
            schedule.recordFireFailure(failure);
        }

        // EVERY branch, including every failure. A schedule that fails once and never advances is
        // re-fired on every tick for ever — a broken schedule becoming a hot loop is the worst
        // outcome available here, and it is the one a naive implementation lands on by putting
        // this inside the success arm.
        scheduleService.recomputeNextFire(schedule, now);
        schedules.save(schedule);
    }

    /**
     * The attempt itself, inside the gate.
     *
     * @return null when a run was created or replayed, otherwise the sentence to record
     */
    private String attempt(RunSchedule schedule, Project project, Instant slot, Instant now) {
        if (project.isArchived()) {
            return ARCHIVED;
        }

        RunScopeRequestView scope =
                RunScopeRequestView.parse(json, schedule.getScopeRequest());
        if (scope == null) {
            // The column is NOT NULL and one writer builds it, so this is unreachable through the
            // product — it is here because "unreadable" must not become a 500 inside a poller that
            // then abandons every schedule after this one in the company.
            return "The schedule's saved scope could not be read, so it did not run.";
        }

        RunCreateService.Outcome outcome = runs.createScheduled(
                schedule.getCreatedBy(),
                project.getId(),
                requestFor(schedule, scope, slot),
                IDEMPOTENCY_PREFIX + schedule.getId() + ":" + slot);

        return switch (outcome) {
            case RunCreateService.Outcome.Created created -> {
                schedule.recordFire(created.view().id(), now);
                yield null;
            }
            // NOT an error. Another instance won this tick, or a retry followed a partial failure:
            // the run exists, it is the right one, and there is exactly one of it. Recording a
            // failure here would put a red sentence on a schedule that worked.
            case RunCreateService.Outcome.Replayed replayed -> {
                schedule.recordFire(replayed.view().id(), now);
                yield null;
            }
            // The common one, and the reason RunScheduleEdit deliberately does not refuse a dead
            // scope arm at write time: a suite deleted since the schedule was written resolves to
            // nothing, and this is where that is a fact about a night rather than about the edit.
            case RunCreateService.Outcome.Invalid invalid -> firstMessage(invalid.errors());
            case RunCreateService.Outcome.Refused refused -> refused.message();
            // Both are unreachable — this lambda already ran inside a gate on the same person and
            // the same project — and are mapped rather than defaulted because the switch is
            // exhaustive and a sixth outcome must not be silently swallowed here.
            case RunCreateService.Outcome.Forbidden ignored -> LOST_GRANT;
            case RunCreateService.Outcome.NotFound ignored -> LOST_PROJECT;
        };
    }

    /**
     * The run this fire asks for.
     *
     * <p>The four scope arms come straight off the stored document, so the run's own
     * {@code scope_request} ends up byte-equivalent to the schedule's — written from the
     * reconstructed request by the same {@link RunScopeDocument}. That is not a coincidence to be
     * tidied away: it is what makes "why is TC-41 in this run" answerable for a run nobody asked
     * for.
     *
     * <p>{@code overrideApprovalGate} is <strong>null</strong>. §6g's whole word is
     * <em>documented</em> — a lead deciding in the moment — and a schedule cannot make that
     * decision in advance for every night to come. A gated project refusing the run is recorded as
     * {@code last_fire_error}, which is a reader seeing the policy work rather than a standing
     * exemption from it.
     */
    private static RunWriteRequest requestFor(
            RunSchedule schedule, RunScopeRequestView scope, Instant slot) {

        return new RunWriteRequest(
                nameFor(schedule, slot),
                scope.planId(),
                schedule.getConfigurationId(),
                null,
                null,
                null,
                null,
                scope.caseIds(),
                scope.suiteIds(),
                scope.tags(),
                schedule.getAssigneeId(),
                null);
    }

    /**
     * {@code "<schedule name> — <date>"}, dated in the schedule's own zone.
     *
     * <p>The schedule's zone and not the server's, for the reason the zone is stored at all: a
     * nightly run at 23:30 in Sofia is dated tomorrow in UTC, so a year of them would carry dates
     * that disagree with the evenings they ran on.
     *
     * <p>Truncated to {@code RunEdit.MAX_NAME}, from the <em>front</em>, so a schedule named right
     * up to the cap still fires. Without it the composed name is longer than a run's name may be
     * and every fire is a validation failure recorded as {@code last_fire_error} — a schedule that
     * saved cleanly and can never run.
     */
    private static String nameFor(RunSchedule schedule, Instant slot) {
        ZoneId zone = CronSchedule.isZone(schedule.getZoneId())
                ? ZoneId.of(schedule.getZoneId())
                : ZoneOffset.UTC;
        String suffix = " — " + LocalDate.ofInstant(slot, zone);
        String prefix = schedule.getName();
        int room = RunEdit.MAX_NAME - suffix.length();
        if (prefix.length() > room) {
            prefix = prefix.substring(0, room);
        }
        return prefix + suffix;
    }

    /** The first field message, which is the one the screen would have shown. */
    private static String firstMessage(Map<String, List<String>> errors) {
        for (List<String> messages : errors.values()) {
            if (!messages.isEmpty()) {
                return messages.getFirst();
            }
        }
        return "The schedule produced no run, and gave no reason.";
    }
}
