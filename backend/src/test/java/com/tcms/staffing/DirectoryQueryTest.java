package com.tcms.staffing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

/**
 * The question the module was built to answer: <strong>who is free next month with skill X.</strong>
 *
 * <p>Three people and two skills, arranged so that each of the filter's two halves is the thing that
 * excludes somebody:
 *
 * <ul>
 *   <li><strong>Ada</strong> holds both skills and is booked solid for the whole window — excluded
 *       by {@code freeFrom}, and she is the reason that filter reads the walk's own answer rather
 *       than a second definition of "free".</li>
 *   <li><strong>Bo</strong> holds both skills and has a free day — the answer.</li>
 *   <li><strong>Cai</strong> holds one of the two and is completely free — excluded by the skill
 *       filter, and she is why the skills are ANDed: "who knows Playwright <em>and</em> Java" is one
 *       question, not two lists to union.</li>
 * </ul>
 *
 * <p>The directory also lists <em>every member</em>, profiled or not (017 D-k), which is the
 * assertion the unfiltered read makes: {@code directory.html}'s "3 of 11 people match" counts
 * members, and a directory built from {@code person_profile} would silently show only the people
 * somebody had got round to profiling.
 *
 * <p>UUID prefix {@code a7}, taken 2026-09-04 and free at the time.
 */
@AutoConfigureMockMvc
@DisplayName("The skills directory — who is free next month with skill X")
class DirectoryQueryTest extends StaffingTestBase {

    private static final UUID ACME = UUID.fromString("a7000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("a7001000-0000-4000-8000-000000000001");

    private static final UUID MANAGER = UUID.fromString("a7002000-0000-4000-8000-000000000001");
    private static final UUID ADA = UUID.fromString("a7002000-0000-4000-8000-000000000002");
    private static final UUID BO = UUID.fromString("a7002000-0000-4000-8000-000000000003");
    private static final UUID CAI = UUID.fromString("a7002000-0000-4000-8000-000000000004");

    private static final UUID MANAGER_GRANT =
            UUID.fromString("a7003000-0000-4000-8000-000000000001");

    /** October 2026 — the 1st is a Thursday, so the window opens on a working day. */
    private static final LocalDate FIRST = LocalDate.of(2026, 10, 1);
    private static final LocalDate LAST = LocalDate.of(2026, 10, 30);

    @Autowired
    private StaffingService staffing;

    @Autowired
    private ProfileService profiles;

    @Autowired
    private PersonProfileRepository profileRows;

    @Autowired
    private AllocationRepository allocations;

    private UUID playwright;
    private UUID java;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "staffing-directory-acme", "Acme", now);
            activeUser(MANAGER, "manager@staffing-directory.test", "M. Manager", now);
            activeUser(ADA, "ada@staffing-directory.test", "Ada Booked", now);
            activeUser(BO, "bo@staffing-directory.test", "Bo Free", now);
            activeUser(CAI, "cai@staffing-directory.test", "Cai Onlyjava", now);
        });
        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(PROJECT, ACME, "DIRP", "Directory Project", now);
            member(ACME, MANAGER, "M. Manager", now);
            member(ACME, ADA, "Ada Booked", now);
            member(ACME, BO, "Bo Free", now);
            member(ACME, CAI, "Cai Onlyjava", now);
        }));
        grantInCompany(MANAGER_GRANT, ACME, ACCOUNT_MANAGER_ROLE, MANAGER);
        wipeStaffing(ACME);

        playwright = skillNamed("Playwright");
        java = skillNamed("Java");

        give(ADA, List.of(playwright, java));
        give(BO, List.of(playwright, java));
        give(CAI, List.of(java));

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            profileRows.save(PersonProfile.create(UUID.randomUUID(), ACME, ADA,
                    new BigDecimal("40"), "Test Lead", "Stockholm",
                    LocalDate.of(2020, 10, 1), now));
            allocations.save(Allocation.of(UUID.randomUUID(), ACME, PROJECT, ADA, 100,
                    FIRST, LAST, null, MANAGER, now));
        }));
    }

    @Test
    @DisplayName("both skills and a free day — one person, in one query")
    void skillsAreAndedAndFreeFromIsTheWalksAnswer() {
        List<PersonCapacityView> matches = allowed(scoped(ACME, () -> staffing.directory(
                MANAGER, FIRST, LAST, List.of(playwright, java), FIRST)));

        assertThat(matches).extracting(person -> person.person().id()).containsExactly(BO);
        assertThat(matches).singleElement().satisfies(person -> {
            assertThat(person.freeFrom()).isEqualTo(FIRST);
            assertThat(person.skills()).extracting(SkillView::name)
                    .containsExactly("Java", "Playwright");
        });
    }

    @Test
    @DisplayName("without freeFrom the booked person is back — the two halves of the filter are separate")
    void theSkillFilterAloneKeepsTheBookedPerson() {
        List<PersonCapacityView> matches = allowed(scoped(ACME, () -> staffing.directory(
                MANAGER, FIRST, LAST, List.of(playwright, java), null)));

        assertThat(matches).extracting(person -> person.person().id())
                .containsExactlyInAnyOrder(ADA, BO);
    }

    @Test
    @DisplayName("the unfiltered directory lists every member, profiled or not (017 D-k)")
    void everyMemberIsListed() {
        List<PersonCapacityView> everybody = allowed(scoped(ACME, () ->
                staffing.directory(MANAGER, FIRST, LAST, List.of(), null)));

        assertThat(everybody).extracting(person -> person.person().id())
                .containsExactlyInAnyOrder(MANAGER, ADA, BO, CAI);
        // Three of the four have no profile, and that reads as null hours rather than as absence
        // from the list — which is the whole point of listing members instead of profiles.
        assertThat(everybody).filteredOn(person -> person.profile() == null).hasSize(3);
    }

    @Test
    @DisplayName("a skill nobody holds matches nobody, rather than matching everybody")
    void anUnheldSkillMatchesNobody() {
        UUID cobol = skillNamed("COBOL");

        assertThat(allowed(scoped(ACME, () ->
                staffing.directory(MANAGER, FIRST, LAST, List.of(cobol), null)))).isEmpty();
    }

    @Test
    @DisplayName("a repeated skill name is refused case-insensitively — citext, and the cast that finds it")
    void theVocabularyIsCaseFolded() {
        ProfileService.Outcome<SkillView> again = allowed(scoped(ACME, () ->
                profiles.createSkill(MANAGER, new SkillInput("playwright"))));

        // Without SkillRepository.findByName's CAST(:name AS citext) this is a Written outcome
        // followed by a uq_skill_name violation — the lookup compares case-sensitively while the
        // index does not, and the two disagree about a name somebody typed.
        assertThat(again).isInstanceOf(ProfileService.Outcome.Conflict.class);
    }

    private UUID skillNamed(String name) {
        ProfileService.Outcome<SkillView> outcome =
                allowed(scoped(ACME, () -> profiles.createSkill(MANAGER, new SkillInput(name))));
        assertThat(outcome).isInstanceOf(ProfileService.Outcome.Written.class);
        return ((ProfileService.Outcome.Written<SkillView>) outcome).value().id();
    }

    private void give(UUID personId, List<UUID> skillIds) {
        assertThat(allowed(scoped(ACME, () ->
                profiles.putSkills(MANAGER, personId, new PersonSkillsInput(skillIds)))))
                .isInstanceOf(ProfileService.Outcome.Written.class);
    }
}
