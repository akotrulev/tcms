package com.tcms.time;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.project.GateResult;
import com.tcms.tenancy.CompanyContext;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

/**
 * Approving posts hours onto the issue and rolls them up to the epic — synchronously, inside the
 * approval transaction (016 D-h).
 *
 * <p>The assertion that carries the module is the pair: <strong>approved and pending are separate
 * numbers and nothing sums them</strong> (016 D-d). One screen says "approved hours only" and
 * another reports hours nobody has approved, so a single figure could not say both, and a rollup
 * that quietly added them would make every panel in the product agree on a number that is wrong.
 *
 * <p>UUID prefix {@code f7}, taken 2026-09-04 and free at the time.
 */
@AutoConfigureMockMvc
@DisplayName("Time rollup — approving posts the hours, and pending is never summed into approved")
class TimeRollupTest extends TimeTestBase {

    private static final UUID ACME = UUID.fromString("f7000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("f7001000-0000-4000-8000-000000000001");

    private static final UUID PM_ROLE = UUID.fromString("10000000-0000-4000-8000-000000000009");
    private static final UUID ENGINEER_ROLE = UUID.fromString("10000000-0000-4000-8000-000000000004");

    private static final UUID DANA = UUID.fromString("f7002000-0000-4000-8000-000000000001");
    private static final UUID PAULA = UUID.fromString("f7002000-0000-4000-8000-000000000002");
    private static final UUID DANA_GRANT = UUID.fromString("f7003000-0000-4000-8000-000000000001");
    private static final UUID PAULA_GRANT = UUID.fromString("f7003000-0000-4000-8000-000000000002");

    private static final UUID EPIC = UUID.fromString("f7004000-0000-4000-8000-000000000001");
    private static final UUID STORY = UUID.fromString("f7004000-0000-4000-8000-000000000002");
    private static final UUID SIBLING = UUID.fromString("f7004000-0000-4000-8000-000000000003");
    private static final UUID UNESTIMATED = UUID.fromString("f7004000-0000-4000-8000-000000000004");

    private static final LocalDate DAY = LocalDate.of(2026, 9, 7);

    @Autowired
    private TimeEntryService entries;

    @Autowired
    private TimesheetService timesheets;

    @Autowired
    private RollupService rollups;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();
        tx.executeWithoutResult(status -> {
            company(ACME, "rollup-acme", "Acme", now);
            activeUser(DANA, "dana@rollup.test", "Dana Engineer", now);
            activeUser(PAULA, "paula@rollup.test", "Paula Manager", now);
        });
        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(PROJECT, ACME, "RUP", "Rollup Project", now);
            member(ACME, DANA, "Dana Engineer", now);
            member(ACME, PAULA, "Paula Manager", now);
        }));
        grantOnProject(DANA_GRANT, ACME, ENGINEER_ROLE, DANA, PROJECT);
        grantOnProject(PAULA_GRANT, ACME, PM_ROLE, PAULA, PROJECT);
        seedProjectDefaults(ACME, PROJECT);
        wipeTime(ACME);

        issueFact(ACME, PROJECT, EPIC, "RUP-100", "Checkout", "EPIC", null, null);
        issueFact(ACME, PROJECT, STORY, "RUP-118", "Guest checkout", "STORY", EPIC,
                new BigDecimal("16.00"));
        issueFact(ACME, PROJECT, SIBLING, "RUP-119", "Saved cards", "STORY", EPIC,
                new BigDecimal("8.00"));
        issueFact(ACME, PROJECT, UNESTIMATED, "RUP-120", "Address validation", "STORY", EPIC, null);
    }

    @AfterEach
    void putEverythingBack() throws SQLException {
        wipeTime(ACME);
    }

    @Test
    @DisplayName("approving posts the hours onto the issue and rolls them up to the epic")
    void approvingPostsHoursOntoTheIssueAndRollsUpToTheEpic() {
        UUID period = log("RUP-118", 14.0);

        TimeRollupView before = issueRollup("RUP-118");
        assertThat(before.approvedHours()).isZero();
        assertThat(before.pendingHours()).isEqualTo(14.0);
        assertThat(before.estimateHours()).isEqualTo(16.0);

        submit(period);
        approve(period);

        TimeRollupView after = issueRollup("RUP-118");
        assertThat(after.approvedHours()).isEqualTo(14.0);
        assertThat(after.pendingHours()).isZero();
        assertThat(after.estimateHours()).isEqualTo(16.0);

        EpicTimeRollupView epic = epicRollup("RUP-100");
        assertThat(epic.approvedHours()).isEqualTo(14.0);
        assertThat(epic.pendingHours()).isZero();
        assertThat(epic.estimateHours())
                .as("the counted children's estimates, summed at read time — there is no epic grain")
                .isEqualTo(24.0);
        assertThat(epic.children()).extracting(EpicTimeRollupView.ChildView::issueKey)
                .containsExactly("RUP-118", "RUP-119", "RUP-120");
    }

    @Test
    @DisplayName("pending and approved are carried apart, and nothing sums them")
    void pendingAndApprovedAreCarriedApart() {
        UUID first = log("RUP-118", 6.0);
        submit(first);
        approve(first);

        // A second fortnight, so the first stays approved while this one is still open.
        logOn("RUP-118", 4.0, LocalDate.of(2026, 9, 20));

        TimeRollupView rollup = issueRollup("RUP-118");
        assertThat(rollup.approvedHours()).isEqualTo(6.0);
        assertThat(rollup.pendingHours()).isEqualTo(4.0);

        EpicTimeRollupView epic = epicRollup("RUP-100");
        assertThat(epic.approvedHours()).isEqualTo(6.0);
        assertThat(epic.pendingHours()).isEqualTo(4.0);
        assertThat(epic.children()).first()
                .satisfies(child -> assertThat(child.approvedHours())
                        .as("the child row shows APPROVED hours, which is what epic.html labels it")
                        .isEqualTo(6.0));
    }

    @Test
    @DisplayName("removing the last entry takes the rollup row away rather than leaving a zero")
    void deletingTheLastEntryRemovesTheRow() {
        log("RUP-118", 3.0);
        assertThat(issueRollup("RUP-118").pendingHours()).isEqualTo(3.0);

        UUID entryId = scoped(() -> allowed(timesheets.current(DANA, DAY)))
                .entries().getFirst().id();
        assertThat(scoped(() -> allowed(entries.delete(DANA, entryId))))
                .isInstanceOf(TimeEntryService.Outcome.Deleted.class);

        TimeRollupView after = issueRollup("RUP-118");
        assertThat(after.pendingHours()).isZero();
        assertThat(after.approvedHours()).isZero();
        assertThat(after.estimateHours())
                .as("the estimate is the tracker's and survives an entry being removed")
                .isEqualTo(16.0);
    }

    @Test
    @DisplayName("a child is over its estimate only when it was estimated at all")
    void anUnestimatedChildIsNeverOverEstimate() {
        UUID period = log("RUP-119", 9.0);
        logOn("RUP-120", 5.0, DAY);
        submit(period);
        approve(period);

        EpicTimeRollupView epic = epicRollup("RUP-100");

        assertThat(epic.children())
                .filteredOn(child -> child.issueKey().equals("RUP-119"))
                .singleElement()
                .satisfies(child -> assertThat(child.overEstimate()).isTrue());
        assertThat(epic.children())
                .filteredOn(child -> child.issueKey().equals("RUP-120"))
                .singleElement()
                .satisfies(child -> {
                    assertThat(child.estimateHours()).isNull();
                    assertThat(child.approvedHours()).isEqualTo(5.0);
                    assertThat(child.overEstimate())
                            .as("a null estimate read as zero would highlight every unestimated row")
                            .isFalse();
                });
    }

    @Test
    @DisplayName("a story's key on the epic path is a 404, not an empty panel")
    void aStoryIsNotAnEpic() {
        assertThat(scoped(() -> rollups.forEpic(DANA, "RUP-118")))
                .isInstanceOf(GateResult.Unreachable.class);
        assertThat(scoped(() -> rollups.forIssue(DANA, "RUP-999")))
                .isInstanceOf(GateResult.Unreachable.class);
    }

    // =====================================================================

    private UUID log(String key, double hours) {
        return logOn(key, hours, DAY);
    }

    private UUID logOn(String key, double hours, LocalDate day) {
        TimeEntryService.Outcome outcome = scoped(() -> allowed(entries.log(DANA,
                new TimeEntryInput(TimeSubjectType.ISSUE, key, day, hours, null))));
        assertThat(outcome).isInstanceOf(TimeEntryService.Outcome.Written.class);
        return ((TimeEntryService.Outcome.Written) outcome).view().periodId();
    }

    private void submit(UUID periodId) {
        assertThat(scoped(() -> allowed(timesheets.submit(DANA, periodId))))
                .isInstanceOf(TimesheetService.Outcome.Decided.class);
    }

    private void approve(UUID periodId) {
        assertThat(scoped(() -> allowed(timesheets.decide(
                PAULA, periodId, new TimesheetDecisionInput("APPROVE", null)))))
                .isInstanceOf(TimesheetService.Outcome.Decided.class);
    }

    private TimeRollupView issueRollup(String key) {
        return scoped(() -> allowed(rollups.forIssue(DANA, key)));
    }

    private EpicTimeRollupView epicRollup(String key) {
        return scoped(() -> allowed(rollups.forEpic(DANA, key)));
    }

    private <T> T scoped(Supplier<T> call) {
        try (var ignored = CompanyContext.open(ACME)) {
            return call.get();
        }
    }

    private static <T> T allowed(GateResult<T> gate) {
        assertThat(gate).isInstanceOf(GateResult.Allowed.class);
        return ((GateResult.Allowed<T>) gate).value();
    }
}
