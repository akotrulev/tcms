package com.tcms.billing;

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
 * {@code client.html}'s <em>Hours and invoices to date</em> table, and {@code billing.html}'s four
 * tiles.
 *
 * <p>The permission half of the client summary is {@code BillingMoneyBoundaryTest}'s — this class is
 * about what the rows and the tiles actually <em>say</em>: that a month's hours are matched to the
 * invoice that covers them, that a draft shows as a draft with no amount, and that a company with a
 * EUR client and a CHF client gets two figures rather than one wrong one (019 D-l).
 *
 * <p><strong>UUID prefix {@code 53}, verified against the tree rather than assumed.</strong>
 */
@AutoConfigureMockMvc
@DisplayName("Client billing and the company summary — a row per month, and money per currency")
class ClientBillingSummaryTest extends BillingTestBase {

    private static final UUID COMPANY_ADMIN_ROLE =
            UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID TEST_ENGINEER_ROLE =
            UUID.fromString("10000000-0000-4000-8000-000000000004");

    private static final UUID ACME = UUID.fromString("53000000-0000-4000-8000-000000000001");
    private static final UUID EUR_PROJECT = UUID.fromString("53001000-0000-4000-8000-000000000001");
    private static final UUID CHF_PROJECT = UUID.fromString("53001000-0000-4000-8000-000000000002");

    private static final UUID ADMIN = UUID.fromString("53002000-0000-4000-8000-000000000001");
    private static final UUID MIRA = UUID.fromString("53002000-0000-4000-8000-000000000002");

    /**
     * A second person for the CHF client's hours, and it has to be a second person:
     * {@code uq_timesheet_period} is {@code (company_id, person_id, starts_on)}, so one fortnight
     * per person, and a fixture that gave Mira a second period over the same days would be writing
     * a row {@code time} could not produce.
     */
    private static final UUID JUN = UUID.fromString("53002000-0000-4000-8000-000000000003");

    private static final UUID EUR_CLIENT = UUID.fromString("53005000-0000-4000-8000-000000000001");
    private static final UUID CHF_CLIENT = UUID.fromString("53005000-0000-4000-8000-000000000002");

    private static final UUID EUR_PERIOD = UUID.fromString("53006000-0000-4000-8000-000000000001");
    private static final UUID CHF_PERIOD = UUID.fromString("53006000-0000-4000-8000-000000000002");

    private static final UUID EUR_ISSUE = UUID.fromString("53007000-0000-4000-8000-000000000001");
    private static final UUID CHF_ISSUE = UUID.fromString("53007000-0000-4000-8000-000000000002");

    private static final UUID EUR_ENTRY = UUID.fromString("53008000-0000-4000-8000-000000000001");
    private static final UUID CHF_ENTRY = UUID.fromString("53008000-0000-4000-8000-000000000002");

    private static final LocalDate JANUARY = LocalDate.of(2026, 1, 1);
    private static final Instant DECIDED = Instant.parse("2026-01-20T09:00:00Z");

    /**
     * The window everything here is worked in — <strong>this month</strong>, because
     * {@code draftableNow} is computed over the calendar month containing the day asked about and a
     * fixture pinned to a literal month would only exercise it in that month.
     */
    private LocalDate worked;

    private LocalDate monthStart;

    private LocalDate monthEnd;

    @Autowired
    private ClientBillingService clientBilling;

    @Autowired
    private BillingSummaryService summaries;

    @Autowired
    private InvoiceService invoices;

    @Autowired
    private RateCardService rateCards;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();
        LocalDate today = LocalDate.now();
        monthStart = today.withDayOfMonth(1);
        monthEnd = today.withDayOfMonth(today.lengthOfMonth());
        // Never the 1st or the last day, so an off-by-one in a BETWEEN would still put the hour in
        // the month rather than at a boundary where two wrong answers look the same.
        worked = monthStart.plusDays(9);

        tx.executeWithoutResult(status -> {
            company(ACME, "billing-clientsum", "Acme", now);
            activeUser(ADMIN, "admin@clientsum.test", "Ada Admin", now);
            activeUser(MIRA, "mira@clientsum.test", "Mira Sund", now);
            activeUser(JUN, "jun@clientsum.test", "Jun Park", now);
        });
        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(EUR_PROJECT, ACME, "CSE", "Acme Storefront", now);
            seedProject(CHF_PROJECT, ACME, "CSC", "Bergstrom Portal", now);
            member(ACME, ADMIN, "Ada Admin", now);
            member(ACME, MIRA, "Mira Sund", now);
            member(ACME, JUN, "Jun Park", now);
        }));
        grantInCompany(UUID.fromString("53003000-0000-4000-8000-000000000001"), ACME,
                COMPANY_ADMIN_ROLE, ADMIN);
        grantOnProject(UUID.fromString("53003000-0000-4000-8000-000000000002"), ACME,
                TEST_ENGINEER_ROLE, MIRA, EUR_PROJECT);
        grantOnProject(UUID.fromString("53003000-0000-4000-8000-000000000003"), ACME,
                TEST_ENGINEER_ROLE, JUN, CHF_PROJECT);
        seedProjectDefaults(ACME, EUR_PROJECT);
        seedProjectDefaults(ACME, CHF_PROJECT);

        wipeBilling(ACME);
        client(ACME, EUR_CLIENT, "Acme Retail");
        client(ACME, CHF_CLIENT, "Bergstrom Health");
        attach(ACME, EUR_PROJECT, EUR_CLIENT);
        attach(ACME, CHF_PROJECT, CHF_CLIENT);
        issueFact(ACME, EUR_PROJECT, EUR_ISSUE, "CSE-1", "Card capture");
        issueFact(ACME, CHF_PROJECT, CHF_ISSUE, "CSC-1", "Referral intake");

        period(ACME, EUR_PERIOD, MIRA, monthStart, monthEnd, "APPROVED", DECIDED, ADMIN);
        issueEntry(ACME, EUR_ENTRY, EUR_PERIOD, MIRA, EUR_PROJECT, EUR_ISSUE, worked,
                new BigDecimal("10.00"));
    }

    @AfterEach
    void wipe() throws SQLException {
        wipeBilling(ACME);
    }

    @Test
    @DisplayName("a month's hours with no invoice are a row, and the invoice columns are null")
    void unbilledHoursAreStillARow() {
        ClientBillingSummaryView summary = allowed(scoped(() -> clientBilling.read(ADMIN, EUR_CLIENT)));

        assertThat(summary.approvedHoursToDate()).isEqualByComparingTo("10.00");
        assertThat(summary.approvedHoursThisQuarter()).isEqualByComparingTo("10.00");
        assertThat(summary.invoiceCount()).isZero();
        assertThat(summary.lastIssuedAt()).isNull();
        assertThat(summary.rows()).singleElement().satisfies(row -> {
            assertThat(row.projectName()).isEqualTo("Acme Storefront");
            assertThat(row.approvedHours()).isEqualByComparingTo("10.00");
            assertThat(row.invoiceId())
                    .as("hours nobody has raised an invoice for are the ordinary state of the "
                            + "current month, and the row is how a reader sees there is one to raise")
                    .isNull();
            assertThat(row.invoiceStatus()).isNull();
            assertThat(row.amount()).isNull();
        });
    }

    @Test
    @DisplayName("a draft names itself and carries no amount; an issued invoice carries both")
    void aDraftIsARowWithNoAmount() {
        setCard(EUR_CLIENT, "EUR", "78.00");
        UUID drafted = written(scoped(() -> invoices.draft(ADMIN, new InvoiceDraftInput(
                EUR_CLIENT, monthStart, monthEnd, null)))).invoice().id();

        ClientBillingSummaryView asDraft =
                allowed(scoped(() -> clientBilling.read(ADMIN, EUR_CLIENT)));
        assertThat(asDraft.rows()).singleElement().satisfies(row -> {
            assertThat(row.invoiceStatus()).isEqualTo(InvoiceStatus.DRAFT);
            assertThat(row.invoiceNumber())
                    .as("the mockup renders the word Draft in that column, which is only possible "
                            + "because the status is a field beside the number")
                    .isNull();
            assertThat(row.amount())
                    .as("a draft stores no lines (019 D-c), so there is no amount to show — and "
                            + "recomputing one would put a figure that moves on a table whose "
                            + "other rows are what was sent")
                    .isNull();
        });
        assertThat(asDraft.invoiceCount())
                .as("a draft is a working document, not an invoice anybody has raised — which is "
                        + "why this reads correctly beside lastIssuedAt")
                .isZero();

        written(scoped(() -> invoices.issue(ADMIN, drafted)));

        ClientBillingSummaryView asIssued =
                allowed(scoped(() -> clientBilling.read(ADMIN, EUR_CLIENT)));
        assertThat(asIssued.invoiceCount()).isEqualTo(1);
        assertThat(asIssued.lastIssuedAt()).isNotNull();
        assertThat(asIssued.rows()).singleElement().satisfies(row -> {
            assertThat(row.invoiceStatus()).isEqualTo(InvoiceStatus.ISSUED);
            assertThat(row.invoiceNumber()).isNotNull();
            assertThat(row.amount().amount()).isEqualByComparingTo("780.00");
            assertThat(row.amount().currency()).isEqualTo("EUR");
        });
    }

    @Test
    @DisplayName("a client this company does not have is a 404, not an empty summary")
    void anUnknownClientIsUnreachable() {
        assertThat(scoped(() -> clientBilling.read(ADMIN, UUID.randomUUID())))
                .as("a client this company does not have is not a client with no invoices, and "
                        + "folding the two would make this an oracle for ids in another tenancy")
                .isInstanceOf(GateResult.Unreachable.class);
    }

    @Test
    @DisplayName("019 D-l — a EUR client and a CHF client are two figures, never one summed one")
    void theCompanyTotalIsOneFigurePerCurrency() throws SQLException {
        period(ACME, CHF_PERIOD, JUN, monthStart, monthEnd, "APPROVED", DECIDED, ADMIN);
        issueEntry(ACME, CHF_ENTRY, CHF_PERIOD, JUN, CHF_PROJECT, CHF_ISSUE, worked,
                new BigDecimal("4.00"));
        setCard(EUR_CLIENT, "EUR", "78.00");
        setCard(CHF_CLIENT, "CHF", "120.00");

        BillingSummaryView summary = allowed(scoped(() -> summaries.summary(ADMIN, worked)));

        assertThat(summary.draftableNow())
                .as("""
                        billing.html draws a single €52.4k tile while its own rate-card table \
                        carries a EUR client and a CHF client two rows apart. That is a \
                        single-currency render of a multi-currency company, and one number summed \
                        across two currencies is wrong in both of them — so the screen renders one \
                        line per currency where the mockup renders one figure.""")
                .extracting(Money::currency)
                .containsExactly("CHF", "EUR");
        assertThat(summary.draftableNow())
                .filteredOn(money -> money.currency().equals("EUR"))
                .singleElement()
                .satisfies(money -> assertThat(money.amount()).isEqualByComparingTo("780.00"));
        assertThat(summary.draftableNow())
                .filteredOn(money -> money.currency().equals("CHF"))
                .singleElement()
                .satisfies(money -> assertThat(money.amount()).isEqualByComparingTo("480.00"));

        assertThat(summary.invoicedYearToDate())
                .as("an empty array means nothing has been issued this year, which is the state a "
                        + "new company is in — never a zero in a currency nobody uses")
                .isEmpty();
        assertThat(summary.clientsBilled()).isZero();
        assertThat(summary.unapprovedHours()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("issuing moves hours out of draftableNow and into invoicedYearToDate")
    void issuingMovesTheMoneyBetweenTiles() {
        setCard(EUR_CLIENT, "EUR", "78.00");
        UUID drafted = written(scoped(() -> invoices.draft(ADMIN, new InvoiceDraftInput(
                EUR_CLIENT, monthStart, monthEnd, null)))).invoice().id();

        assertThat(allowed(scoped(() -> summaries.summary(ADMIN, worked))).draftableNow())
                .as("an OPEN draft reserves nothing, so the hours are still draftable — 019 D-c "
                        + "stated as a tile")
                .singleElement()
                .satisfies(money -> assertThat(money.amount()).isEqualByComparingTo("780.00"));

        written(scoped(() -> invoices.issue(ADMIN, drafted)));

        BillingSummaryView after = allowed(scoped(() -> summaries.summary(ADMIN, worked)));
        assertThat(after.draftableNow())
                .as("the same NOT EXISTS the draft's own lines are computed through, so the tile "
                        + "and the document cannot disagree")
                .isEmpty();
        assertThat(after.invoicedYearToDate()).singleElement().satisfies(money -> {
            assertThat(money.amount()).isEqualByComparingTo("780.00");
            assertThat(money.currency()).isEqualTo("EUR");
        });
        assertThat(after.clientsBilled()).isEqualTo(1);
    }

    @Test
    @DisplayName("unapproved hours are the tile's own figure, and they are not a currency")
    void unapprovedHoursAreAPlainNumber() throws SQLException {
        period(ACME, CHF_PERIOD, JUN, monthStart, monthEnd, "SUBMITTED", null, null);
        issueEntry(ACME, CHF_ENTRY, CHF_PERIOD, JUN, CHF_PROJECT, CHF_ISSUE, worked,
                new BigDecimal("6.00"));

        BillingSummaryView summary = allowed(scoped(() -> summaries.summary(ADMIN, worked)));

        assertThat(summary.unapprovedHours())
                .as("billing.html labels it 'excluded from every draft', which is exactly what "
                        + "it is — and hours have no currency, so it is a plain number")
                .isEqualByComparingTo("6.00");
        assertThat(summary.draftableNow())
                .as("and no card exists for either client, so nothing is priceable — which is a "
                        + "state and not a zero in some currency")
                .isEmpty();
    }

    // =====================================================================

    private void setCard(UUID clientId, String currency, String rate) {
        written(scoped(() -> rateCards.create(ADMIN, new RateCardWriteRequest(
                clientId, currency, List.of(new RateCardEntryInput(
                        RateCardScope.ALL_ROLES, null, null, new BigDecimal(rate), JANUARY))))));
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
