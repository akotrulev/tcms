package com.tcms.staffing;

import static org.assertj.core.api.Assertions.assertThat;

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
 * 017 D-e, asserted at the two surfaces that read it: <strong>over-allocation is a DAILY peak, not
 * a monthly sum.</strong>
 *
 * <p>The acceptance criterion the plan states — 50% on one project and 51% on another over
 * overlapping days raises the flag with a peak of 101 — and its complement, which is the one a
 * monthly sum gets wrong: 100% for the first half of a month and 100% for the second is <em>not</em>
 * over-allocation, and a sum would report it as 200%.
 *
 * <p>The complement is the assertion that matters. {@code CapacityTest} already proves both against
 * the pure function; what this adds is that the rows actually reach it — two allocations on two
 * different projects, in one company, read back through the gate that decides who may ask.
 *
 * <p>UUID prefix {@code a3}, taken 2026-09-04 and free at the time.
 */
@AutoConfigureMockMvc
@DisplayName("Over-allocation — the daily peak, through real rows and both scopes")
class OverAllocationTest extends StaffingTestBase {

    private static final UUID ACME = UUID.fromString("a3000000-0000-4000-8000-000000000001");
    private static final UUID LOYALTY = UUID.fromString("a3001000-0000-4000-8000-000000000001");
    private static final UUID PORTAL = UUID.fromString("a3001000-0000-4000-8000-000000000002");

    /** Holds the three COMPANY-scope keys and no project grant at all. */
    private static final UUID MANAGER = UUID.fromString("a3002000-0000-4000-8000-000000000001");
    /** The allocated person. Holds nothing; their own staffing is readable to them regardless. */
    private static final UUID DEV = UUID.fromString("a3002000-0000-4000-8000-000000000002");
    /** Project Manager on LOYALTY only — the project-scope reader (017 D-f). */
    private static final UUID PM = UUID.fromString("a3002000-0000-4000-8000-000000000003");

    private static final UUID MANAGER_GRANT = UUID.fromString("a3003000-0000-4000-8000-000000000001");
    private static final UUID PM_GRANT = UUID.fromString("a3003000-0000-4000-8000-000000000002");

    /** A Monday, so the window's first day is a working one and the peak has a date to land on. */
    private static final LocalDate MON = LocalDate.of(2026, 9, 7);
    private static final LocalDate FRI = LocalDate.of(2026, 9, 11);

    @Autowired
    private StaffingService staffing;

    @Autowired
    private AllocationService allocationService;

    @Autowired
    private AllocationRepository allocations;

    @Autowired
    private PersonProfileRepository profiles;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "staffing-peak-acme", "Acme", now);
            activeUser(MANAGER, "manager@staffing-peak.test", "M. Manager", now);
            activeUser(DEV, "dev@staffing-peak.test", "D. Developer", now);
            activeUser(PM, "pm@staffing-peak.test", "P. Manager", now);
        });
        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(LOYALTY, ACME, "PKLOY", "Acme Loyalty", now);
            seedProject(PORTAL, ACME, "PKPOR", "Bergstrom Portal", now);
            member(ACME, MANAGER, "M. Manager", now);
            member(ACME, DEV, "D. Developer", now);
            member(ACME, PM, "P. Manager", now);
        }));
        grantInCompany(MANAGER_GRANT, ACME, ACCOUNT_MANAGER_ROLE, MANAGER);
        grantOnProject(PM_GRANT, ACME, PROJECT_MANAGER_ROLE, PM, LOYALTY);

        wipeStaffing(ACME);
        inCompany(ACME, () -> tx.executeWithoutResult(status -> profiles.save(
                PersonProfile.create(UUID.randomUUID(), ACME, DEV, new BigDecimal("40"),
                        "Engineer", "Stockholm", LocalDate.of(2020, 9, 7), now))));
    }

    @Test
    @DisplayName("50% and 51% over the same days is a peak of 101 and the flag is raised")
    void overlappingAllocationsPeak() {
        allocate(LOYALTY, 50, MON, FRI);
        allocate(PORTAL, 51, MON, FRI);

        PersonCapacityView view = readDev();

        assertThat(view.peakPercent()).isEqualTo(101);
        assertThat(view.overAllocated()).isTrue();
        assertThat(view.peakOn()).isEqualTo(MON);
        // The mean is the row label and the peak is the flag — two statistics, deliberately.
        assertThat(view.allocatedPercent()).isEqualTo(101);
        assertThat(view.freePercent()).isZero();
        assertThat(view.allocations()).extracting(AllocationSpanView::projectKey)
                .containsExactlyInAnyOrder("PKLOY", "PKPOR");
    }

    @Test
    @DisplayName("consecutive 100% allocations are not over-allocation — the sum a month's view would report is 200")
    void consecutiveAllocationsAreNotAPeak() {
        allocate(LOYALTY, 100, MON, MON.plusDays(1));
        allocate(PORTAL, 100, MON.plusDays(2), FRI);

        PersonCapacityView view = readDev();

        assertThat(view.peakPercent()).isEqualTo(100);
        assertThat(view.overAllocated()).isFalse();
    }

    @Test
    @DisplayName("the project screen carries the company-wide total and the flag, and names no other project")
    void projectViewCarriesTheTotalWithoutTheBreakdown() {
        allocate(LOYALTY, 50, MON, FRI);
        allocate(PORTAL, 51, MON, FRI);

        ProjectStaffingView view = allowed(
                scoped(ACME, () -> allocationService.staffingOf(PM, LOYALTY, MON, FRI)));

        assertThat(view.people()).singleElement().satisfies(person -> {
            assertThat(person.percentOnThisProject()).isEqualTo(50);
            assertThat(person.totalPercent()).isEqualTo(101);
            assertThat(person.overAllocated()).isTrue();
        });
        // 017 D-f: the Project Manager holds no company-wide key, so the OTHER project must not be
        // nameable from here. The allocations list is this project's alone.
        assertThat(view.allocations()).singleElement()
                .satisfies(row -> assertThat(row.projectId()).isEqualTo(LOYALTY));
    }

    @Test
    @DisplayName("a person nobody has profiled is still reported as over-allocated, with null hours")
    void unprofiledPeopleStillRaiseTheFlag() throws SQLException {
        inCompany(ACME, () -> tx.executeWithoutResult(status ->
                profiles.findByCompanyIdAndUserId(ACME, DEV).ifPresent(profiles::delete)));

        allocate(LOYALTY, 60, MON, FRI);
        allocate(PORTAL, 60, MON, FRI);

        PersonCapacityView view = readDev();

        // The refusal 016 D-j made and this module keeps: no profile means no hours, not 40 of them.
        assertThat(view.profile()).isNull();
        assertThat(view.capacityHours()).isNull();
        assertThat(view.utilisationPercent()).isNull();
        // And the percentages are real anyway, which is how anybody notices the profile is missing.
        assertThat(view.peakPercent()).isEqualTo(120);
        assertThat(view.overAllocated()).isTrue();
    }

    private PersonCapacityView readDev() {
        return allowed(scoped(ACME, () -> staffing.person(MANAGER, DEV, MON, FRI)));
    }

    private void allocate(UUID projectId, int percent, LocalDate from, LocalDate to) {
        inCompany(ACME, () -> tx.executeWithoutResult(status -> allocations.save(Allocation.of(
                UUID.randomUUID(), ACME, projectId, DEV, percent, from, to, null, MANAGER,
                Instant.now()))));
    }
}
