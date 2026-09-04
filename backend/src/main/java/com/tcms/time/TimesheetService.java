package com.tcms.time;

import com.tcms.api.UserRef;
import com.tcms.project.GateResult;
import com.tcms.project.PeopleDirectory;
import com.tcms.project.Project;
import com.tcms.tenancy.CompanyContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A person's fortnight, and the queue somebody approves it from.
 *
 * <h2>Who may approve a period (016 D-i), which is the part a reader will get wrong</h2>
 *
 * <p>A period is <strong>per person and spans projects</strong> — one submission carries two clients'
 * projects and a project-less meeting — while {@code time:approve} is a <strong>PROJECT-scope</strong>
 * grant. So a period reaches the queue when at least one of its entries names a project the caller
 * may approve for, and <strong>approving it approves the whole period, including entries against
 * projects they do not hold</strong>. That is the sharp edge, stated rather than hidden: the
 * alternative — per-project partial approval — is a state the screen has no way to render (its
 * Approve button is one button per person) and would make "a period is locked" untrue for half a
 * timesheet. A period whose entries name no project at all is approvable by anybody holding
 * {@code time:approve} somewhere, because there is nothing else it could be keyed on. Company Admin
 * holds the key company-wide and is unaffected by any of it.
 *
 * <p><strong>Test Lead cannot approve one</strong> (016 D-f). V2's own comment above that role's
 * grant flags it for this module by name: a project staffed with a Test Lead and no Project Manager
 * can only have its timesheets approved by Company Admin, which is a consequence of D3 that was
 * decided and recorded. {@code TimeMoneyBoundaryTest} asserts the boundary rather than repairing it —
 * widening the grant here would widen a grant nobody decided to widen.
 */
@Service
public class TimesheetService {

    private static final String LOG = "time:log";
    private static final String READ = "time:read";
    private static final String APPROVE = "time:approve";
    private static final int MAX_PERIODS = 24;

    private final TimeAccess access;
    private final PeopleDirectory people;
    private final TimeEntryService entries;
    private final TimeEntryRepository rows;
    private final TimesheetPeriodRepository periods;
    private final RollupService rollups;
    private final Clock clock;

    public TimesheetService(
            TimeAccess access,
            PeopleDirectory people,
            TimeEntryService entries,
            TimeEntryRepository rows,
            TimesheetPeriodRepository periods,
            RollupService rollups,
            Clock clock) {

        this.access = access;
        this.people = people;
        this.entries = entries;
        this.rows = rows;
        this.periods = periods;
        this.rollups = rollups;
        this.clock = clock;
    }

    /** What a decision did. Sealed, so the controller's mapping over it is exhaustive. */
    public sealed interface Outcome {

        /** A field the caller can fix — an unknown decision, or a REJECT with no note. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** The period is not in a state this act can reach from. The 409. */
        record NotInThatState(TimesheetState state) implements Outcome {
        }

        record Decided(TimesheetPeriodView view) implements Outcome {
        }
    }

    /**
     * {@code GET /timesheets/periods/current} — always the caller's own.
     *
     * <p>There is no person parameter, and that is a decision rather than an omission: one would let
     * a credential holding the permission every role holds read somebody else's timesheet. Reading
     * another person's fortnight is the approval queue, and it costs {@code time:approve}.
     *
     * <p><strong>A read that writes a row</strong>, which is the contract's own "created lazily if it
     * has no rows yet". The alternative is answering with bounds and no id, and the id is what the
     * Submit button posts to — so a caller who had logged nothing would have to log something before
     * the screen could function.
     */
    @Transactional
    public GateResult<TimesheetPeriodView> current(UUID userId, LocalDate on) {
        if (!access.holdsAnywhere(userId, LOG)) {
            return new GateResult.Denied<>();
        }
        LocalDate date = on == null ? today() : on;
        return new GateResult.Allowed<>(viewOf(entries.periodFor(userId, date)));
    }

    /** {@code GET /timesheets/periods} — the caller's own, newest first. */
    @Transactional(readOnly = true)
    public GateResult<List<TimesheetPeriodView>> list(UUID userId, Integer limit) {
        if (!access.holdsAnywhere(userId, LOG)) {
            return new GateResult.Denied<>();
        }
        int wanted = limit == null ? 6 : Math.clamp(limit, 1, MAX_PERIODS);
        return new GateResult.Allowed<>(periods.findByPersonIdOrderByStartsOnDesc(userId).stream()
                .limit(wanted)
                .map(this::viewOf)
                .toList());
    }

    /**
     * {@code GET /timesheets/periods/{periodId}}.
     *
     * <p>{@code time:log} for the caller's own, {@code time:read} for anybody else's — and the second
     * is a different key precisely because seeing what a colleague worked on is not the same act as
     * recording what you worked on.
     */
    @Transactional(readOnly = true)
    public GateResult<TimesheetPeriodView> find(UUID userId, UUID periodId) {
        Optional<TimesheetPeriod> found = periods.findScopedById(periodId);
        if (found.isEmpty()) {
            return new GateResult.Unreachable<>();
        }
        TimesheetPeriod period = found.get();
        String needed = period.getPersonId().equals(userId) ? LOG : READ;
        if (!access.holdsAnywhere(userId, needed)) {
            return new GateResult.Denied<>();
        }
        return new GateResult.Allowed<>(viewOf(period));
    }

    /**
     * {@code POST /timesheets/periods/{periodId}/submit} — the caller's own period only.
     *
     * <p>Submitting somebody else's timesheet is not an act this product has, and it is not a fifth
     * permission key (016 D-e): it is the same act as logging, one step later.
     */
    @Transactional
    public GateResult<Outcome> submit(UUID userId, UUID periodId) {
        Optional<TimesheetPeriod> found = periods.findScopedById(periodId);
        if (found.isEmpty()) {
            return new GateResult.Unreachable<>();
        }
        TimesheetPeriod period = found.get();
        if (!period.getPersonId().equals(userId) || !access.holdsAnywhere(userId, LOG)) {
            return new GateResult.Denied<>();
        }
        if (!period.getState().editable()) {
            return new GateResult.Allowed<>(new Outcome.NotInThatState(period.getState()));
        }
        period.submit(clock.instant());
        return new GateResult.Allowed<>(new Outcome.Decided(viewOf(periods.save(period))));
    }

    /**
     * {@code GET /timesheets/approvals} — one entry per person for one fortnight.
     *
     * <p>Not only the submitted ones: the mockup shows an OPEN period as "Not submitted · 41.0h so
     * far" with a Remind action beside it, so the state is served and the client decides what may be
     * done with it.
     */
    @Transactional(readOnly = true)
    public GateResult<List<TimesheetQueueEntryView>> queue(UUID userId, LocalDate on) {
        List<Project> approvable = access.granting(userId, APPROVE);
        if (approvable.isEmpty()) {
            return new GateResult.Denied<>();
        }
        Set<UUID> mine = approvable.stream().map(Project::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, String> names = new HashMap<>();
        approvable.forEach(project -> names.put(project.getId(), project.getName()));

        LocalDate date = on == null ? today() : on;
        List<TimesheetPeriod> fortnight =
                periods.findByStartsOn(Periods.containing(date).startsOn());
        if (fortnight.isEmpty()) {
            return new GateResult.Allowed<>(List.of());
        }
        Map<UUID, List<TimeEntry>> byPeriod = new LinkedHashMap<>();
        rows.findByPeriodIdIn(fortnight.stream().map(TimesheetPeriod::getId).toList())
                .forEach(row -> byPeriod
                        .computeIfAbsent(row.getPeriodId(), ignored -> new ArrayList<>())
                        .add(row));

        List<TimesheetQueueEntryView> queue = new ArrayList<>();
        for (TimesheetPeriod period : fortnight) {
            List<TimeEntry> entriesOf = byPeriod.getOrDefault(period.getId(), List.of());
            if (!approvableBy(entriesOf, mine)) {
                continue;
            }
            queue.add(new TimesheetQueueEntryView(viewOf(period), totalsOf(entriesOf, names)));
        }
        return new GateResult.Allowed<>(queue);
    }

    /** {@code POST /timesheets/periods/{periodId}/decision}. */
    @Transactional
    public GateResult<Outcome> decide(UUID userId, UUID periodId, TimesheetDecisionInput input) {
        Optional<TimesheetPeriod> found = periods.findScopedById(periodId);
        if (found.isEmpty()) {
            return new GateResult.Unreachable<>();
        }
        TimesheetPeriod period = found.get();
        List<TimeEntry> entriesOf = rows.findByPeriodIdOrderBySpentOnDesc(period.getId());
        Set<UUID> mine = access.granting(userId, APPROVE).stream().map(Project::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (mine.isEmpty() || !approvableBy(entriesOf, mine)) {
            return new GateResult.Denied<>();
        }
        return new GateResult.Allowed<>(apply(userId, period, entriesOf, input));
    }

    // =====================================================================

    private Outcome apply(
            UUID userId, TimesheetPeriod period, List<TimeEntry> entriesOf,
            TimesheetDecisionInput input) {

        String decision = input == null || input.decision() == null
                ? "" : input.decision().strip().toUpperCase(Locale.ROOT);
        Instant now = clock.instant();

        switch (decision) {
            case "APPROVE" -> {
                if (period.getState() != TimesheetState.SUBMITTED) {
                    return new Outcome.NotInThatState(period.getState());
                }
                period.approve(userId, now);
            }
            case "REJECT" -> {
                if (input.note() == null || input.note().isBlank()) {
                    return new Outcome.Invalid(Map.of("note", List.of(
                            "Say what has to change. A rejection without a note is a period that "
                                    + "came back with no way to know why, and the note is what the "
                                    + "person sees at the top of the unlocked fortnight.")));
                }
                if (period.getState() != TimesheetState.SUBMITTED) {
                    return new Outcome.NotInThatState(period.getState());
                }
                period.reject(userId, input.note().strip(), now);
            }
            case "WITHDRAW_REJECTION" -> {
                if (period.getState() != TimesheetState.REJECTED) {
                    return new Outcome.NotInThatState(period.getState());
                }
                period.withdrawRejection(now);
            }
            default -> {
                return new Outcome.Invalid(Map.of("decision", List.of(
                        "APPROVE, REJECT or WITHDRAW_REJECTION.")));
            }
        }
        TimesheetPeriod saved = periods.save(period);
        // Synchronously, inside this transaction (016 D-h). Every issue the period names moves
        // between approved and pending, and the story panel has to be right the moment this returns.
        rollups.restate(entriesOf.stream()
                .map(TimeEntry::getSubjectId)
                .filter(Objects::nonNull)
                .distinct()
                .toList());
        return new Outcome.Decided(viewOf(saved));
    }

    /**
     * D-i's rule, in one place.
     *
     * <p>A period naming no project at all is approvable by anybody holding the key somewhere: there
     * is nothing else it could be keyed on, and a fortnight of leave that no one could ever approve
     * would be a person permanently unable to close their timesheet.
     */
    private static boolean approvableBy(List<TimeEntry> entriesOf, Set<UUID> approvable) {
        List<UUID> named = entriesOf.stream()
                .map(TimeEntry::getProjectId)
                .filter(Objects::nonNull)
                .toList();
        return named.isEmpty() || named.stream().anyMatch(approvable::contains);
    }

    /**
     * Hours per project, for the projects the caller can see.
     *
     * <p>These deliberately do not add up to the period's {@code loggedHours}, and the contract says
     * so from the other side: a project-less total omits its row rather than rendering a dash, and an
     * entry against a project this approver cannot see is one of D-i's — approvable, and not theirs
     * to be told the name of.
     */
    private static List<TimesheetQueueEntryView.ProjectTotalView> totalsOf(
            List<TimeEntry> entriesOf, Map<UUID, String> names) {

        Map<UUID, BigDecimal> summed = new LinkedHashMap<>();
        for (TimeEntry entry : entriesOf) {
            if (entry.getProjectId() == null || !names.containsKey(entry.getProjectId())) {
                continue;
            }
            summed.merge(entry.getProjectId(), entry.getHours(), BigDecimal::add);
        }
        return summed.entrySet().stream()
                .map(total -> new TimesheetQueueEntryView.ProjectTotalView(
                        names.get(total.getKey()), total.getValue().doubleValue()))
                .toList();
    }

    private TimesheetPeriodView viewOf(TimesheetPeriod period) {
        List<TimeEntryView> shown = entries.viewsOf(period);
        double logged = shown.stream().mapToDouble(TimeEntryView::hours).sum();
        Map<UUID, UserRef> resolved = people.resolve(
                CompanyContext.require(), namesWanted(period));
        UserRef person = resolved.get(period.getPersonId());
        UserRef decider = resolved.get(period.getDecidedBy());

        return new TimesheetPeriodView(
                period.getId(),
                period.getPersonId(),
                person == null ? null : person.displayName(),
                period.getStartsOn(),
                period.getEndsOn(),
                period.getState(),
                logged,
                // Zero unless APPROVED, and carried apart from loggedHours (016 D-d). The two are
                // never summed: one screen says "approved hours only" and another counts hours
                // nobody has approved, so a single number could not say both.
                period.getState() == TimesheetState.APPROVED ? logged : 0d,
                period.getSubmittedAt(),
                period.getDecidedAt(),
                decider == null ? null : decider.displayName(),
                period.getDecisionNote(),
                period.getState().editable(),
                shown);
    }

    private static List<UUID> namesWanted(TimesheetPeriod period) {
        List<UUID> wanted = new ArrayList<>();
        wanted.add(period.getPersonId());
        if (period.getDecidedBy() != null) {
            wanted.add(period.getDecidedBy());
        }
        return wanted;
    }

    /** The instance's day, from the one {@code Clock} bean rather than the wall clock. */
    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
