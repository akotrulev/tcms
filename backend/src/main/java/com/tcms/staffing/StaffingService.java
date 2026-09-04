package com.tcms.staffing;

import com.tcms.api.UserRef;
import com.tcms.project.GateResult;
import com.tcms.project.PeopleDirectory;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.CompanyContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The company-wide staffing reads — the directory, one person, and the capacity view.
 *
 * <h2>Every read here is COMPANY scope, and one of them is not gated at all</h2>
 *
 * <p>{@code staffing:directory} is a COMPANY-scope key held by Instance Admin, Company Admin,
 * Account Manager and Finance, and <strong>not</strong> by Project Manager, whose staffing keys are
 * the PROJECT-scope pair. A Project Manager's question is <em>who is on this project</em>, and that
 * is {@link AllocationService}'s.
 *
 * <p>Two reads answer without it. {@link #myCapacity} has no staffing permission at all, because it
 * is the caller's own figure (017 D-m), and {@link #person} accepts either the key <em>or</em> the
 * caller being the person: somebody may always read their own staffing, which is why it is not a
 * directory-only read.
 *
 * <h2>Nothing here computes capacity</h2>
 *
 * <p>{@link Capacity} does, through {@link CapacityReader}. What this class does is decide who may
 * ask, fetch the names and the skills, and turn one window into the shape the wire declares — which
 * is the only way "an absence zeroes capacity everywhere" survives a fourth screen being added.
 */
@Service
public class StaffingService {

    private static final String DIRECTORY = "staffing:directory";
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final ProjectService projects;
    private final ProjectRepository projectRows;
    private final PeopleDirectory people;
    private final CapacityReader capacity;
    private final PersonSkillRepository personSkills;
    private final SkillRepository skills;
    private final TimeFacts timeFacts;
    private final Clock clock;

    public StaffingService(
            ProjectService projects,
            ProjectRepository projectRows,
            PeopleDirectory people,
            CapacityReader capacity,
            PersonSkillRepository personSkills,
            SkillRepository skills,
            TimeFacts timeFacts,
            Clock clock) {

        this.projects = projects;
        this.projectRows = projectRows;
        this.people = people;
        this.capacity = capacity;
        this.personSkills = personSkills;
        this.skills = skills;
        this.timeFacts = timeFacts;
        this.clock = clock;
    }

    /**
     * {@code GET /staffing/people} — the skills directory.
     *
     * <p>Every present company member, whether or not they have a profile: {@code directory.html}'s
     * "3 of 11 people match" counts members, and most people have no profile at all. {@code skill}
     * may repeat and is <strong>ANDed</strong> — a person must hold all of them, which is what "who
     * knows Playwright and Java" means, and it is answered by one query rather than an intersection
     * over whole-company lists ({@link PersonSkillRepository#holdingEveryOne}).
     *
     * <p>{@code freeFrom} keeps only people with a day under 100% on or after it, which is the whole
     * of "who is free next month with skill X" — and it is applied to the walk's own answer rather
     * than to a second definition of free.
     */
    @Transactional(readOnly = true)
    public GateResult<List<PersonCapacityView>> directory(
            UUID userId, LocalDate from, LocalDate to, List<UUID> skillIds, LocalDate freeFrom) {

        return projects.readInCompany(userId, DIRECTORY, () -> {
            UUID companyId = CompanyContext.require();
            StaffingWindow window = StaffingWindow.of(from, to, clock);

            List<UserRef> members = people.membersOf(companyId);
            Set<UUID> wanted = matching(companyId, members, skillIds);

            return viewsOf(companyId, members, wanted, window).stream()
                    .filter(person -> freeFrom == null
                            || (person.freeFrom() != null && !person.freeFrom().isBefore(freeFrom)))
                    .toList();
        });
    }

    /**
     * {@code GET /staffing/people/{userId}} — one person's profile, skills, allocations, absences
     * and capacity.
     *
     * <p>{@code staffing:directory}, <strong>or</strong> the caller is this person. The two are not
     * expressible as a gate around the read, so the key is asked as a boolean and combined with the
     * identity check here — {@link ProjectService#holdsInCompany} exists for exactly this shape.
     *
     * <p>A person who is not a present member of this company is {@link GateResult.Unreachable},
     * not {@link GateResult.Denied}: the 404 conceals whether an id names somebody in another
     * client's tenancy, which is the oracle §6t forbids.
     */
    @Transactional(readOnly = true)
    public GateResult<PersonCapacityView> person(
            UUID userId, UUID personId, LocalDate from, LocalDate to) {

        if (!personId.equals(userId) && !projects.holdsInCompany(userId, DIRECTORY)) {
            return new GateResult.Denied<>();
        }
        UUID companyId = CompanyContext.require();
        List<UserRef> members = people.membersOf(companyId).stream()
                .filter(member -> member.id().equals(personId))
                .toList();
        if (members.isEmpty()) {
            return new GateResult.Unreachable<>();
        }
        StaffingWindow window = StaffingWindow.of(from, to, clock);
        return new GateResult.Allowed<>(
                viewsOf(companyId, members, Set.of(personId), window).getFirst());
    }

    /**
     * {@code GET /staffing/capacity} — everybody, plus the over-allocated subset.
     *
     * <p>The subset is served rather than left to the client to re-filter, so the Over-100% panel
     * cannot disagree with the row badge above it: two filters over one list is how one screen comes
     * to say two things.
     */
    @Transactional(readOnly = true)
    public GateResult<StaffingCapacityView> capacity(UUID userId, LocalDate from, LocalDate to) {
        return projects.readInCompany(userId, DIRECTORY, () -> {
            UUID companyId = CompanyContext.require();
            StaffingWindow window = StaffingWindow.of(from, to, clock);
            List<UserRef> members = people.membersOf(companyId);

            List<PersonCapacityView> everybody = viewsOf(
                    companyId,
                    members,
                    members.stream().map(UserRef::id)
                            .collect(Collectors.toCollection(LinkedHashSet::new)),
                    window);

            return new StaffingCapacityView(
                    window.from(),
                    window.to(),
                    everybody,
                    everybody.stream().filter(PersonCapacityView::overAllocated).toList());
        });
    }

    /**
     * {@code GET /staffing/capacity/me} — <strong>no staffing permission at all.</strong>
     *
     * <p>It is the caller's own figure (017 D-m), and the two screens that read it — the timesheet's
     * capacity tile and My Work's this-period meter — belong to people who hold no staffing key of
     * any kind. Both pass the <em>period's</em> bounds and not a week's (016 D-a).
     *
     * <p>{@code hasProfile} is explicit rather than inferred from a null so the tile can say
     * <em>no profile recorded</em> instead of <em>failed to load</em>; the two look identical
     * without it, and only one of them is the reader's to fix.
     */
    @Transactional(readOnly = true)
    public SelfCapacityView myCapacity(UUID userId, LocalDate from, LocalDate to) {
        UUID companyId = CompanyContext.require();
        StaffingWindow window = StaffingWindow.of(from, to, clock);
        CapacityReader.PersonWindow mine = capacity
                .windowsFor(companyId, List.of(userId), window.from(), window.to())
                .get(userId);

        boolean profiled = mine != null && mine.profile() != null;
        return new SelfCapacityView(
                window.from(),
                window.to(),
                profiled ? mine.window().capacityHours().doubleValue() : null,
                profiled ? mine.window().absenceHours().doubleValue() : null,
                mine == null ? 0 : mine.window().allocatedPercent(),
                profiled);
    }

    // =====================================================================

    /**
     * Which members the skill filter keeps.
     *
     * <p>An empty {@code skillIds} keeps everybody, which is the unfiltered directory rather than a
     * special case: "hold all of nothing" is true of every person, and expressing it as the absent
     * filter is what stops the query running with an empty {@code IN ()}.
     */
    private Set<UUID> matching(UUID companyId, List<UserRef> members, List<UUID> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) {
            return members.stream().map(UserRef::id)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        Set<UUID> distinct = new LinkedHashSet<>(skillIds);
        return Set.copyOf(personSkills.holdingEveryOne(companyId, distinct, distinct.size()));
    }

    /**
     * The people {@code wanted}, as the wire declares them — in {@code members} order, which is
     * display-name order.
     *
     * <p>Everything is fetched for the whole page: one query for the windows' three tables, one for
     * the chips, one for the vocabulary behind them, one for approved hours. The alternative is four
     * per row, which is the N+1 a directory of eleven people hides and a directory of two hundred
     * does not.
     */
    private List<PersonCapacityView> viewsOf(
            UUID companyId, List<UserRef> members, Set<UUID> wanted, StaffingWindow window) {

        List<UserRef> shown = members.stream()
                .filter(member -> wanted.contains(member.id()))
                .toList();
        List<UUID> ids = shown.stream().map(UserRef::id).toList();

        Map<UUID, CapacityReader.PersonWindow> windows =
                capacity.windowsFor(companyId, ids, window.from(), window.to());
        Map<UUID, List<SkillView>> chips = chipsOf(companyId, ids);
        Map<UUID, BigDecimal> approved =
                timeFacts.approvedHoursBy(ids, window.from(), window.to());
        Map<UUID, Project> named = projectsById();
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);

        List<PersonCapacityView> views = new ArrayList<>();
        for (UserRef member : shown) {
            views.add(viewOf(
                    member,
                    windows.get(member.id()),
                    chips.getOrDefault(member.id(), List.of()),
                    approved.get(member.id()),
                    named,
                    today));
        }
        return views;
    }

    private PersonCapacityView viewOf(
            UserRef member,
            CapacityReader.PersonWindow held,
            List<SkillView> chips,
            BigDecimal approvedHours,
            Map<UUID, Project> named,
            LocalDate today) {

        Capacity.Window window = held.window();
        boolean profiled = held.profile() != null;

        // Every hours figure is null for somebody with no profile — absent, not zero, and not a
        // default of 40 (016 D-j's refusal, kept). The percentages below are real either way: an
        // unprofiled person can be allocated and over-allocated, and saying so is how anybody
        // notices the profile is missing.
        Double allocatedCapacityHours =
                profiled ? window.allocatedCapacityHours().doubleValue() : null;

        return new PersonCapacityView(
                member,
                profiled ? PersonProfileView.of(held.profile(), today) : null,
                chips,
                profiled ? window.capacityHours().doubleValue() : null,
                profiled ? window.absenceHours().doubleValue() : null,
                window.allocatedPercent(),
                allocatedCapacityHours,
                Math.max(0, 100 - window.allocatedPercent()),
                window.peakPercent(),
                window.peakOn(),
                window.overAllocated(),
                profiled ? approvedOf(approvedHours) : null,
                profiled ? utilisation(approvedHours, window.allocatedCapacityHours()) : null,
                window.freeFrom(),
                held.allocations().stream().map(row -> spanOf(row, named)).toList(),
                held.absences().stream().map(AbsenceView::of).toList());
    }

    private static double approvedOf(BigDecimal approvedHours) {
        return approvedHours == null ? 0d : approvedHours.doubleValue();
    }

    /**
     * {@code approvedHours ÷ allocatedCapacityHours}, as a percentage (017 D-i).
     *
     * <p><strong>Null, not zero, when allocated capacity is zero.</strong> A person nobody has
     * allocated has no utilisation, and 0% would say they are idle when the true statement is that
     * nobody has planned their time — a different sentence, and the one a staffing screen exists to
     * make actionable.
     *
     * <p>APPROVED hours only, never approved plus pending (016 D-d): nothing in this product sums
     * the two, and Finance holds {@code staffing:directory} for precisely the money reading of this
     * number.
     */
    private static Integer utilisation(BigDecimal approvedHours, BigDecimal allocatedCapacity) {
        if (allocatedCapacity == null || allocatedCapacity.signum() == 0) {
            return null;
        }
        BigDecimal worked = approvedHours == null ? BigDecimal.ZERO : approvedHours;
        return worked.multiply(HUNDRED)
                .divide(allocatedCapacity, 0, RoundingMode.HALF_UP)
                .intValue();
    }

    private static AllocationSpanView spanOf(Allocation row, Map<UUID, Project> named) {
        Project project = named.get(row.getProjectId());
        return new AllocationSpanView(
                row.getProjectId(),
                // A project deleted between the allocation being written and this read cascades the
                // row away, so an unnamed project here means the read raced a delete. Saying so is
                // better than an empty cell that reads as a project with no name.
                project == null ? "—" : project.getKey(),
                project == null ? "Removed project" : project.getName(),
                row.getPercent(),
                row.getStartsOn(),
                row.getEndsOn());
    }

    private Map<UUID, List<SkillView>> chipsOf(UUID companyId, Collection<UUID> personIds) {
        if (personIds.isEmpty()) {
            return Map.of();
        }
        List<PersonSkill> held = personSkills.findByCompanyIdAndUserIdIn(companyId, personIds);
        Map<UUID, SkillView> vocabulary = new HashMap<>();
        skills.findByCompanyIdAndIdIn(
                        companyId, held.stream().map(PersonSkill::getSkillId).distinct().toList())
                .forEach(skill -> vocabulary.put(
                        skill.getId(), new SkillView(skill.getId(), skill.getName())));

        Map<UUID, List<SkillView>> byPerson = new LinkedHashMap<>();
        for (PersonSkill chip : held) {
            SkillView named = vocabulary.get(chip.getSkillId());
            if (named != null) {
                byPerson.computeIfAbsent(chip.getUserId(), ignored -> new ArrayList<>()).add(named);
            }
        }
        byPerson.values().forEach(chips -> chips.sort(
                (left, right) -> left.name().compareToIgnoreCase(right.name())));
        return byPerson;
    }

    /**
     * Every project in the company, by id — a <strong>naming</strong> read, never an access one.
     *
     * <p>{@code reporting.ReportHeaders} reaches for {@code ProjectRepository} the same way and for
     * the same reason: the caller has already been gated on a COMPANY-scope key, so the question
     * left is what a project is called, not whether this person may see it. The whole point of D-f
     * is that this breakdown is available <em>here</em> and not on the project screen.
     */
    private Map<UUID, Project> projectsById() {
        Map<UUID, Project> byId = new HashMap<>();
        projectRows.findAllByOrderByNameAsc().forEach(project -> byId.put(project.getId(), project));
        return byId;
    }
}
