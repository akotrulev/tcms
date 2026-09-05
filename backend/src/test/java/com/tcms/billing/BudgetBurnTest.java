package com.tcms.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.tcms.project.GateResult;
import com.tcms.tenancy.CompanyContext;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

/**
 * Budget burn, end to end — and <strong>the assertion a Test Engineer's whole view of this module
 * rests on</strong> (019 D-h).
 *
 * <p>{@code GET /projects/{projectId}/budget-burn} is gated on {@code project:read} rather than on a
 * money key, and the only reason that is safe is that {@link BudgetBurnView} has no amount member
 * and no currency member at all. {@link #theProjectScopeBudgetReadCarriesNoMoneyWord} is the check,
 * and it reads the <strong>serialized body</strong> rather than the record's components for
 * {@code TimeMoneyBoundaryTest}'s reason: the failure it guards against is a field reaching the wire
 * — a member renamed on the way out, or a nested view added below the ones this test names.
 *
 * <p>It also drives the rate ladder against real rows, which {@code PricingTest} deliberately does
 * not: the roles it prices by come from {@code identity}'s own grant projections through
 * {@link PersonRoles}, and a unit test of the ladder cannot see that resolution at all.
 *
 * <p><strong>UUID prefix {@code 51}, verified against the tree rather than assumed</strong> — 018
 * records what assuming costs.
 */
@AutoConfigureMockMvc
@DisplayName("Budget burn — a percentage, hours, and not one money word (019 D-h)")
class BudgetBurnTest extends BillingTestBase {

    private static final UUID COMPANY_ADMIN_ROLE =
            UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID TEST_ENGINEER_ROLE =
            UUID.fromString("10000000-0000-4000-8000-000000000004");
    private static final UUID FINANCE_ROLE =
            UUID.fromString("10000000-0000-4000-8000-000000000011");

    private static final UUID ACME = UUID.fromString("51000000-0000-4000-8000-000000000001");

    private static final UUID MONEY_PROJECT =
            UUID.fromString("51001000-0000-4000-8000-000000000001");
    private static final UUID HOURS_PROJECT =
            UUID.fromString("51001000-0000-4000-8000-000000000002");
    private static final UUID INTERNAL_PROJECT =
            UUID.fromString("51001000-0000-4000-8000-000000000003");

    private static final UUID DANA_TEST_ENGINEER =
            UUID.fromString("51002000-0000-4000-8000-000000000001");
    private static final UUID FIN = UUID.fromString("51002000-0000-4000-8000-000000000002");
    private static final UUID ADMIN = UUID.fromString("51002000-0000-4000-8000-000000000003");

    private static final UUID CLIENT = UUID.fromString("51005000-0000-4000-8000-000000000001");
    private static final UUID PERIOD = UUID.fromString("51006000-0000-4000-8000-000000000001");
    private static final UUID OPEN_PERIOD = UUID.fromString("51006000-0000-4000-8000-000000000002");
    private static final UUID ISSUE = UUID.fromString("51007000-0000-4000-8000-000000000001");

    private static final LocalDate JANUARY = LocalDate.of(2026, 1, 1);
    private static final LocalDate A_WORKED_DAY = LocalDate.of(2026, 6, 15);

    @Autowired
    private BudgetService budgets;

    @Autowired
    private RateCardService rateCards;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();
        tx.executeWithoutResult(status -> {
            company(ACME, "billing-acme", "Acme", now);
            activeUser(DANA_TEST_ENGINEER, "dana@billing.test", "Dana Test", now);
            activeUser(FIN, "fin@billing.test", "Fin Ance", now);
            activeUser(ADMIN, "admin@billing.test", "Ada Admin", now);
        });
        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(MONEY_PROJECT, ACME, "BILM", "Acme Storefront", now);
            seedProject(HOURS_PROJECT, ACME, "BILH", "Acme Portal", now);
            seedProject(INTERNAL_PROJECT, ACME, "BILI", "Internal Platform", now);
            member(ACME, DANA_TEST_ENGINEER, "Dana Test", now);
            member(ACME, FIN, "Fin Ance", now);
            member(ACME, ADMIN, "Ada Admin", now);
        }));
        grantInCompany(UUID.fromString("51003000-0000-4000-8000-000000000001"), ACME,
                COMPANY_ADMIN_ROLE, ADMIN);
        grantInCompany(UUID.fromString("51003000-0000-4000-8000-000000000002"), ACME,
                FINANCE_ROLE, FIN);
        grantOnProject(UUID.fromString("51003000-0000-4000-8000-000000000003"), ACME,
                TEST_ENGINEER_ROLE, DANA_TEST_ENGINEER, MONEY_PROJECT);
        grantOnProject(UUID.fromString("51003000-0000-4000-8000-000000000004"), ACME,
                TEST_ENGINEER_ROLE, DANA_TEST_ENGINEER, HOURS_PROJECT);
        seedProjectDefaults(ACME, MONEY_PROJECT);
        seedProjectDefaults(ACME, HOURS_PROJECT);
        seedProjectDefaults(ACME, INTERNAL_PROJECT);
        client(ACME, CLIENT, "Acme Retail");
    }

    @AfterEach
    void wipe() throws SQLException {
        wipeBilling(ACME);
    }

    @Test
    @DisplayName("019 D-h — the project-scope budget read carries no money word, on either basis")
    void theProjectScopeBudgetReadCarriesNoMoneyWord() throws JsonProcessingException, SQLException {
        setBudget(MONEY_PROJECT, BudgetBasis.MONEY, "40000.00", "EUR");
        approvedHours(MONEY_PROJECT, "10.00");

        String body = json.writeValueAsString(
                allowed(scoped(() -> budgets.burn(DANA_TEST_ENGINEER, MONEY_PROJECT))))
                .toLowerCase(Locale.ROOT);

        /*
          Over the SERIALIZED body rather than the record's components, for TimeMoneyBoundaryTest's
          reason: the failure this guards against is a field reaching the wire — a member renamed on
          the way out, or a nested view added below the one this test names.
        */
        assertThat(body)
                .as("a MONEY-basis budget must reach a project:read caller as a percentage and "
                        + "nothing else — project-overview.html's own hint reads \"hours, no "
                        + "rates\", and this endpoint is the only one in the module that role may "
                        + "call")
                .doesNotContain("rate")
                .doesNotContain("cost")
                .doesNotContain("amount")
                .doesNotContain("currency")
                .doesNotContain("eur")
                .doesNotContain("invoice")
                .doesNotContain("40000");
        assertThat(body).contains("\"burnpercent\"");
    }

    @Test
    @DisplayName("a MONEY budget with no rate card burns an UNKNOWN percentage, never zero")
    void aMoneyBudgetWithoutARateCardBurnsAnUnknownPercentage() throws SQLException {
        setBudget(MONEY_PROJECT, BudgetBasis.MONEY, "40000.00", "EUR");
        attach(ACME, MONEY_PROJECT, CLIENT);
        approvedHours(MONEY_PROJECT, "10.00");

        BudgetBurnView burn = allowed(scoped(() -> budgets.burn(DANA_TEST_ENGINEER, MONEY_PROJECT)));

        assertThat(burn.basis()).isEqualTo(BudgetBasis.MONEY);
        assertThat(burn.budgetHours()).as("the ceiling is an amount, and the amount is Finance's")
                .isNull();
        assertThat(burn.approvedHours()).isEqualByComparingTo("10.00");
        assertThat(burn.burnPercent())
                .as("""
                        A zero here would read as "nothing spent" on a project that has spent ten \
                        hours. The client has no rate card, so what those hours are worth is \
                        unknown — and unknown is a different answer from none.""")
                .isNull();
    }

    @Test
    @DisplayName("with a rate card the burn is priced through the ROLE rung the person holds")
    void aMoneyBudgetWithARateCardBurnsThroughTheRoleRung() throws SQLException {
        attach(ACME, MONEY_PROJECT, CLIENT);
        setRateCard("EUR", new RateCardEntryInput(
                RateCardScope.ROLE, null, TEST_ENGINEER_ROLE, new BigDecimal("100.00"), JANUARY));
        setBudget(MONEY_PROJECT, BudgetBasis.MONEY, "4000.00", "EUR");
        approvedHours(MONEY_PROJECT, "10.00");

        BudgetBurnView burn = allowed(scoped(() -> budgets.burn(DANA_TEST_ENGINEER, MONEY_PROJECT)));
        assertThat(burn.burnPercent())
                .as("ten hours at the Test Engineer rate is 1000 of a 4000 ceiling")
                .isEqualByComparingTo("25.0");

        // The same figure with the money on it, for the caller who may see money.
        BudgetView money = allowed(scoped(() -> budgets.list(FIN, null))).stream()
                .filter(row -> row.projectId().equals(MONEY_PROJECT))
                .findFirst()
                .orElseThrow();
        assertThat(money.pricedToDate().amount()).isEqualByComparingTo("1000.00");
        assertThat(money.pricedToDate().currency()).isEqualTo("EUR");
        assertThat(money.amountMoney().amount()).isEqualByComparingTo("4000.00");
        assertThat(money.amountHours()).isNull();
        assertThat(money.client()).isNotNull();
        assertThat(money.client().name()).isEqualTo("Acme Retail");
    }

    @Test
    @DisplayName("an HOURS budget burns past 100% and is not clamped")
    void anHoursBudgetIsNotClampedAtOneHundred() throws SQLException {
        setBudget(HOURS_PROJECT, BudgetBasis.HOURS, "8.00", null);
        approvedHours(HOURS_PROJECT, "10.00");

        BudgetBurnView burn = allowed(scoped(() -> budgets.burn(DANA_TEST_ENGINEER, HOURS_PROJECT)));

        assertThat(burn.budgetHours()).isEqualByComparingTo("8.00");
        assertThat(burn.burnPercent())
                .as("billing.html draws Bergstrom Portal at 102% in a danger tone, and clamping "
                        + "would hide the one row that screen exists to surface")
                .isEqualByComparingTo("125.0");
    }

    @Test
    @DisplayName("a project with no budget answers a null basis, which is a state and not an error")
    void aProjectWithNoBudgetIsNotAnError() throws SQLException {
        approvedHours(HOURS_PROJECT, "10.00");

        BudgetBurnView burn = allowed(scoped(() -> budgets.burn(DANA_TEST_ENGINEER, HOURS_PROJECT)));

        assertThat(burn.basis()).isNull();
        assertThat(burn.budgetHours()).isNull();
        assertThat(burn.burnPercent()).isNull();
        assertThat(burn.approvedHours())
                .as("the hours are a fact about the project whether or not anybody set a ceiling")
                .isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("019 D-e — pending hours and non-issue hours are not in the burn at all")
    void onlyApprovedIssueHoursAreCounted() throws SQLException {
        setBudget(HOURS_PROJECT, BudgetBasis.HOURS, "100.00", null);
        approvedHours(HOURS_PROJECT, "10.00");

        period(ACME, OPEN_PERIOD, DANA_TEST_ENGINEER, LocalDate.of(2026, 6, 16),
                LocalDate.of(2026, 6, 30), "SUBMITTED", null, null);
        issueEntry(ACME, UUID.fromString("51008000-0000-4000-8000-000000000002"), OPEN_PERIOD,
                DANA_TEST_ENGINEER, HOURS_PROJECT, ISSUE, LocalDate.of(2026, 6, 20),
                new BigDecimal("7.00"));
        nonIssueEntry(ACME, UUID.fromString("51008000-0000-4000-8000-000000000003"), PERIOD,
                DANA_TEST_ENGINEER, "MEETING", A_WORKED_DAY, new BigDecimal("4.00"));

        BudgetBurnView burn = allowed(scoped(() -> budgets.burn(DANA_TEST_ENGINEER, HOURS_PROJECT)));

        assertThat(burn.approvedHours())
                .as("""
                        Approved and pending are two numbers and nothing in this product sums them \
                        (016 D-d), and ck_time_entry_subject_shape makes project_id NULL for a \
                        MEETING — so four of the five subject kinds can never reach a budget at \
                        all. Neither exclusion is a filter this module chose to apply.""")
                .isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("019 D-p — an internal project with a budget burns perfectly well and has no client")
    void anInternalProjectWithABudgetIsNotADeficientOne() throws SQLException {
        setBudget(INTERNAL_PROJECT, BudgetBasis.HOURS, "200.00", null);

        BudgetView internal = allowed(scoped(() -> budgets.list(FIN, null))).stream()
                .filter(row -> row.projectId().equals(INTERNAL_PROJECT))
                .findFirst()
                .orElseThrow();

        assertThat(internal.client())
                .as("billing.html renders it as None (internal), in the same table as three billed "
                        + "projects, with no warning tone and no call to action")
                .isNull();
        assertThat(internal.pricedToDate())
                .as("no client means no rate card, so the money value is unknown rather than zero")
                .isNull();
        assertThat(internal.amountHours()).isEqualByComparingTo("200.00");
        assertThat(internal.burnPercent()).isEqualByComparingTo("0.0");
    }

    // =====================================================================

    private void setBudget(UUID projectId, BudgetBasis basis, String amount, String currency) {
        written(scoped(() -> budgets.set(
                ADMIN, projectId, new BudgetWriteRequest(basis, new BigDecimal(amount), currency))));
    }

    private void setRateCard(String currency, RateCardEntryInput... entries) {
        written(scoped(() -> rateCards.create(
                ADMIN, new RateCardWriteRequest(CLIENT, currency, List.of(entries)))));
    }

    /** One approved fortnight with one issue-subject entry on it. */
    private void approvedHours(UUID projectId, String hours) throws SQLException {
        period(ACME, PERIOD, DANA_TEST_ENGINEER, LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 15), "APPROVED", Instant.parse("2026-06-16T09:00:00Z"), ADMIN);
        issueEntry(ACME, UUID.fromString("51008000-0000-4000-8000-000000000001"), PERIOD,
                DANA_TEST_ENGINEER, projectId, ISSUE, A_WORKED_DAY, new BigDecimal(hours));
    }

    private <T> T scoped(Supplier<T> call) {
        try (var ignored = CompanyContext.open(ACME)) {
            return tx.execute(status -> call.get());
        }
    }

    private static <T> T allowed(GateResult<T> gate) {
        assertThat(gate).isInstanceOf(GateResult.Allowed.class);
        return ((GateResult.Allowed<T>) gate).value();
    }

    private static <T> T written(GateResult<Outcome<T>> gate) {
        Outcome<T> outcome = allowed(gate);
        assertThat(outcome).isInstanceOf(Outcome.Written.class);
        return ((Outcome.Written<T>) outcome).value();
    }
}
