package com.tcms.time;

import com.tcms.project.GateResult;
import com.tcms.project.Project;
import com.tcms.project.ProjectService;
import com.tcms.project.ProjectSettings;
import com.tcms.project.ProjectSettingsRepository;
import com.tcms.tenancy.CompanyContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Logging, amending and removing an hour — and the lock the whole feature turns on.
 *
 * <h2>The editability rule</h2>
 *
 * <p>A period that is {@code SUBMITTED} or {@code APPROVED} refuses every write into it with a
 * {@code 409}, and a {@code REJECTED} one accepts them again. That is not a convenience: a timesheet
 * somebody has approved is a record of what was agreed, and a fortnight that could still be edited
 * after approval would make the approval mean nothing.
 *
 * <h2>What a caller may never choose</h2>
 *
 * <p>The <strong>person</strong> — an entry is always logged for the authenticated caller, because
 * {@code time:log} is "log your own hours" and a person parameter would let one credential fill in
 * somebody else's fortnight. The <strong>company</strong> — from the session, never a parameter.
 * And the <strong>period</strong>, which is derived from the date (016 D-a).
 */
@Service
public class TimeEntryService {

    private static final String LOG = "time:log";
    private static final String EDIT = "time:edit";
    private static final BigDecimal MIN_HOURS = new BigDecimal("0.25");
    private static final BigDecimal MAX_HOURS = new BigDecimal("24");

    private final ProjectService projects;
    private final ProjectSettingsRepository settings;
    private final TimeAccess access;
    private final IssueFacts issues;
    private final TimeEntryRepository entries;
    private final TimesheetPeriodRepository periods;
    private final RollupService rollups;
    private final Clock clock;

    public TimeEntryService(
            ProjectService projects,
            ProjectSettingsRepository settings,
            TimeAccess access,
            IssueFacts issues,
            TimeEntryRepository entries,
            TimesheetPeriodRepository periods,
            RollupService rollups,
            Clock clock) {

        this.projects = projects;
        this.settings = settings;
        this.access = access;
        this.issues = issues;
        this.entries = entries;
        this.periods = periods;
        this.rollups = rollups;
        this.clock = clock;
    }

    /** What happened. Sealed, so the controller's mapping over it is exhaustive at compile time. */
    public sealed interface Outcome {

        /** A field the caller can fix — an issue key on a meeting, hours out of range, a moved date. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** The period this date falls in is submitted or approved. The 409. */
        record Locked(TimesheetState state) implements Outcome {
        }

        record Written(TimeEntryView view) implements Outcome {
        }

        record Deleted() implements Outcome {
        }
    }

    /** {@code POST /timesheets/entries}. */
    @Transactional
    public GateResult<Outcome> log(UUID userId, TimeEntryInput input) {
        Optional<Outcome> refused = validate(input);
        if (refused.isPresent()) {
            return new GateResult.Allowed<>(refused.get());
        }
        if (input.subjectType() != TimeSubjectType.ISSUE) {
            if (!access.holdsAnywhere(userId, LOG)) {
                return new GateResult.Denied<>();
            }
            return new GateResult.Allowed<>(write(userId, input, null));
        }
        Optional<IssueFacts.IssueSubject> found = issues.byKey(input.issueKey().strip());
        if (found.isEmpty()) {
            return new GateResult.Unreachable<>();
        }
        IssueFacts.IssueSubject subject = found.get();
        return projects.writeGranted(userId, subject.projectId(), LOG,
                project -> tracksTime(project)
                        ? write(userId, input, subject)
                        : trackingOff(project));
    }

    /**
     * {@code PATCH /timesheets/entries/{entryId}} — a whole-body replacement.
     *
     * <p>{@code time:log} for the caller's own entry, {@code time:edit} for anybody else's. Both are
     * asked against the entry's <em>own</em> project where it has one, and against any project the
     * caller holds the key on where it does not — a meeting belongs to a person, not to a project.
     */
    @Transactional
    public GateResult<Outcome> amend(UUID userId, UUID entryId, TimeEntryInput input) {
        return onEntry(userId, entryId, (entry, period) -> {
            Optional<Outcome> refused = validate(input);
            if (refused.isPresent()) {
                return refused.get();
            }
            if (!Periods.containing(input.spentOn()).equals(
                    new PeriodBounds(period.getStartsOn(), period.getEndsOn()))) {
                // Moving the date out of the period would move the row into a fortnight that may
                // already be submitted, which is the lock above defeated by an edit somewhere else.
                return new Outcome.Invalid(Map.of("spentOn", List.of(
                        "That date is in a different period. Delete this entry and log it again on "
                                + "the day it belongs to — a period is approved whole, so an hour "
                                + "cannot move between two of them.")));
            }
            IssueFacts.IssueSubject subject = null;
            if (input.subjectType() == TimeSubjectType.ISSUE) {
                Optional<IssueFacts.IssueSubject> found = issues.byKey(input.issueKey().strip());
                if (found.isEmpty()) {
                    return new Outcome.Invalid(Map.of("issueKey", List.of(
                            "No issue in this company has the key " + input.issueKey().strip()
                                    + ".")));
                }
                subject = found.get();
                Optional<Outcome> blocked = mayMoveTo(userId, subject.projectId());
                if (blocked.isPresent()) {
                    return blocked.get();
                }
            }
            UUID previous = entry.getSubjectId();
            entry.amend(
                    input.subjectType(),
                    subject == null ? null : subject.issueId(),
                    subject == null ? null : subject.projectId(),
                    input.spentOn(),
                    hours(input),
                    trimmed(input.note()),
                    userId,
                    clock.instant());
            TimeEntry saved = entries.save(entry);
            rollups.restate(touched(previous, entry.getSubjectId()));
            return new Outcome.Written(viewOf(saved, subject, period.getState()));
        });
    }

    /** {@code DELETE /timesheets/entries/{entryId}} — a hard delete; the rollup follows. */
    @Transactional
    public GateResult<Outcome> delete(UUID userId, UUID entryId) {
        return onEntry(userId, entryId, (entry, period) -> {
            UUID subjectId = entry.getSubjectId();
            entries.delete(entry);
            entries.flush();
            rollups.restate(touched(subjectId, null));
            return new Outcome.Deleted();
        });
    }

    /**
     * The entries of one period, rendered.
     *
     * <p>Package-private: {@code TimesheetService} composes a period out of this, and the gate that
     * let it through is the period's rather than each row's.
     */
    List<TimeEntryView> viewsOf(TimesheetPeriod period) {
        List<TimeEntry> rows = entries.findByPeriodIdOrderBySpentOnDesc(period.getId());
        Map<UUID, IssueFacts.IssueSubject> subjects = subjectsOf(rows);
        return rows.stream()
                .map(row -> viewOf(row, subjects.get(row.getSubjectId()), period.getState()))
                .toList();
    }

    /**
     * The subjects behind a set of rows, in one query.
     *
     * <p>A {@code HashMap} rather than {@code Map.of}'s, because every non-issue row looks up a null
     * subject id and {@code Map.of().get(null)} throws by contract.
     */
    Map<UUID, IssueFacts.IssueSubject> subjectsOf(List<TimeEntry> rows) {
        List<UUID> issueIds = rows.stream()
                .map(TimeEntry::getSubjectId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<UUID, IssueFacts.IssueSubject> byId = new HashMap<>();
        issues.byIds(issueIds).forEach(subject -> byId.put(subject.issueId(), subject));
        return byId;
    }

    // =====================================================================

    private GateResult<Outcome> onEntry(
            UUID userId, UUID entryId, BiFunction<TimeEntry, TimesheetPeriod, Outcome> write) {

        Optional<TimeEntry> found = entries.findScopedById(entryId);
        if (found.isEmpty()) {
            return new GateResult.Unreachable<>();
        }
        TimeEntry entry = found.get();
        String needed = entry.getPersonId().equals(userId) ? LOG : EDIT;
        TimesheetPeriod period = periods.findScopedById(entry.getPeriodId()).orElseThrow();

        if (entry.getProjectId() != null) {
            return projects.writeGranted(userId, entry.getProjectId(), needed, project ->
                    period.getState().editable()
                            ? write.apply(entry, period)
                            : new Outcome.Locked(period.getState()));
        }
        if (!access.holdsAnywhere(userId, needed)) {
            return new GateResult.Denied<>();
        }
        return new GateResult.Allowed<>(period.getState().editable()
                ? write.apply(entry, period)
                : new Outcome.Locked(period.getState()));
    }

    private Outcome write(UUID userId, TimeEntryInput input, IssueFacts.IssueSubject subject) {
        TimesheetPeriod period = periodFor(userId, input.spentOn());
        if (!period.getState().editable()) {
            return new Outcome.Locked(period.getState());
        }
        TimeEntry saved = entries.save(TimeEntry.log(
                UUID.randomUUID(),
                CompanyContext.require(),
                period.getId(),
                userId,
                input.subjectType(),
                subject == null ? null : subject.issueId(),
                subject == null ? null : subject.projectId(),
                input.spentOn(),
                hours(input),
                trimmed(input.note()),
                userId,
                clock.instant()));
        rollups.restate(touched(saved.getSubjectId(), null));
        return new Outcome.Written(viewOf(saved, subject, period.getState()));
    }

    /**
     * The caller's period for a date, created if this is the first write into it (016 D-a).
     *
     * <p>Package-private because {@code TimesheetService} opens the same row when a person first
     * looks at the fortnight, and two spellings of "which period is this date in" is how two clients
     * end up disagreeing about where a period starts.
     */
    TimesheetPeriod periodFor(UUID personId, LocalDate on) {
        PeriodBounds bounds = Periods.containing(on);
        return periods.findByPersonIdAndStartsOn(personId, bounds.startsOn())
                .orElseGet(() -> periods.save(TimesheetPeriod.open(
                        UUID.randomUUID(),
                        CompanyContext.require(),
                        personId,
                        bounds,
                        clock.instant())));
    }

    private Optional<Outcome> mayMoveTo(UUID userId, UUID projectId) {
        if (projects.holds(userId, projectId, LOG)) {
            return Optional.empty();
        }
        return Optional.of(new Outcome.Invalid(Map.of("issueKey", List.of(
                "You cannot log time against that project. An amend can move an entry between "
                        + "issues, but not onto a project you have no time:log grant on."))));
    }

    private boolean tracksTime(Project project) {
        return settings.findForProject(project.getId())
                .map(ProjectSettings::tracksTime)
                .orElse(true);
    }

    private static Outcome trackingOff(Project project) {
        return new Outcome.Invalid(Map.of("issueKey", List.of(
                "Time tracking is off on " + project.getName() + ". Turn it on in the project's "
                        + "settings before logging hours against its issues — nothing already "
                        + "recorded was deleted when it was turned off.")));
    }

    private Optional<Outcome> validate(TimeEntryInput input) {
        if (input == null || input.subjectType() == null) {
            return Optional.of(new Outcome.Invalid(Map.of("subjectType", List.of(
                    "Say what these hours were spent on — an issue, a meeting, leave, training or "
                            + "internal work."))));
        }
        if (input.spentOn() == null) {
            return Optional.of(new Outcome.Invalid(Map.of("spentOn", List.of(
                    "An entry needs the day it was worked. The period it belongs to is derived "
                            + "from it."))));
        }
        boolean onIssue = input.subjectType() == TimeSubjectType.ISSUE;
        boolean keyed = input.issueKey() != null && !input.issueKey().isBlank();
        if (onIssue && !keyed) {
            return Optional.of(new Outcome.Invalid(Map.of("issueKey", List.of(
                    "An entry logged against an issue needs that issue's key."))));
        }
        if (!onIssue && keyed) {
            return Optional.of(new Outcome.Invalid(Map.of("issueKey", List.of(
                    "Only an ISSUE entry names an issue. A meeting, leave, training and internal "
                            + "work belong to the person and to no issue at all."))));
        }
        if (input.hours() == null) {
            return Optional.of(new Outcome.Invalid(Map.of("hours", List.of(
                    "An entry needs a number of hours."))));
        }
        BigDecimal hours = hours(input);
        if (hours.compareTo(MIN_HOURS) < 0 || hours.compareTo(MAX_HOURS) > 0) {
            return Optional.of(new Outcome.Invalid(Map.of("hours", List.of(
                    "Between 0.25 and 24 hours. A quarter of an hour is the smallest unit this "
                            + "product records, and a day is the largest."))));
        }
        return Optional.empty();
    }

    /**
     * The submitted hours at the column's scale.
     *
     * <p>{@code HALF_UP} at two places, matching {@code NUMERIC(5,2)}: a client that sends
     * {@code 1.005} gets {@code 1.01} rather than a Postgres rounding nobody chose, and the value
     * that comes back is the value that was stored.
     */
    private static BigDecimal hours(TimeEntryInput input) {
        return BigDecimal.valueOf(input.hours()).setScale(2, RoundingMode.HALF_UP);
    }

    private static String trimmed(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        return note.strip();
    }

    private static List<UUID> touched(UUID before, UUID after) {
        return Stream.of(before, after)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private static TimeEntryView viewOf(
            TimeEntry entry, IssueFacts.IssueSubject subject, TimesheetState state) {

        return new TimeEntryView(
                entry.getId(),
                entry.getPeriodId(),
                entry.getSubjectType(),
                subject == null ? null : new TimeEntryView.SubjectView(
                        subject.issueId(),
                        subject.issueKey(),
                        subject.title(),
                        subject.typeLevel(),
                        subject.projectId(),
                        subject.projectName()),
                entry.getSpentOn(),
                entry.getHours().doubleValue(),
                entry.getNote(),
                state.editable());
    }
}
