package com.tcms.time;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.tcms.identity.PermissionEvaluator;
import com.tcms.project.GateResult;
import com.tcms.tenancy.CompanyContext;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

/**
 * Scope §7's sharpest boundary, asserted as a <strong>negative</strong>.
 *
 * <p>The working agreement's rule applies literally here: <em>a catalogue-wide invariant needs a
 * computed check, not a careful reading.</em> Reading §A3 and concluding that a Test Engineer lacks
 * the money keys is exactly the pass that missed five join tables without {@code company_id}. So the
 * money keys are derived from the catalogue and their absence is what is asserted.
 *
 * <p><strong>Written before M8 exists, deliberately.</strong> When rate cards arrive, this test is
 * already standing and M8 inherits a boundary it cannot quietly widen. It is not a second copy of
 * {@code SeedDataTest.moneyIsFinanceAndAdminsOnly}: that one asks what the <em>seed</em> grants, and
 * this one asks what a person actually <em>evaluates to</em> on a real project, and what the time
 * surface puts on the wire for them.
 *
 * <p>UUID prefix {@code f8}, taken 2026-09-04 and free at the time.
 */
@AutoConfigureMockMvc
@DisplayName("Time and money — a Test Engineer logs hours and cannot see what they cost")
class TimeMoneyBoundaryTest extends TimeTestBase {

    /**
     * The seven keys V2 seeded. Held as a literal <em>and</em> compared to the catalogue, because
     * the two failures are different: a key added to the catalogue and not to this list means the
     * boundary grew while the test did not, and the comparison is what says so.
     */
    private static final Set<String> MONEY_KEYS = Set.of(
            "ratecard:read", "ratecard:manage",
            "billing:read", "billing:budget", "billing:expense", "billing:invoice",
            "billing:issue");

    private static final UUID ACME = UUID.fromString("f8000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("f8001000-0000-4000-8000-000000000001");

    private static final UUID LEAD_ROLE = UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final UUID ENGINEER_ROLE = UUID.fromString("10000000-0000-4000-8000-000000000004");
    private static final UUID ACCOUNT_MANAGER_ROLE =
            UUID.fromString("10000000-0000-4000-8000-000000000010");

    private static final UUID DANA = UUID.fromString("f8002000-0000-4000-8000-000000000001");
    private static final UUID LEO = UUID.fromString("f8002000-0000-4000-8000-000000000002");

    /** The Account Manager arm, added by 019 T4 — the role that owns the client and not its price. */
    private static final UUID ANNA = UUID.fromString("f8002000-0000-4000-8000-000000000003");

    private static final UUID DANA_GRANT = UUID.fromString("f8003000-0000-4000-8000-000000000001");
    private static final UUID LEO_GRANT = UUID.fromString("f8003000-0000-4000-8000-000000000002");
    private static final UUID ANNA_GRANT = UUID.fromString("f8003000-0000-4000-8000-000000000003");

    private static final UUID STORY = UUID.fromString("f8004000-0000-4000-8000-000000000001");

    private static final LocalDate DAY = LocalDate.of(2026, 9, 7);

    @Autowired
    private TimeEntryService entries;

    @Autowired
    private TimesheetService timesheets;

    @Autowired
    private PermissionEvaluator permissions;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();
        tx.executeWithoutResult(status -> {
            company(ACME, "money-acme", "Acme", now);
            activeUser(DANA, "dana@money.test", "Dana Engineer", now);
            activeUser(LEO, "leo@money.test", "Leo Lead", now);
            activeUser(ANNA, "anna@money.test", "Anna Konto", now);
        });
        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(PROJECT, ACME, "MNY", "Storefront", now);
            member(ACME, DANA, "Dana Engineer", now);
            member(ACME, LEO, "Leo Lead", now);
            member(ACME, ANNA, "Anna Konto", now);
        }));
        grantOnProject(DANA_GRANT, ACME, ENGINEER_ROLE, DANA, PROJECT);
        grantOnProject(LEO_GRANT, ACME, LEAD_ROLE, LEO, PROJECT);
        // COMPANY scope, because Account Manager is a company role and granting it on one project
        // would be a grant the seed cannot produce — §A1 draws that boundary and this arm is about
        // the role as it actually exists.
        grantInCompany(ANNA_GRANT, ACME, ACCOUNT_MANAGER_ROLE, ANNA);
        seedProjectDefaults(ACME, PROJECT);
        wipeTime(ACME);
        issueFact(ACME, PROJECT, STORY, "MNY-1", "Guest checkout", "STORY", null,
                new BigDecimal("16.00"));
    }

    @AfterEach
    void putEverythingBack() throws SQLException {
        wipeTime(ACME);
    }

    @Test
    @DisplayName("every money key in the catalogue is covered by this test")
    void everyMoneyKeyInTheCatalogueIsCoveredByThisTest() throws SQLException {
        Set<String> fromCatalogue = new LinkedHashSet<>();
        try (Connection connection = superuserConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT key FROM permission
                         WHERE key LIKE 'billing:%' OR key LIKE 'ratecard:%'
                         ORDER BY key
                        """);
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                fromCatalogue.add(rows.getString(1));
            }
        }

        assertThat(fromCatalogue)
                .as("a money key was added to the catalogue and this test did not move with it — "
                        + "the boundary grew and nothing here is asserting the new key's absence")
                .isEqualTo(MONEY_KEYS);
    }

    @Test
    @DisplayName("a Test Engineer holds time:log and not one money key")
    void aTestEngineerHoldsTimeLogAndNotOneMoneyKey() {
        Set<String> held = heldBy(DANA);

        assertThat(held).contains("time:log");
        assertThat(held)
                .as("story T1: a Test Engineer logs hours and cannot see what they cost")
                .doesNotContainAnyElementsOf(MONEY_KEYS);
    }

    @Test
    @DisplayName("the time surface carries no money word anywhere in what it serves")
    void aTestEngineerLogsHoursAndSeesNoCostAnywhereInTheResponse()
            throws JsonProcessingException {

        scoped(() -> allowed(entries.log(DANA,
                new TimeEntryInput(TimeSubjectType.ISSUE, "MNY-1", DAY, 4.0, "card form"))));
        TimesheetPeriodView period = scoped(() -> allowed(timesheets.current(DANA, DAY)));

        String body = json.writeValueAsString(period).toLowerCase(Locale.ROOT);

        // Over the SERIALIZED body rather than the record's components, because the failure this
        // guards against is a field reaching the wire — a member renamed on the way out, or a
        // nested view somebody added below the one this test names, is still a money word served.
        assertThat(body)
                .as("the time surface must never carry a money word: what an hour is worth is M8's, "
                        + "gated on billing:* and ratecard:read")
                .doesNotContain("rate")
                .doesNotContain("cost")
                .doesNotContain("amount")
                .doesNotContain("currency")
                .doesNotContain("invoice")
                .doesNotContain("budget");
        assertThat(body).contains("\"hours\":4.0");
    }

    @Test
    @DisplayName("016 D-f — a Test Lead cannot approve a timesheet, and this asserts it")
    void aTestLeadCannotApproveATimesheet() {
        scoped(() -> allowed(entries.log(DANA,
                new TimeEntryInput(TimeSubjectType.MEETING, null, DAY, 1.0, null))));
        UUID period = scoped(() -> allowed(timesheets.current(DANA, DAY))).id();
        assertThat(scoped(() -> allowed(timesheets.submit(DANA, period))))
                .isInstanceOf(TimesheetService.Outcome.Decided.class);

        assertThat(heldBy(LEO))
                .as("V2's own comment above the Test Lead grant flags this for M6 by name: a "
                        + "project staffed with a Test Lead and no Project Manager can only have "
                        + "its timesheets approved by Company Admin. That is a consequence of D3 "
                        + "that was decided and recorded, and widening it here would widen a grant "
                        + "nobody decided to widen.")
                .contains("time:log")
                .doesNotContain("time:approve");

        assertThat(scoped(() ->
                timesheets.decide(LEO, period, new TimesheetDecisionInput("APPROVE", null))))
                .isInstanceOf(GateResult.Denied.class);
        assertThat(scoped(() -> timesheets.queue(LEO, DAY)))
                .isInstanceOf(GateResult.Denied.class);
    }

    /**
     * The Account Manager arm, added by 019 T4.
     *
     * <p>This class's four existing arms are untouched, and
     * {@link #everyMoneyKeyInTheCatalogueIsCoveredByThisTest} above all: it is what makes the
     * boundary grow-proof, and M8 inheriting a standing test it cannot quietly widen was the whole
     * reason 016 wrote this file before billing existed. What M8 adds is a fifth arm and not a
     * rewrite of any of them.
     *
     * <p>The behavioural half — that this person is <em>refused</em> every billing path — is
     * {@code billing.BillingMoneyBoundaryTest}'s, because the services that do the refusing are in
     * that module and this one may not import it. What is asserted here is the grant, which is what
     * {@code time} can see and what every refusal over there rests on.
     */
    @Test
    @DisplayName("story M2 — an Account Manager knows the client and the hours and never a rate")
    void anAccountManagerKnowsTheClientAndTheHoursAndNeverARate() {
        Set<String> held = heldBy(ANNA);

        assertThat(held).contains("client:read");
        assertThat(held)
                .as("the Account Manager owns the client relationship and never sees its price — "
                        + "roles spec §A3's Money table, asserted rather than read")
                .doesNotContainAnyElementsOf(MONEY_KEYS);
        assertThat(held)
                .as("and they do not hold time:approve either — a period is approved by a Project "
                        + "Manager or a Company Admin, not by the person who owns the client "
                        + "relationship. 019 D-f rests on that: the approval an invoice entry "
                        + "reports is the decision on somebody's FORTNIGHT, and this is one of the "
                        + "roles it is demonstrably not.")
                .doesNotContain("time:approve");
        assertThat(held)
                .as("nor project:read, which at COMPANY scope would confer it over every project "
                        + "in the company present and future — V2's own comment above this grant")
                .doesNotContain("project:read");
    }

    // =====================================================================

    private Set<String> heldBy(UUID userId) {
        return scoped(() -> tx.execute(status -> permissions.evaluate(userId, PROJECT).keys()));
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
