package com.tcms.staffing;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.project.GateResult;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

/**
 * Story S1's third acceptance criterion, and the reason it is one test rather than four:
 * <strong>one absence, three surfaces, one number.</strong>
 *
 * <p>"An absence zeroes capacity everywhere" is a claim about several screens, and the only way it
 * can be a fact rather than four separately-checked claims is that none of them computes anything —
 * they all read {@link Capacity} through {@link CapacityReader}. So this records ONE absence and
 * asks the company capacity view, the person read and {@code GET /staffing/capacity/me} the same
 * question. If a fourth surface is added and computes its own hours, this test stays green and the
 * product starts disagreeing with itself, which is exactly why the shared function exists and why
 * {@code StaffingService} has no arithmetic in it.
 *
 * <p>UUID prefix {@code a4}, taken 2026-09-04 and free at the time.
 */
@AutoConfigureMockMvc
@DisplayName("An absence reduces capacity — the same figure on all three surfaces")
class AbsenceZeroesCapacityTest extends StaffingTestBase {

    private static final UUID ACME = UUID.fromString("a4000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("a4001000-0000-4000-8000-000000000001");

    /** Account Manager — the COMPANY-scope reader. */
    private static final UUID MANAGER = UUID.fromString("a4002000-0000-4000-8000-000000000001");
    /** The absent person. Holds no staffing key: their own capacity needs none (017 D-m, D-h). */
    private static final UUID DEV = UUID.fromString("a4002000-0000-4000-8000-000000000002");

    private static final UUID MANAGER_GRANT =
            UUID.fromString("a4003000-0000-4000-8000-000000000001");

    private static final LocalDate MON = LocalDate.of(2026, 9, 7);
    private static final LocalDate FRI = LocalDate.of(2026, 9, 11);

    @Autowired
    private StaffingService staffing;

    @Autowired
    private ProfileService profileService;

    @Autowired
    private PersonProfileRepository profiles;

    @Autowired
    private AllocationRepository allocations;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "staffing-absence-acme", "Acme", now);
            activeUser(MANAGER, "manager@staffing-absence.test", "M. Manager", now);
            activeUser(DEV, "dev@staffing-absence.test", "D. Developer", now);
        });
        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(PROJECT, ACME, "ABSP", "Absence Project", now);
            member(ACME, MANAGER, "M. Manager", now);
            member(ACME, DEV, "D. Developer", now);
        }));
        grantInCompany(MANAGER_GRANT, ACME, ACCOUNT_MANAGER_ROLE, MANAGER);

        wipeStaffing(ACME);
        inCompany(ACME, () -> tx.executeWithoutResult(status -> profiles.save(
                PersonProfile.create(UUID.randomUUID(), ACME, DEV, new BigDecimal("40"),
                        "Engineer", "Malmö", null, now))));
    }

    @Test
    @DisplayName("a whole day off takes eight hours off the week, on the company view, the person and /capacity/me")
    void oneAbsenceMovesEveryFigure() {
        assertThat(companyFigureFor(DEV)).isEqualByComparingTo("40.00");

        recordAbsence(MON, MON, AbsenceKind.LEAVE, null);

        assertThat(companyFigureFor(DEV)).isEqualByComparingTo("32.00");
        assertThat(personFigureFor(DEV)).isEqualByComparingTo("32.00");
        assertThat(selfFigure()).isEqualByComparingTo("32.00");
    }

    @Test
    @DisplayName("a half day takes half of it, and the absence hours say what was lost")
    void halfDaysAreHalf() {
        recordAbsence(MON, MON, AbsenceKind.HOLIDAY, 0.5);

        PersonCapacityView view = allowed(scoped(ACME, () -> staffing.person(MANAGER, DEV, MON, FRI)));

        assertThat(view.capacityHours()).isEqualTo(36.0);
        assertThat(view.absenceHours()).isEqualTo(4.0);
        assertThat(view.absences()).singleElement().satisfies(absence -> {
            assertThat(absence.kind()).isEqualTo(AbsenceKind.HOLIDAY);
            assertThat(absence.dayFraction()).isEqualTo(0.5);
        });
    }

    @Test
    @DisplayName("an allocation cannot claim a day the person is away — utilisation denominates over what is left")
    void allocatedCapacityIsNetOfAbsence() {
        inCompany(ACME, () -> tx.executeWithoutResult(status -> allocations.save(Allocation.of(
                UUID.randomUUID(), ACME, PROJECT, DEV, 50, MON, FRI, null, MANAGER,
                Instant.now()))));
        recordAbsence(MON, MON, AbsenceKind.SICK, null);

        PersonCapacityView view = allowed(scoped(ACME, () -> staffing.person(MANAGER, DEV, MON, FRI)));

        // Half of 32, not half of 40. The absence is subtracted once, by the one function that
        // subtracts it, and the allocation is a share of what survived.
        assertThat(view.allocatedCapacityHours()).isEqualTo(16.0);
        // Nobody has approved any hours, so utilisation is 0% rather than null: the denominator
        // exists. Null is reserved for the person nobody has allocated (017 D-i).
        assertThat(view.utilisationPercent()).isZero();
    }

    @Test
    @DisplayName("recording your OWN absence needs no staffing key (017 D-h)")
    void recordingYourOwnAbsenceNeedsNoKey() {
        ProfileService.Outcome<AbsenceView> outcome = allowed(scoped(ACME, () ->
                profileService.createAbsence(DEV, DEV,
                        new AbsenceInput(MON, MON, AbsenceKind.LEAVE, null, "Moving house"))));

        assertThat(outcome).isInstanceOf(ProfileService.Outcome.Written.class);
        assertThat(selfFigure()).isEqualByComparingTo("32.00");
    }

    @Test
    @DisplayName("but somebody else's does, and a member holding no key is refused")
    void recordingSomebodyElsesNeedsTheKey() {
        assertThat(scoped(ACME, () -> profileService.createAbsence(
                DEV, MANAGER, new AbsenceInput(MON, MON, AbsenceKind.LEAVE, null, null))))
                .isInstanceOf(GateResult.Denied.class);
    }

    private void recordAbsence(LocalDate from, LocalDate to, AbsenceKind kind, Double fraction) {
        allowed(scoped(ACME, () -> profileService.createAbsence(
                MANAGER, DEV, new AbsenceInput(from, to, kind, fraction, null))));
    }

    private BigDecimal companyFigureFor(UUID personId) {
        StaffingCapacityView view =
                allowed(scoped(ACME, () -> staffing.capacity(MANAGER, MON, FRI)));
        return view.people().stream()
                .filter(person -> person.person().id().equals(personId))
                .map(person -> BigDecimal.valueOf(person.capacityHours()))
                .findFirst()
                .orElseThrow();
    }

    private BigDecimal personFigureFor(UUID personId) {
        return BigDecimal.valueOf(allowed(
                scoped(ACME, () -> staffing.person(MANAGER, personId, MON, FRI))).capacityHours());
    }

    private BigDecimal selfFigure() {
        return BigDecimal.valueOf(
                scoped(ACME, () -> staffing.myCapacity(DEV, MON, FRI)).capacityHours());
    }
}
