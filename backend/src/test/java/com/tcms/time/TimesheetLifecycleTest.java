package com.tcms.time;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.project.GateResult;
import com.tcms.tenancy.CompanyContext;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

/**
 * The lock the whole feature turns on: <em>a submitted period is editable by nobody, and a rejected
 * one is editable again.</em>
 *
 * <p>Every other rule in this module is a convenience beside that one. A fortnight that could still
 * be edited after approval would make the approval mean nothing, and a rejection that did not unlock
 * the period would leave the person who has to fix it with nothing to fix it with.
 *
 * <p>UUID prefix {@code f6}, taken 2026-09-04 and free at the time. The whole namespace this class
 * uses is greppable from here.
 */
@AutoConfigureMockMvc
@DisplayName("Timesheet lifecycle — submitted refuses an edit, rejected accepts one")
class TimesheetLifecycleTest extends TimeTestBase {

    private static final UUID ACME = UUID.fromString("f6000000-0000-4000-8000-000000000001");
    private static final UUID STOREFRONT = UUID.fromString("f6001000-0000-4000-8000-000000000001");
    private static final UUID LOYALTY = UUID.fromString("f6001000-0000-4000-8000-000000000002");

    /** Project Manager — the tier holding all four time keys, {@code time:approve} included (§A3). */
    private static final UUID PM_ROLE = UUID.fromString("10000000-0000-4000-8000-000000000009");
    /** Test Engineer — {@code time:log} and nothing else in this namespace. */
    private static final UUID ENGINEER_ROLE = UUID.fromString("10000000-0000-4000-8000-000000000004");

    private static final UUID DANA = UUID.fromString("f6002000-0000-4000-8000-000000000001");
    private static final UUID PAULA = UUID.fromString("f6002000-0000-4000-8000-000000000002");

    private static final UUID DANA_GRANT = UUID.fromString("f6003000-0000-4000-8000-000000000001");
    private static final UUID DANA_GRANT_TWO = UUID.fromString("f6003000-0000-4000-8000-000000000002");
    private static final UUID PAULA_GRANT = UUID.fromString("f6003000-0000-4000-8000-000000000003");

    private static final UUID STORY = UUID.fromString("f6004000-0000-4000-8000-000000000001");
    private static final UUID OTHER_STORY = UUID.fromString("f6004000-0000-4000-8000-000000000002");

    /** The 7th is inside 1-15, so a period opened for it is the same fortnight every time. */
    private static final LocalDate DAY = LocalDate.of(2026, 9, 7);

    @Autowired
    private TimeEntryService entries;

    @Autowired
    private TimesheetService timesheets;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();
        tx.executeWithoutResult(status -> {
            company(ACME, "lifecycle-acme", "Acme", now);
            activeUser(DANA, "dana@lifecycle.test", "Dana Engineer", now);
            activeUser(PAULA, "paula@lifecycle.test", "Paula Manager", now);
        });
        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(STOREFRONT, ACME, "LSF", "Storefront", now);
            seedProject(LOYALTY, ACME, "LLY", "Loyalty", now);
            member(ACME, DANA, "Dana Engineer", now);
            member(ACME, PAULA, "Paula Manager", now);
        }));
        grantOnProject(DANA_GRANT, ACME, ENGINEER_ROLE, DANA, STOREFRONT);
        grantOnProject(DANA_GRANT_TWO, ACME, ENGINEER_ROLE, DANA, LOYALTY);
        grantOnProject(PAULA_GRANT, ACME, PM_ROLE, PAULA, STOREFRONT);
        seedProjectDefaults(ACME, STOREFRONT);
        seedProjectDefaults(ACME, LOYALTY);
        wipeTime(ACME);
        issueFact(ACME, STOREFRONT, STORY, "LSF-1", "Guest checkout", "STORY", null,
                new BigDecimal("16.00"));
        issueFact(ACME, LOYALTY, OTHER_STORY, "LLY-1", "Points ledger", "STORY", null, null);
    }

    @AfterEach
    void putEverythingBack() throws SQLException {
        wipeTime(ACME);
    }

    @Test
    @DisplayName("a submitted period refuses an edit with the state it is in")
    void aSubmittedPeriodRefusesAnEdit() {
        UUID period = logMeeting(4.0).periodId();
        submitted(period);

        TimeEntryService.Outcome refused = scoped(() ->
                allowed(entries.log(DANA, meeting(1.0))));

        assertThat(refused)
                .isInstanceOf(TimeEntryService.Outcome.Locked.class)
                .extracting(outcome -> ((TimeEntryService.Outcome.Locked) outcome).state())
                .isEqualTo(TimesheetState.SUBMITTED);
    }

    @Test
    @DisplayName("an approved period refuses an edit too, and that is the state that is final")
    void anApprovedPeriodRefusesAnEditAsWell() {
        UUID period = logMeeting(4.0).periodId();
        submitted(period);
        decided(period, "APPROVE", null);

        assertThat(scoped(() -> allowed(entries.log(DANA, meeting(1.0)))))
                .isInstanceOf(TimeEntryService.Outcome.Locked.class);
    }

    @Test
    @DisplayName("a rejected period is editable again and keeps the note that sent it back")
    void aRejectedPeriodIsEditableAgainAndKeepsItsNote() {
        UUID period = logMeeting(4.0).periodId();
        submitted(period);

        TimesheetPeriodView after = decided(period, "REJECT", "re-point LSF-1");

        assertThat(after.state()).isEqualTo(TimesheetState.REJECTED);
        assertThat(after.editable()).isTrue();
        assertThat(after.decisionNote()).isEqualTo("re-point LSF-1");
        assertThat(after.entries()).allSatisfy(entry -> assertThat(entry.editable()).isTrue());

        assertThat(scoped(() -> allowed(entries.log(DANA, meeting(1.0)))))
                .as("the whole point of a rejection is that the fortnight can be worked on again")
                .isInstanceOf(TimeEntryService.Outcome.Written.class);
    }

    @Test
    @DisplayName("a rejection without a note is refused, and the note is the field named")
    void aRejectionWithoutANoteIsRefused() {
        UUID period = logMeeting(4.0).periodId();
        submitted(period);

        TimesheetService.Outcome refused = scoped(() -> allowed(
                timesheets.decide(PAULA, period, new TimesheetDecisionInput("REJECT", "   "))));

        assertThat(refused).isInstanceOf(TimesheetService.Outcome.Invalid.class);
        assertThat(((TimesheetService.Outcome.Invalid) refused).errors()).containsKey("note");

        assertThat(scoped(() -> allowed(timesheets.find(DANA, period))).state())
                .as("a refused rejection leaves the period where it was")
                .isEqualTo(TimesheetState.SUBMITTED);
    }

    @Test
    @DisplayName("re-submitting a rejected period keeps the note as history")
    void resubmittingKeepsTheNote() {
        UUID period = logMeeting(4.0).periodId();
        submitted(period);
        decided(period, "REJECT", "split the Tuesday");

        TimesheetPeriodView again = submitted(period);

        assertThat(again.state()).isEqualTo(TimesheetState.SUBMITTED);
        assertThat(again.decisionNote())
                .as("the engineer needs to see what they were asked to fix while they fix it")
                .isEqualTo("split the Tuesday");
    }

    @Test
    @DisplayName("a withdrawn rejection puts the period back to submitted, note intact")
    void withdrawingARejectionPutsItBackToSubmitted() {
        UUID period = logMeeting(4.0).periodId();
        submitted(period);
        decided(period, "REJECT", "sent back by mistake");

        TimesheetPeriodView after = decided(period, "WITHDRAW_REJECTION", null);

        assertThat(after.state()).isEqualTo(TimesheetState.SUBMITTED);
        assertThat(after.editable()).isFalse();
        assertThat(after.decisionNote()).isEqualTo("sent back by mistake");
    }

    @Test
    @DisplayName("approving something nobody submitted is a conflict, not a silent approval")
    void approvingAnOpenPeriodIsRefused() {
        UUID period = logMeeting(4.0).periodId();

        assertThat(scoped(() -> allowed(
                timesheets.decide(PAULA, period, new TimesheetDecisionInput("APPROVE", null)))))
                .isInstanceOf(TimesheetService.Outcome.NotInThatState.class);
    }

    @Test
    @DisplayName("submitting somebody else's period is refused however senior the caller")
    void aManagerCannotSubmitSomebodyElsesPeriod() {
        UUID period = logMeeting(4.0).periodId();

        assertThat(scoped(() -> timesheets.submit(PAULA, period)))
                .as("submitting somebody else's timesheet is not an act this product has")
                .isInstanceOf(GateResult.Denied.class);
    }

    @Test
    @DisplayName("an issue entry needs a key, and a meeting refuses one")
    void theSubjectShapeIsEnforcedBeforeTheDatabaseHasTo() {
        TimeEntryService.Outcome missing = scoped(() -> allowed(entries.log(DANA,
                new TimeEntryInput(TimeSubjectType.ISSUE, null, DAY, 2.0, null))));
        assertThat(((TimeEntryService.Outcome.Invalid) missing).errors()).containsKey("issueKey");

        TimeEntryService.Outcome spurious = scoped(() -> allowed(entries.log(DANA,
                new TimeEntryInput(TimeSubjectType.MEETING, "LSF-1", DAY, 2.0, null))));
        assertThat(((TimeEntryService.Outcome.Invalid) spurious).errors()).containsKey("issueKey");
    }

    @Test
    @DisplayName("hours outside a quarter-hour to a day are refused")
    void hoursAreBounded() {
        assertThat(scoped(() -> allowed(entries.log(DANA, meeting(0.1)))))
                .isInstanceOf(TimeEntryService.Outcome.Invalid.class);
        assertThat(scoped(() -> allowed(entries.log(DANA, meeting(25.0)))))
                .isInstanceOf(TimeEntryService.Outcome.Invalid.class);
    }

    @Test
    @DisplayName("D-i — a period spanning two projects reaches the queue of a PM on one of them")
    void theQueueShowsAPeriodNamingAProjectTheCallerApprovesFor() {
        logIssue("LSF-1", 3.0);
        UUID period = logIssue("LLY-1", 2.0).periodId();
        submitted(period);

        List<TimesheetQueueEntryView> queue =
                scoped(() -> allowed(timesheets.queue(PAULA, DAY)));

        assertThat(queue).singleElement().satisfies(row -> {
            assertThat(row.period().id()).isEqualTo(period);
            assertThat(row.period().personName()).isEqualTo("Dana Engineer");
            // Loyalty is absent because Paula cannot see it, and the totals are documented as not
            // adding up to loggedHours for exactly that reason.
            assertThat(row.projectTotals()).singleElement().satisfies(total -> {
                assertThat(total.projectName()).isEqualTo("Storefront");
                assertThat(total.hours()).isEqualTo(3.0);
            });
            assertThat(row.period().loggedHours()).isEqualTo(5.0);
        });

        assertThat(scoped(() -> allowed(
                timesheets.decide(PAULA, period, new TimesheetDecisionInput("APPROVE", null)))))
                .as("approving covers the whole period, Loyalty's two hours included (D-i)")
                .isInstanceOf(TimesheetService.Outcome.Decided.class);
    }

    @Test
    @DisplayName("a period naming no project at all is still approvable by a holder of the key")
    void aPeriodOfMeetingsIsApprovableByAnybodyHoldingTheKey() {
        UUID period = logMeeting(4.0).periodId();
        submitted(period);

        assertThat(scoped(() -> allowed(timesheets.queue(PAULA, DAY))))
                .as("a fortnight of leave nobody could ever approve is a person who cannot close "
                        + "their timesheet")
                .hasSize(1);
    }

    @Test
    @DisplayName("an engineer holding only time:log cannot approve anybody's period")
    void anEngineerCannotApprove() {
        UUID period = logMeeting(4.0).periodId();
        submitted(period);

        assertThat(scoped(() -> timesheets.queue(DANA, DAY))).isInstanceOf(GateResult.Denied.class);
        assertThat(scoped(() ->
                timesheets.decide(DANA, period, new TimesheetDecisionInput("APPROVE", null))))
                .isInstanceOf(GateResult.Denied.class);
    }

    // =====================================================================

    private TimeEntryInput meeting(double hours) {
        return new TimeEntryInput(TimeSubjectType.MEETING, null, DAY, hours, null);
    }

    private TimeEntryView logMeeting(double hours) {
        TimeEntryService.Outcome outcome = scoped(() -> allowed(entries.log(DANA, meeting(hours))));
        assertThat(outcome).isInstanceOf(TimeEntryService.Outcome.Written.class);
        return ((TimeEntryService.Outcome.Written) outcome).view();
    }

    private TimeEntryView logIssue(String key, double hours) {
        TimeEntryService.Outcome outcome = scoped(() -> allowed(entries.log(DANA,
                new TimeEntryInput(TimeSubjectType.ISSUE, key, DAY, hours, null))));
        assertThat(outcome).isInstanceOf(TimeEntryService.Outcome.Written.class);
        return ((TimeEntryService.Outcome.Written) outcome).view();
    }

    private TimesheetPeriodView submitted(UUID periodId) {
        TimesheetService.Outcome outcome = scoped(() -> allowed(timesheets.submit(DANA, periodId)));
        assertThat(outcome).isInstanceOf(TimesheetService.Outcome.Decided.class);
        return ((TimesheetService.Outcome.Decided) outcome).view();
    }

    private TimesheetPeriodView decided(UUID periodId, String decision, String note) {
        TimesheetService.Outcome outcome = scoped(() -> allowed(
                timesheets.decide(PAULA, periodId, new TimesheetDecisionInput(decision, note))));
        assertThat(outcome).isInstanceOf(TimesheetService.Outcome.Decided.class);
        return ((TimesheetService.Outcome.Decided) outcome).view();
    }

    /**
     * Runs the call inside this company's scope.
     *
     * <p>Without it every RLS read is empty and the gate answers "no such project", which reads as
     * an authorization defect and is not one — the trap {@code TrackerEndpointTestBase} records
     * losing nine tests to.
     */
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
