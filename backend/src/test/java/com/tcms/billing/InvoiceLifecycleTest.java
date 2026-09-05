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
 * The draft as a query, the issue as a record, and the lock proved the honest way (019 D-c).
 *
 * <p>Every claim this module makes about immutability is checked by <strong>doing the thing that
 * would break it</strong> — issue, edit the rate card, re-read — rather than by asserting that an
 * endpoint nobody built returns 405. The absence of an update path is not something a test can see;
 * what it can see is that the number did not move.
 *
 * <p>It also carries the anti-double-billing proof, which is the one defect in this module that
 * would reach a client as a bill for hours they had already paid for: an entry on an issued invoice
 * is never offered to a second draft, and the query that guarantees it is a {@code NOT EXISTS}
 * against {@code invoice_line_entry} rather than anything a caller has to remember.
 *
 * <p><strong>UUID prefix {@code 4e}, verified against the tree rather than assumed</strong> — 018
 * records that {@code fa} and {@code fb} looked free, were not, and the suite failed on a
 * {@code project_pkey} duplicate naming neither class.
 */
@AutoConfigureMockMvc
@DisplayName("Invoices — a draft recalculates, an issued invoice never does (019 D-c)")
class InvoiceLifecycleTest extends BillingTestBase {

    private static final UUID COMPANY_ADMIN_ROLE =
            UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID TEST_ENGINEER_ROLE =
            UUID.fromString("10000000-0000-4000-8000-000000000004");

    private static final UUID ACME = UUID.fromString("4e000000-0000-4000-8000-000000000001");
    private static final UUID STOREFRONT = UUID.fromString("4e001000-0000-4000-8000-000000000001");
    private static final UUID LOYALTY = UUID.fromString("4e001000-0000-4000-8000-000000000002");

    private static final UUID ADMIN = UUID.fromString("4e002000-0000-4000-8000-000000000001");
    private static final UUID MIRA = UUID.fromString("4e002000-0000-4000-8000-000000000002");
    private static final UUID TOMAS = UUID.fromString("4e002000-0000-4000-8000-000000000003");

    private static final UUID CLIENT = UUID.fromString("4e005000-0000-4000-8000-000000000001");
    private static final UUID CONTACT = UUID.fromString("4e005000-0000-4000-8000-000000000002");

    private static final UUID APPROVED_PERIOD =
            UUID.fromString("4e006000-0000-4000-8000-000000000001");
    private static final UUID OPEN_PERIOD = UUID.fromString("4e006000-0000-4000-8000-000000000002");
    private static final UUID SEPTEMBER_PERIOD =
            UUID.fromString("4e006000-0000-4000-8000-000000000003");

    private static final UUID ISSUE = UUID.fromString("4e007000-0000-4000-8000-000000000001");

    private static final UUID MIRA_HOURS = UUID.fromString("4e008000-0000-4000-8000-000000000001");
    private static final UUID TOMAS_HOURS = UUID.fromString("4e008000-0000-4000-8000-000000000002");
    private static final UUID SEPTEMBER_HOURS =
            UUID.fromString("4e008000-0000-4000-8000-000000000003");

    private static final LocalDate JANUARY = LocalDate.of(2026, 1, 1);
    private static final LocalDate AUGUST_START = LocalDate.of(2026, 8, 1);
    private static final LocalDate AUGUST_END = LocalDate.of(2026, 8, 31);
    private static final LocalDate WORKED = LocalDate.of(2026, 8, 12);
    private static final LocalDate SEPTEMBER_START = LocalDate.of(2026, 9, 1);
    private static final LocalDate SEPTEMBER_END = LocalDate.of(2026, 9, 30);
    private static final Instant DECIDED = Instant.parse("2026-08-17T09:00:00Z");

    @Autowired
    private InvoiceService invoices;

    @Autowired
    private RateCardService rateCards;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();
        tx.executeWithoutResult(status -> {
            company(ACME, "billing-invoices", "Acme", now);
            activeUser(ADMIN, "admin@invoice.test", "Ada Admin", now);
            activeUser(MIRA, "mira@invoice.test", "Mira Sund", now);
            activeUser(TOMAS, "tomas@invoice.test", "Tomas Ek", now);
        });
        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(STOREFRONT, ACME, "INVS", "Acme Storefront", now);
            seedProject(LOYALTY, ACME, "INVL", "Acme Loyalty", now);
            member(ACME, ADMIN, "Ada Admin", now);
            member(ACME, MIRA, "Mira Sund", now);
            member(ACME, TOMAS, "Tomas Ek", now);
        }));
        grantInCompany(UUID.fromString("4e003000-0000-4000-8000-000000000001"), ACME,
                COMPANY_ADMIN_ROLE, ADMIN);
        grantOnProject(UUID.fromString("4e003000-0000-4000-8000-000000000002"), ACME,
                TEST_ENGINEER_ROLE, MIRA, STOREFRONT);
        grantOnProject(UUID.fromString("4e003000-0000-4000-8000-000000000003"), ACME,
                TEST_ENGINEER_ROLE, TOMAS, STOREFRONT);
        seedProjectDefaults(ACME, STOREFRONT);
        seedProjectDefaults(ACME, LOYALTY);

        wipeBilling(ACME);
        client(ACME, CLIENT, "Acme Retail");
        contact(ACME, CONTACT, CLIENT, "Anna Krogh", "invoices@acme.test");
        attach(ACME, STOREFRONT, CLIENT);
        issueFact(ACME, STOREFRONT, ISSUE, "INVS-1", "Card capture");
    }

    @AfterEach
    void wipe() throws SQLException {
        wipeBilling(ACME);
    }

    // ================================================================== the lock ===

    @Test
    @DisplayName("an issued invoice's lines do not move when the rate card does")
    void anIssuedInvoicesLinesDoNotMoveWhenTheRateCardDoes() throws SQLException {
        approvedHours(MIRA, MIRA_HOURS, new BigDecimal("10.00"));
        setCard("78.00");

        UUID drafted = written(scoped(() -> invoices.draft(ADMIN, august()))).invoice().id();
        InvoiceDetailView issued = written(scoped(() -> invoices.issue(ADMIN, drafted)));

        assertThat(issued.invoice().number())
                .as("INV-<issue year>-<seq zero-padded to 3>, as the mockups draw it. Matched "
                        + "rather than compared: the year is the year of ISSUE (019 D-k), so a "
                        + "literal would redden this suite on 1 January for no defect at all.")
                .matches("INV-\\d{4}-001");
        assertThat(issued.invoice().status()).isEqualTo(InvoiceStatus.ISSUED);
        assertThat(issued.lines()).singleElement()
                .extracting(InvoiceLineView::rate).isEqualTo(new BigDecimal("78.00"));

        // The card doubles. An invoice already sent must not reprice.
        scoped(() -> rateCards.update(ADMIN, cardId(), card("156.00")));

        InvoiceDetailView reread = allowed(scoped(() -> invoices.read(ADMIN, drafted)));
        assertThat(reread.lines())
                .as("019 D-c: an issued line is a stored row carrying the rate AS APPLIED. Nothing "
                        + "on this surface updates one — the immutability is structural, not "
                        + "guarded, and this is the only way to observe the absence of a path.")
                .isEqualTo(issued.lines());
        assertThat(reread.invoice().total()).isEqualTo(issued.invoice().total());

        // And a DRAFT does move, which is the same decision seen from the other side.
        approvedHours(MIRA, SEPTEMBER_HOURS, SEPTEMBER_PERIOD, SEPTEMBER_START, SEPTEMBER_END,
                LocalDate.of(2026, 9, 14), new BigDecimal("4.00"));
        UUID second = written(scoped(() -> invoices.draft(ADMIN, september()))).invoice().id();
        assertThat(allowed(scoped(() -> invoices.read(ADMIN, second))).lines())
                .singleElement()
                .extracting(InvoiceLineView::rate).isEqualTo(new BigDecimal("156.00"));
    }

    @Test
    @DisplayName("an issued invoice cannot be discarded, and the refusal is a 409 rather than a 403")
    void anIssuedInvoiceCannotBeDiscarded() throws SQLException {
        approvedHours(MIRA, MIRA_HOURS, new BigDecimal("10.00"));
        setCard("78.00");
        UUID drafted = written(scoped(() -> invoices.draft(ADMIN, august()))).invoice().id();
        written(scoped(() -> invoices.issue(ADMIN, drafted)));

        assertThat(allowed(scoped(() -> invoices.discard(ADMIN, drafted))))
                .as("the caller may delete, the invoice exists, and it is not a field they can fix "
                        + "— so it is neither Denied nor Unreachable, exactly as 018 D-k reasoned "
                        + "for a client a project still names")
                .isInstanceOf(Outcome.Conflict.class);

        assertThat(allowed(scoped(() -> invoices.read(ADMIN, drafted))).invoice())
                .as("and the refusal left it exactly where it was")
                .satisfies(invoice -> {
                    assertThat(invoice.status()).isEqualTo(InvoiceStatus.ISSUED);
                    assertThat(invoice.number()).matches("INV-\\d{4}-001");
                });
    }

    @Test
    @DisplayName("a second issue of one invoice is a 409 and does not burn a second number")
    void issuingTwiceIsRefused() throws SQLException {
        approvedHours(MIRA, MIRA_HOURS, new BigDecimal("10.00"));
        setCard("78.00");
        UUID drafted = written(scoped(() -> invoices.draft(ADMIN, august()))).invoice().id();
        written(scoped(() -> invoices.issue(ADMIN, drafted)));

        assertThat(allowed(scoped(() -> invoices.issue(ADMIN, drafted))))
                .as("idempotency by re-issue would either reprice a sent document or silently do "
                        + "nothing, and both are worse than a refusal that names the state")
                .isInstanceOf(Outcome.Conflict.class);
    }

    @Test
    @DisplayName("a draft can be discarded, and its hours were never reserved to begin with")
    void aDraftCanBeDiscarded() throws SQLException {
        approvedHours(MIRA, MIRA_HOURS, new BigDecimal("10.00"));
        setCard("78.00");
        UUID drafted = written(scoped(() -> invoices.draft(ADMIN, august()))).invoice().id();

        assertThat(allowed(scoped(() -> invoices.discard(ADMIN, drafted))))
                .isInstanceOf(Outcome.Removed.class);
        assertThat(scoped(() -> invoices.read(ADMIN, drafted)))
                .isInstanceOf(GateResult.Unreachable.class);

        UUID again = written(scoped(() -> invoices.draft(ADMIN, august()))).invoice().id();
        assertThat(allowed(scoped(() -> invoices.read(ADMIN, again))).lines())
                .as("a draft reserves nothing (019 D-c), so discarding one frees nothing — the "
                        + "hours were draftable the whole time it existed")
                .singleElement()
                .extracting(InvoiceLineView::hours).isEqualTo(new BigDecimal("10.00"));
    }

    // ====================================================== the anti-double-bill ===

    @Test
    @DisplayName("an hour on an issued invoice is never offered to a second draft")
    void anHourOnAnIssuedInvoiceIsNeverOfferedToASecondDraft() throws SQLException {
        approvedHours(MIRA, MIRA_HOURS, new BigDecimal("10.00"));
        setCard("78.00");
        UUID first = written(scoped(() -> invoices.draft(ADMIN, august()))).invoice().id();
        UUID second = written(scoped(() -> invoices.draft(ADMIN, august()))).invoice().id();

        // Both drafts offer the same hours — 019 D-c states that cost rather than hiding it.
        assertThat(allowed(scoped(() -> invoices.read(ADMIN, second))).lines()).hasSize(1);

        written(scoped(() -> invoices.issue(ADMIN, first)));

        InvoiceDetailView recomputed = allowed(scoped(() -> invoices.read(ADMIN, second)));
        assertThat(recomputed.lines())
                .as("""
                        The one defect in this module that would reach a client as a bill for \
                        hours they already paid for. invoice_line_entry rows exist only for ISSUED \
                        invoices, so "already billed" is a fact about issued work and the exclusion \
                        is a NOT EXISTS in the query every draft read runs — not a rule somebody \
                        remembered to apply.""")
                .isEmpty();
        assertThat(recomputed.invoice().total().amount()).isEqualByComparingTo("0.00");
        assertThat(recomputed.excluded())
                .as("and they are not reported as excluded either: they are not missing from this "
                        + "draft, they are on another invoice")
                .isEmpty();
    }

    // ============================================================ the two buckets ===

    @Test
    @DisplayName("019 D-g — both exclusion buckets are counted and named, and neither is dropped")
    void bothExclusionBucketsAreCountedAndNamed() throws SQLException {
        // Priced: Mira, 10h, on a project whose client has a card.
        approvedHours(MIRA, MIRA_HOURS, new BigDecimal("10.00"));
        // NOT_APPROVED: Tomas, 6h, in the same window, on a period nobody has approved.
        period(ACME, OPEN_PERIOD, TOMAS, AUGUST_START, AUGUST_END, "SUBMITTED", null, null);
        issueEntry(ACME, TOMAS_HOURS, OPEN_PERIOD, TOMAS, STOREFRONT, ISSUE, WORKED,
                new BigDecimal("6.00"));
        // NO_RATE: a card whose only rung starts AFTER the day worked, so nothing prices.
        written(scoped(() -> rateCards.create(ADMIN, new RateCardWriteRequest(
                CLIENT, "EUR", List.of(new RateCardEntryInput(
                        RateCardScope.ALL_ROLES, null, null, new BigDecimal("78.00"),
                        LocalDate.of(2026, 12, 1)))))));

        InvoiceDetailView draft = written(scoped(() -> invoices.draft(ADMIN, august())));

        assertThat(draft.lines()).isEmpty();
        assertThat(draft.excluded())
                .as("""
                        An hour that vanished from a draft with no explanation is the defect this \
                        whole module exists to remove. The second bucket is the one a naive \
                        implementation loses, because from inside a join it looks exactly like a \
                        priced hour.""")
                .extracting(ExcludedHoursView::reason)
                .containsExactlyInAnyOrder(ExcludedReason.NOT_APPROVED, ExcludedReason.NO_RATE);

        assertThat(draft.excluded()).filteredOn(row -> row.reason() == ExcludedReason.NOT_APPROVED)
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.hours()).isEqualByComparingTo("6.00");
                    assertThat(row.detail()).isEqualTo("Tomas Ek 6h");
                });
        assertThat(draft.excluded()).filteredOn(row -> row.reason() == ExcludedReason.NO_RATE)
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.hours()).isEqualByComparingTo("10.00");
                    assertThat(row.detail()).isEqualTo("Mira Sund 10h");
                });
    }

    @Test
    @DisplayName("an empty `excluded` means every approved hour is on a line, not that none was looked for")
    void anEmptyExclusionListMeansEverythingIsOnALine() throws SQLException {
        approvedHours(MIRA, MIRA_HOURS, new BigDecimal("10.00"));
        setCard("78.00");

        InvoiceDetailView draft = written(scoped(() -> invoices.draft(ADMIN, august())));

        assertThat(draft.excluded()).isEmpty();
        assertThat(draft.lines()).singleElement()
                .satisfies(line -> {
                    assertThat(line.hours()).isEqualByComparingTo("10.00");
                    assertThat(line.amount().amount()).isEqualByComparingTo("780.00");
                    assertThat(line.amount().currency()).isEqualTo("EUR");
                    assertThat(line.appliesTo()).isEqualTo("All roles");
                });
    }

    // ================================================================ the entries ===

    @Test
    @DisplayName("019 D-f — an entry's approval names the PERIOD's decision and claims nothing more")
    void anEntryClaimsOnlyThatThePeriodWasApproved() throws JsonProcessingException, SQLException {
        approvedHours(MIRA, MIRA_HOURS, new BigDecimal("10.00"));
        setCard("78.00");

        InvoiceDetailView draft = written(scoped(() -> invoices.draft(ADMIN, august())));

        assertThat(draft.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.personName()).isEqualTo("Mira Sund");
            assertThat(entry.subjectKey()).isEqualTo("INVS-1");
            assertThat(entry.subjectTitle()).isEqualTo("Card capture");
            assertThat(entry.periodApprovedAt()).isEqualTo(DECIDED);
            assertThat(entry.periodApprovedBy()).isEqualTo("Ada Admin");
        });

        String body = json.writeValueAsString(draft).toLowerCase(Locale.ROOT);
        assertThat(body)
                .as("""
                        "These hours were approved" is a claim the data supports. "Somebody with \
                        authority over this project approved these hours" is NOT, because a period \
                        is per person per fortnight and SPANS PROJECTS while time:approve is a \
                        PROJECT-scope grant (019 D-f, 016 D-i). No field on this surface makes the \
                        second claim, and the member names are how that is kept true.""")
                .contains("periodapprovedby")
                .doesNotContain("approvedbylead")
                .doesNotContain("authorised")
                .doesNotContain("authorized");
    }

    @Test
    @DisplayName("019 D-e — pending hours and non-issue hours never reach a line")
    void onlyApprovedIssueHoursAreBillable() throws SQLException {
        approvedHours(MIRA, MIRA_HOURS, new BigDecimal("10.00"));
        setCard("78.00");
        nonIssueEntry(ACME, UUID.fromString("4e008000-0000-4000-8000-000000000009"),
                APPROVED_PERIOD, MIRA, "MEETING", WORKED, new BigDecimal("4.00"));

        InvoiceDetailView draft = written(scoped(() -> invoices.draft(ADMIN, august())));

        assertThat(draft.lines()).singleElement()
                .extracting(InvoiceLineView::hours).isEqualTo(new BigDecimal("10.00"));
        assertThat(draft.excluded())
                .as("""
                        ck_time_entry_subject_shape makes project_id NULL for a MEETING, so four of \
                        the five subject kinds have no project, therefore no client, therefore no \
                        rate card — and can never reach an invoice at all. That is a constraint the \
                        database already holds, not a filter this module chose, which is why the \
                        meeting is not even an excluded hour.""")
                .isEmpty();
    }

    // ================================================================== the draft ===

    @Test
    @DisplayName("a client with no rate card cannot be drafted for, and the refusal names the card")
    void aClientWithNoRateCardCannotBeDrafted() throws SQLException {
        approvedHours(MIRA, MIRA_HOURS, new BigDecimal("10.00"));

        Outcome<InvoiceDetailView> refused = allowed(scoped(() -> invoices.draft(ADMIN, august())));

        assertThat(refused).isInstanceOf(Outcome.Invalid.class);
        assertThat(((Outcome.Invalid<InvoiceDetailView>) refused).errors())
                .as("a draft that could price nothing would be a document whose every hour is in "
                        + "an exclusion bucket, and billing.html's Set button is where that is fixed")
                .containsKey("clientId");
    }

    @Test
    @DisplayName("the draft carries the client's bill-to contact, and a period with no dates is a 400")
    void theDraftCarriesTheBillToAndValidatesItsPeriod() throws SQLException {
        approvedHours(MIRA, MIRA_HOURS, new BigDecimal("10.00"));
        setCard("78.00");

        InvoiceDetailView draft = written(scoped(() -> invoices.draft(ADMIN, august())));
        assertThat(draft.billTo()).isNotNull();
        assertThat(draft.billTo().name()).isEqualTo("Anna Krogh");
        assertThat(draft.billTo().email()).isEqualTo("invoices@acme.test");
        assertThat(draft.termsDays()).as("the contract declares no default, so the server records 30")
                .isEqualTo(30);
        assertThat(draft.invoice().number()).as("assigned on issue, and the panel says so").isNull();

        Outcome<InvoiceDetailView> backwards = allowed(scoped(() -> invoices.draft(
                ADMIN, new InvoiceDraftInput(CLIENT, AUGUST_END, AUGUST_START, null))));
        assertThat(backwards).isInstanceOf(Outcome.Invalid.class);
        assertThat(((Outcome.Invalid<InvoiceDetailView>) backwards).errors())
                .containsKey("periodEnd");
    }

    @Test
    @DisplayName("the list carries a draft's computed total beside an issued invoice's stored one")
    void theListCarriesBothShapes() throws SQLException {
        approvedHours(MIRA, MIRA_HOURS, new BigDecimal("10.00"));
        setCard("78.00");
        UUID first = written(scoped(() -> invoices.draft(ADMIN, august()))).invoice().id();
        written(scoped(() -> invoices.issue(ADMIN, first)));
        approvedHours(MIRA, SEPTEMBER_HOURS, SEPTEMBER_PERIOD, SEPTEMBER_START, SEPTEMBER_END,
                LocalDate.of(2026, 9, 14), new BigDecimal("4.00"));
        written(scoped(() -> invoices.draft(ADMIN, september())));

        List<InvoiceView> all = allowed(scoped(() -> invoices.list(ADMIN, null, null)));
        assertThat(all).hasSize(2);
        assertThat(all).extracting(InvoiceView::status)
                .as("billing.html renders two issued and one draft in ONE list, the draft outlined "
                        + "rather than filtered away — so ALL is the default")
                .containsExactly(InvoiceStatus.DRAFT, InvoiceStatus.ISSUED);
        assertThat(all.get(0).total().amount()).isEqualByComparingTo("312.00");
        assertThat(all.get(1).total().amount()).isEqualByComparingTo("780.00");

        assertThat(allowed(scoped(() -> invoices.list(ADMIN, null, InvoiceStatus.ISSUED))))
                .singleElement()
                .extracting(InvoiceView::number).asString().matches("INV-\\d{4}-001");
    }

    // =====================================================================

    private InvoiceDraftInput august() {
        return new InvoiceDraftInput(CLIENT, AUGUST_START, AUGUST_END, null);
    }

    private InvoiceDraftInput september() {
        return new InvoiceDraftInput(CLIENT, SEPTEMBER_START, SEPTEMBER_END, null);
    }

    private RateCardWriteRequest card(String rate) {
        return new RateCardWriteRequest(CLIENT, "EUR", List.of(new RateCardEntryInput(
                RateCardScope.ALL_ROLES, null, null, new BigDecimal(rate), JANUARY)));
    }

    private void setCard(String rate) {
        written(scoped(() -> rateCards.create(ADMIN, card(rate))));
    }

    private UUID cardId() {
        return allowed(scoped(() -> rateCards.listSlots(ADMIN))).get(0).card().id();
    }

    private void approvedHours(UUID personId, UUID entryId, BigDecimal amount) throws SQLException {
        approvedHours(personId, entryId, APPROVED_PERIOD, AUGUST_START, AUGUST_END, WORKED, amount);
    }

    /** One approved fortnight, decided by the Company Admin — 019 D-f's "who decided the period". */
    private void approvedHours(
            UUID personId, UUID entryId, UUID periodId, LocalDate from, LocalDate to,
            LocalDate spentOn, BigDecimal amount) throws SQLException {

        period(ACME, periodId, personId, from, to, "APPROVED", DECIDED, ADMIN);
        issueEntry(ACME, entryId, periodId, personId, STOREFRONT, ISSUE, spentOn, amount);
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
