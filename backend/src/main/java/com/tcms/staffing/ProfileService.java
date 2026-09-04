package com.tcms.staffing;

import com.tcms.api.UserRef;
import com.tcms.project.GateResult;
import com.tcms.project.PeopleDirectory;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.CompanyContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Profiles, the skill vocabulary and absences — the company-scope writes, and the two reads that go
 * with them.
 *
 * <h2>Recording your own absence needs no key (017 D-h)</h2>
 *
 * <p>Straight off the catalogue text, which is precise about it: {@code staffing:absence} covers
 * <em>"record absence on somebody else's behalf"</em>. Telling the product you are away on Thursday
 * is not an administrative act, and a product that made it one would be a product where nobody
 * records absence — which would make every capacity figure in this module a lie by omission.
 * Reading somebody else's absences is {@code staffing:directory}; reading your own is always
 * allowed.
 *
 * <h2>The two creates here carry no {@code Idempotency-Key}, and that is the parameter's scope</h2>
 *
 * <p>It deduplicates within {@code (company, project, key)}, and neither a skill nor an absence is
 * addressed to a project (017 T1 correction 5). What a lost response costs is bounded and stated
 * rather than hidden: a repeated skill <em>name</em> answers {@code 409} instead of creating a
 * second row, and a duplicate absence over the same days double-counts nothing, because
 * {@link Capacity} computes per day and caps the day at one whole one. Inventing a company-scope
 * idempotency scope to close the gap would be a second dedupe rule beside the one the contract
 * declares.
 *
 * <h2>Why only one of these five uses {@link ProjectService#createGranted}</h2>
 *
 * <p>That gate has two outcomes because a create names no target. Four of the operations here
 * <em>do</em> name one — a person — and the contract declares a {@code 404} on each, so they ask
 * {@link ProjectService#holdsInCompany} for the key and answer {@link GateResult.Unreachable}
 * themselves when the person is not a present member of this company. {@code POST /staffing/skills}
 * is the one that names nothing, and it is the one that uses the gate.
 */
@Service
public class ProfileService {

    private static final String PROFILE = "staffing:profile";
    private static final String DIRECTORY = "staffing:directory";
    private static final String ABSENCE = "staffing:absence";

    private static final BigDecimal MAX_WEEKLY_HOURS = new BigDecimal("80");
    private static final BigDecimal MIN_DAY_FRACTION = new BigDecimal("0.25");
    private static final int MAX_SKILL_NAME = 80;

    private final ProjectService projects;
    private final PeopleDirectory people;
    private final PersonProfileRepository profiles;
    private final SkillRepository skills;
    private final PersonSkillRepository personSkills;
    private final AbsenceRepository absences;
    private final Clock clock;

    public ProfileService(
            ProjectService projects,
            PeopleDirectory people,
            PersonProfileRepository profiles,
            SkillRepository skills,
            PersonSkillRepository personSkills,
            AbsenceRepository absences,
            Clock clock) {

        this.projects = projects;
        this.people = people;
        this.profiles = profiles;
        this.skills = skills;
        this.personSkills = personSkills;
        this.absences = absences;
        this.clock = clock;
    }

    /**
     * What one of these writes did. Sealed and generic, so the controller has one mapping for five
     * operations and the exhaustive {@code switch} makes a new outcome a compile error rather than a
     * silent 200 with the wrong body.
     *
     * @param <T> what the write produced, carried by {@link Written}
     */
    public sealed interface Outcome<T> {

        /** A field the caller can fix. */
        record Invalid<T>(Map<String, List<String>> errors) implements Outcome<T> {
        }

        /** The 409 — today only a skill name this company already has. */
        record Conflict<T>(String title, String detail) implements Outcome<T> {
        }

        record Written<T>(T value) implements Outcome<T> {
        }

        record Removed<T>() implements Outcome<T> {
        }
    }

    /** {@code PUT /staffing/people/{userId}/profile} — create or replace. */
    @Transactional
    public GateResult<Outcome<PersonProfileView>> putProfile(
            UUID userId, UUID personId, PersonProfileInput submitted) {

        return onPerson(userId, PROFILE, personId, () -> writeProfile(personId, submitted));
    }

    /** {@code PUT /staffing/people/{userId}/skills} — the whole set, replacing what is there. */
    @Transactional
    public GateResult<Outcome<List<SkillView>>> putSkills(
            UUID userId, UUID personId, PersonSkillsInput submitted) {

        return onPerson(userId, PROFILE, personId, () -> writeSkills(personId, submitted));
    }

    /** {@code GET /staffing/skills} — the company's vocabulary, in name order. */
    @Transactional(readOnly = true)
    public GateResult<List<SkillView>> listSkills(UUID userId) {
        return projects.readInCompany(userId, DIRECTORY, () ->
                skills.findByCompanyIdOrderByNameAsc(CompanyContext.require()).stream()
                        .map(skill -> new SkillView(skill.getId(), skill.getName()))
                        .toList());
    }

    /**
     * {@code POST /staffing/skills} — find-or-create against a {@code citext} name.
     *
     * <p>A repeated name is a {@code 409} rather than a second row, and the lookup that decides it
     * has to cast ({@link SkillRepository#findByName}): Hibernate binds a {@code String} as
     * {@code varchar}, so an uncast comparison against {@code citext} is case-<em>sensitive</em>
     * while {@code uq_skill_name} is not — the two would disagree, and the visible symptom is a 409
     * for a skill the directory cannot show.
     */
    @Transactional
    public GateResult<Outcome<SkillView>> createSkill(UUID userId, SkillInput submitted) {
        return projects.createGranted(userId, PROFILE, () -> writeSkill(submitted));
    }

    /**
     * {@code GET /staffing/people/{userId}/absences} — {@code staffing:directory} for anybody
     * else's, and always allowed for the caller's own.
     */
    @Transactional(readOnly = true)
    public GateResult<List<AbsenceView>> listAbsences(
            UUID userId, UUID personId, LocalDate from, LocalDate to) {

        if (!personId.equals(userId) && !projects.holdsInCompany(userId, DIRECTORY)) {
            return new GateResult.Denied<>();
        }
        UUID companyId = CompanyContext.require();
        if (!isMember(companyId, personId)) {
            return new GateResult.Unreachable<>();
        }
        StaffingWindow window = StaffingWindow.of(from, to, clock);
        return new GateResult.Allowed<>(absences
                .findByCompanyIdAndPersonIdAndStartsOnLessThanEqualAndEndsOnGreaterThanEqualOrderByStartsOnAsc(
                        companyId, personId, window.to(), window.from())
                .stream()
                .map(AbsenceView::of)
                .toList());
    }

    /** {@code POST /staffing/people/{userId}/absences} — {@code staffing:absence}, or your own. */
    @Transactional
    public GateResult<Outcome<AbsenceView>> createAbsence(
            UUID userId, UUID personId, AbsenceInput submitted) {

        if (!mayActOnBehalfOf(userId, personId)) {
            return new GateResult.Denied<>();
        }
        UUID companyId = CompanyContext.require();
        if (!isMember(companyId, personId)) {
            return new GateResult.Unreachable<>();
        }
        Map<String, List<String>> errors = problemsWith(submitted);
        if (!errors.isEmpty()) {
            return new GateResult.Allowed<>(new Outcome.Invalid<>(errors));
        }
        Absence recorded = absences.save(Absence.recorded(
                UUID.randomUUID(),
                companyId,
                personId,
                submitted.startsOn(),
                submitted.endsOn(),
                submitted.kind(),
                submitted.dayFraction() == null
                        ? BigDecimal.ONE
                        : BigDecimal.valueOf(submitted.dayFraction()),
                submitted.note(),
                userId,
                clock.instant()));
        return new GateResult.Allowed<>(new Outcome.Written<>(AbsenceView.of(recorded)));
    }

    /**
     * {@code DELETE /staffing/people/{userId}/absences/{absenceId}} — the same rule, from the other
     * end.
     *
     * <p>The row is looked up by {@code (company, person, id)} rather than by id alone, so an
     * absence id belonging to somebody else answers 404 on this path instead of being removed from
     * under them by a caller who holds the key.
     */
    @Transactional
    public GateResult<Outcome<AbsenceView>> deleteAbsence(
            UUID userId, UUID personId, UUID absenceId) {

        if (!mayActOnBehalfOf(userId, personId)) {
            return new GateResult.Denied<>();
        }
        Optional<Absence> found = absences.findByCompanyIdAndPersonIdAndId(
                CompanyContext.require(), personId, absenceId);
        if (found.isEmpty()) {
            return new GateResult.Unreachable<>();
        }
        absences.delete(found.get());
        return new GateResult.Allowed<>(new Outcome.Removed<>());
    }

    // =====================================================================

    /**
     * A COMPANY-scope key, then a target that may not exist — the shape {@code createGranted} has no
     * arm for and {@code writeGranted} answers only about a project.
     */
    private <T> GateResult<Outcome<T>> onPerson(
            UUID userId, String permission, UUID personId, Supplier<Outcome<T>> write) {

        if (!projects.holdsInCompany(userId, permission)) {
            return new GateResult.Denied<>();
        }
        if (!isMember(CompanyContext.require(), personId)) {
            return new GateResult.Unreachable<>();
        }
        return new GateResult.Allowed<>(write.get());
    }

    /**
     * The key covers acting for somebody else; acting for yourself never needs one (017 D-h).
     *
     * <p>Written as one predicate rather than repeated at the two call sites, because they are the
     * same rule seen from each end and the cost of letting them drift is that one of the pair
     * becomes reachable and the other does not.
     */
    private boolean mayActOnBehalfOf(UUID userId, UUID personId) {
        return personId.equals(userId) || projects.holdsInCompany(userId, ABSENCE);
    }

    private boolean isMember(UUID companyId, UUID personId) {
        return people.membersOf(companyId).stream()
                .map(UserRef::id)
                .anyMatch(personId::equals);
    }

    private Outcome<PersonProfileView> writeProfile(UUID personId, PersonProfileInput submitted) {
        Map<String, List<String>> errors = problemsWith(submitted);
        if (!errors.isEmpty()) {
            return new Outcome.Invalid<>(errors);
        }
        UUID companyId = CompanyContext.require();
        Instant now = clock.instant();
        BigDecimal hours = BigDecimal.valueOf(submitted.weeklyHours());

        PersonProfile stored = profiles.findByCompanyIdAndUserId(companyId, personId)
                .map(existing -> {
                    existing.replace(hours, submitted.jobTitle(), submitted.location(),
                            submitted.experienceSince(), now);
                    return existing;
                })
                .orElseGet(() -> PersonProfile.create(
                        UUID.randomUUID(), companyId, personId, hours, submitted.jobTitle(),
                        submitted.location(), submitted.experienceSince(), now));

        return new Outcome.Written<>(PersonProfileView.of(
                profiles.save(stored), LocalDate.ofInstant(now, ZoneOffset.UTC)));
    }

    private Outcome<List<SkillView>> writeSkills(UUID personId, PersonSkillsInput submitted) {
        if (submitted == null || submitted.skillIds() == null) {
            return new Outcome.Invalid<>(Map.of("skillIds", List.of(
                    "Send the whole set. An absent list is not an empty one — this operation "
                            + "replaces what is there, so the two would mean different things.")));
        }
        UUID companyId = CompanyContext.require();
        Set<UUID> wanted = new LinkedHashSet<>(submitted.skillIds());
        List<Skill> known = wanted.isEmpty()
                ? List.of()
                : skills.findByCompanyIdAndIdIn(companyId, wanted);
        if (known.size() != wanted.size()) {
            return new Outcome.Invalid<>(Map.of("skillIds", List.of(
                    "One of those skills is not in this company's vocabulary. Add it first — a "
                            + "skill is a word a client chose, so nothing here invents one.")));
        }

        // Delete-and-insert rather than a reconcile: a person_skill row holds nothing beyond its
        // key and created_at, so there is nothing a reconcile would have preserved.
        personSkills.deleteEveryChipOf(companyId, personId);
        Instant now = clock.instant();
        List<PersonSkill> held = new ArrayList<>();
        for (UUID skillId : wanted) {
            held.add(PersonSkill.held(companyId, personId, skillId, now));
        }
        personSkills.saveAll(held);

        return new Outcome.Written<>(known.stream()
                .map(skill -> new SkillView(skill.getId(), skill.getName()))
                .sorted((left, right) -> left.name().compareToIgnoreCase(right.name()))
                .toList());
    }

    private Outcome<SkillView> writeSkill(SkillInput submitted) {
        String name = submitted == null || submitted.name() == null ? "" : submitted.name().strip();
        if (name.isEmpty() || name.length() > MAX_SKILL_NAME) {
            return new Outcome.Invalid<>(Map.of("name", List.of(
                    "A skill needs a name of at most " + MAX_SKILL_NAME + " characters.")));
        }
        if (skills.findByName(name).isPresent()) {
            return new Outcome.Conflict<>("That skill is already here",
                    "This company's vocabulary already has \"" + name + "\". Names are matched "
                            + "case-insensitively, so \"Playwright\" and \"playwright\" are one "
                            + "skill — a directory whose filter missed half the people who have "
                            + "the skill would be worse than no filter.");
        }
        Skill created = skills.save(Skill.create(
                UUID.randomUUID(), CompanyContext.require(), name, clock.instant()));
        return new Outcome.Written<>(new SkillView(created.getId(), created.getName()));
    }

    private static Map<String, List<String>> problemsWith(PersonProfileInput submitted) {
        if (submitted == null || submitted.weeklyHours() == null) {
            return Map.of("weeklyHours", List.of(
                    "Say how many hours a week this person is contracted for. It is the only input "
                            + "to their capacity, and a profile without it would show em dashes on "
                            + "every screen that has one."));
        }
        BigDecimal hours = BigDecimal.valueOf(submitted.weeklyHours());
        if (hours.signum() < 0 || hours.compareTo(MAX_WEEKLY_HOURS) > 0) {
            return Map.of("weeklyHours", List.of(
                    "Contracted hours run from 0 to 80 a week."));
        }
        return Map.of();
    }

    private static Map<String, List<String>> problemsWith(AbsenceInput submitted) {
        if (submitted == null || submitted.startsOn() == null || submitted.endsOn() == null) {
            return Map.of("startsOn", List.of("An absence needs a first and a last day."));
        }
        if (submitted.endsOn().isBefore(submitted.startsOn())) {
            return Map.of("endsOn", List.of("The last day cannot be before the first."));
        }
        if (submitted.kind() == null) {
            return Map.of("kind", List.of("Say what kind of absence this is."));
        }
        if (submitted.dayFraction() != null) {
            BigDecimal fraction = BigDecimal.valueOf(submitted.dayFraction());
            if (fraction.compareTo(MIN_DAY_FRACTION) < 0
                    || fraction.compareTo(BigDecimal.ONE) > 0) {
                return Map.of("dayFraction", List.of(
                        "A day's absence runs from a quarter of it to all of it. Leave it out for "
                                + "a whole day."));
            }
        }
        return Map.of();
    }
}
