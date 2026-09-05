package com.tcms.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.identity.PermissionEvaluator;
import com.tcms.project.GateResult;
import com.tcms.tenancy.CompanyContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

/**
 * Scope §7's sharpest boundary, <strong>from the money side</strong>.
 *
 * <p>{@code time.TimeMoneyBoundaryTest} asserts that a person who logs an hour cannot see its price.
 * This asserts the other half: nobody outside Finance, Company Admin and Instance Admin can reach
 * the surface that knows.
 *
 * <h2>The paths are derived from the contract, not listed</h2>
 *
 * <p>The working agreement's rule applies literally: <em>a catalogue-wide invariant needs a computed
 * check, not a careful reading.</em> Every path item tagged {@code billing} in
 * {@code docs/specs/openapi.yaml} is enumerated here, so a thirteenth endpoint added later without a
 * gate reddens this test instead of shipping. {@code backend/build.gradle.kts} already declares
 * {@code docs/specs/} as a test input, so this does not go green by cache.
 *
 * <p><strong>It asserts the LACK of a permission and the refusal that follows</strong>, never the
 * presence of a role. "The matrix says Finance only, therefore it holds" is exactly the reading pass
 * that missed five join tables without {@code company_id}.
 *
 * <h2>One of the eleven is an exception, and it is asserted as one</h2>
 *
 * <p>{@code GET /projects/{projectId}/budget-burn} is tagged {@code billing} like the other ten and
 * is <strong>the one a Test Engineer may call</strong> (019 D-h). It is not quietly left out of the
 * population — that would make the exception invisible and a future ungated endpoint indisting-
 * uishable from it. It gets its own arm below, asserting the opposite of the other ten, and the
 * reason it is safe is that {@link BudgetBurnView} has no amount member and no currency member at
 * all. {@code BudgetBurnTest.theProjectScopeBudgetReadCarriesNoMoneyWord} is that endpoint's own
 * check, over its serialized body.
 *
 * <p><strong>UUID prefix {@code 4c}, verified against the tree rather than assumed</strong> — 018
 * records that {@code fa} and {@code fb} looked free, were not, and the suite failed on a
 * {@code project_pkey} duplicate naming neither class.
 */
@AutoConfigureMockMvc
@DisplayName("Billing and money — the seven keys, the refusals, and the paths derived from the contract")
class BillingMoneyBoundaryTest extends BillingTestBase {

    /**
     * The seven keys V2 seeded, held as a literal <em>and</em> compared to the catalogue by
     * {@code time.TimeMoneyBoundaryTest.everyMoneyKeyInTheCatalogueIsCoveredByThisTest}.
     *
     * <p>Not re-derived here. That test is the boundary's grow-proof arm and duplicating it would
     * make two files the authority on what a money key is — the second copy stays green while the
     * first fails, which is worse than not having it.
     */
    private static final Set<String> MONEY_KEYS = Set.of(
            "ratecard:read", "ratecard:manage",
            "billing:read", "billing:budget", "billing:expense", "billing:invoice",
            "billing:issue");

    /** The contract as text, from the module root — the same relative path every backend test uses. */
    private static final Path CONTRACT = Path.of("..", "docs", "specs", "openapi.yaml");

    /** A path item: two spaces, a slash, a colon, end of line. The document's own shape. */
    private static final Pattern PATH_ITEM = Pattern.compile("^ {2}(/\\S*):\\s*$");

    /** How every operation in this document spells its tag list — one tag, on one line. */
    private static final String BILLING_TAG = "tags: [billing]";

    private static final UUID COMPANY_ADMIN_ROLE =
            UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID TEST_ENGINEER_ROLE =
            UUID.fromString("10000000-0000-4000-8000-000000000004");
    private static final UUID ACCOUNT_MANAGER_ROLE =
            UUID.fromString("10000000-0000-4000-8000-000000000010");
    private static final UUID FINANCE_ROLE =
            UUID.fromString("10000000-0000-4000-8000-000000000011");

    private static final UUID ACME = UUID.fromString("4c000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("4c001000-0000-4000-8000-000000000001");

    private static final UUID ADMIN = UUID.fromString("4c002000-0000-4000-8000-000000000001");
    private static final UUID FIN = UUID.fromString("4c002000-0000-4000-8000-000000000002");
    private static final UUID DANA_TEST_ENGINEER =
            UUID.fromString("4c002000-0000-4000-8000-000000000003");
    private static final UUID ANNA_ACCOUNT_MANAGER =
            UUID.fromString("4c002000-0000-4000-8000-000000000004");

    private static final UUID CLIENT = UUID.fromString("4c005000-0000-4000-8000-000000000001");
    private static final UUID PERIOD = UUID.fromString("4c006000-0000-4000-8000-000000000001");
    private static final UUID ISSUE = UUID.fromString("4c007000-0000-4000-8000-000000000001");
    private static final UUID ENTRY = UUID.fromString("4c008000-0000-4000-8000-000000000001");

    private static final LocalDate JANUARY = LocalDate.of(2026, 1, 1);
    private static final LocalDate AUGUST_START = LocalDate.of(2026, 8, 1);
    private static final LocalDate AUGUST_END = LocalDate.of(2026, 8, 31);

    @Autowired
    private InvoiceService invoices;

    @Autowired
    private RateCardService rateCards;

    @Autowired
    private BudgetService budgets;

    @Autowired
    private ExpenseService expenses;

    @Autowired
    private BillingSummaryService summaries;

    @Autowired
    private ClientBillingService clientBilling;

    @Autowired
    private PermissionEvaluator permissions;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();
        tx.executeWithoutResult(status -> {
            company(ACME, "billing-boundary", "Acme", now);
            activeUser(ADMIN, "admin@boundary.test", "Ada Admin", now);
            activeUser(FIN, "fin@boundary.test", "Fin Ance", now);
            activeUser(DANA_TEST_ENGINEER, "dana@boundary.test", "Dana Test", now);
            activeUser(ANNA_ACCOUNT_MANAGER, "anna@boundary.test", "Anna Konto", now);
        });
        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(PROJECT, ACME, "BND", "Acme Storefront", now);
            member(ACME, ADMIN, "Ada Admin", now);
            member(ACME, FIN, "Fin Ance", now);
            member(ACME, DANA_TEST_ENGINEER, "Dana Test", now);
            member(ACME, ANNA_ACCOUNT_MANAGER, "Anna Konto", now);
        }));
        grantInCompany(UUID.fromString("4c003000-0000-4000-8000-000000000001"), ACME,
                COMPANY_ADMIN_ROLE, ADMIN);
        grantInCompany(UUID.fromString("4c003000-0000-4000-8000-000000000002"), ACME,
                FINANCE_ROLE, FIN);
        grantInCompany(UUID.fromString("4c003000-0000-4000-8000-000000000003"), ACME,
                ACCOUNT_MANAGER_ROLE, ANNA_ACCOUNT_MANAGER);
        grantOnProject(UUID.fromString("4c003000-0000-4000-8000-000000000004"), ACME,
                TEST_ENGINEER_ROLE, DANA_TEST_ENGINEER, PROJECT);
        seedProjectDefaults(ACME, PROJECT);

        wipeBilling(ACME);
        client(ACME, CLIENT, "Acme Retail");
        attach(ACME, PROJECT, CLIENT);
        issueFact(ACME, PROJECT, ISSUE, "BND-1", "Card capture");
        period(ACME, PERIOD, DANA_TEST_ENGINEER, AUGUST_START, AUGUST_END, "APPROVED",
                Instant.parse("2026-08-17T09:00:00Z"), ADMIN);
        issueEntry(ACME, ENTRY, PERIOD, DANA_TEST_ENGINEER, PROJECT, ISSUE,
                LocalDate.of(2026, 8, 12), new BigDecimal("10.00"));
    }

    @AfterEach
    void wipe() throws SQLException {
        wipeBilling(ACME);
    }

    // ============================================ the population, from the contract ===

    @Test
    @DisplayName("every billing-tagged path in the contract has an assertion in this class")
    void everyBillingTaggedPathIsCoveredByThisTest() {
        List<String> declared = pathsTaggedBilling();

        assertThat(declared)
                .as("the contract declares fewer billing paths than M8 landed — the tag, the "
                        + "document's location or this test's parser has moved, and every "
                        + "assertion below would then be about an empty list")
                .hasSizeGreaterThanOrEqualTo(11);
        assertThat(declared)
                .as("""
                        A billing-tagged path exists that this class does not exercise.

                        Every entry below has an assertion in this class: ten are refused to a \
                        Test Engineer, and budget-burn is the one 019 D-h allows them, asserted as \
                        an exception rather than left out of the population. An endpoint added to \
                        the contract reddens this arm rather than shipping ungated — and adding it \
                        here without also asserting its refusal below is the mistake this pairing \
                        exists to make visible.""")
                .containsExactlyInAnyOrder(
                        "/billing/summary",
                        "/rate-cards",
                        "/rate-cards/{rateCardId}",
                        "/budgets",
                        "/projects/{projectId}/budget",
                        "/projects/{projectId}/budget-burn",
                        "/invoices",
                        "/invoices/{invoiceId}",
                        "/invoices/{invoiceId}/issue",
                        "/expenses",
                        "/expenses/{expenseId}");
    }

    @Test
    @DisplayName("019 D-h — budget-burn is the one billing path a Test Engineer MAY call")
    void theBudgetBurnIsTheOneBillingPathATestEngineerMayCall() {
        written(scoped(() -> budgets.set(ADMIN, PROJECT,
                new BudgetWriteRequest(BudgetBasis.HOURS, new BigDecimal("100"), null))));

        BudgetBurnView burn = allowed(scoped(() -> budgets.burn(DANA_TEST_ENGINEER, PROJECT)));

        assertThat(burn.budgetHours()).isEqualByComparingTo("100");
        assertThat(burn.approvedHours()).isEqualByComparingTo("10.00");
        assertThat(burn.burnPercent()).isEqualByComparingTo("10.0");
        /*
          Gated on project:read and not on a money key, and the reason that is safe is that there is
          nothing on it to withhold: hiding a project's own ceiling from the people delivering
          against it is the opposite of what the roles spec means by "a Project Manager… whose
          budget view is a percentage on the project overview rather than a number in a currency".
          The check that the SHAPE carries no money is BudgetBurnTest's, over the serialized body.
        */
    }

    // ================================================================= the refusals ===

    @Test
    @DisplayName("a Test Engineer holds time:log and is refused every read behind a money key")
    void aTestEngineerIsRefusedEveryBillingRead() {
        assertThat(heldOnProject(DANA_TEST_ENGINEER))
                .as("story T1: the person who logs the hour cannot see what it costs")
                .contains("time:log")
                .doesNotContainAnyElementsOf(MONEY_KEYS);

        /*
          One assertion per service call rather than one per HTTP path, and the two populations are
          tied together by everyBillingTaggedPathIsCoveredByThisTest above: a path with no call here
          fails that arm, and a call whose gate is missing fails this one. Asserting over MockMvc
          would test Spring Security's filter chain, which qa-api-java owns; what is under test here
          is the gate each service applies.
        */
        assertThat(scoped(() -> summaries.summary(DANA_TEST_ENGINEER, null)))
                .isInstanceOf(GateResult.Denied.class);
        assertThat(scoped(() -> rateCards.listSlots(DANA_TEST_ENGINEER)))
                .isInstanceOf(GateResult.Denied.class);
        assertThat(scoped(() -> rateCards.read(DANA_TEST_ENGINEER, UUID.randomUUID())))
                .isInstanceOf(GateResult.Denied.class);
        assertThat(scoped(() -> budgets.list(DANA_TEST_ENGINEER, null)))
                .isInstanceOf(GateResult.Denied.class);
        assertThat(scoped(() -> invoices.list(DANA_TEST_ENGINEER, null, null)))
                .isInstanceOf(GateResult.Denied.class);
        assertThat(scoped(() -> invoices.read(DANA_TEST_ENGINEER, UUID.randomUUID())))
                .isInstanceOf(GateResult.Denied.class);
        assertThat(scoped(() -> expenses.list(DANA_TEST_ENGINEER, PROJECT, null, null)))
                .isInstanceOf(GateResult.Denied.class);
    }

    @Test
    @DisplayName("a Test Engineer is refused every WRITE behind a money key, including the issue")
    void aTestEngineerIsRefusedEveryBillingWrite() {
        assertThat(scoped(() -> rateCards.create(DANA_TEST_ENGINEER,
                new RateCardWriteRequest(CLIENT, "EUR", List.of()))))
                .isInstanceOf(GateResult.Denied.class);
        assertThat(scoped(() -> rateCards.update(DANA_TEST_ENGINEER, UUID.randomUUID(),
                new RateCardWriteRequest(CLIENT, "EUR", List.of()))))
                .isInstanceOf(GateResult.Denied.class);
        assertThat(scoped(() -> rateCards.delete(DANA_TEST_ENGINEER, UUID.randomUUID())))
                .isInstanceOf(GateResult.Denied.class);
        assertThat(scoped(() -> budgets.set(DANA_TEST_ENGINEER, PROJECT,
                new BudgetWriteRequest(BudgetBasis.HOURS, new BigDecimal("100"), null))))
                .isInstanceOf(GateResult.Denied.class);
        assertThat(scoped(() -> budgets.clear(DANA_TEST_ENGINEER, PROJECT)))
                .isInstanceOf(GateResult.Denied.class);
        assertThat(scoped(() -> invoices.draft(DANA_TEST_ENGINEER,
                new InvoiceDraftInput(CLIENT, AUGUST_START, AUGUST_END, null))))
                .isInstanceOf(GateResult.Denied.class);
        assertThat(scoped(() -> invoices.discard(DANA_TEST_ENGINEER, UUID.randomUUID())))
                .isInstanceOf(GateResult.Denied.class);
        assertThat(scoped(() -> invoices.issue(DANA_TEST_ENGINEER, UUID.randomUUID())))
                .as("billing:issue is its own key, so holding none of the other six would not have "
                        + "been enough to prove this one refuses. Inside scoped() like every other "
                        + "call here even though issue() carries no @Transactional of its own "
                        + "(019 D-k): the gate still asks PermissionEvaluator, which requires a "
                        + "company context whether or not a transaction is open.")
                .isInstanceOf(GateResult.Denied.class);
        assertThat(scoped(() -> expenses.record(DANA_TEST_ENGINEER, new ExpenseWriteRequest(
                PROJECT, JANUARY, "A laptop", new BigDecimal("900"), "EUR"))))
                .isInstanceOf(GateResult.Denied.class);
        assertThat(scoped(() -> expenses.delete(DANA_TEST_ENGINEER, UUID.randomUUID())))
                .isInstanceOf(GateResult.Denied.class);
    }

    @Test
    @DisplayName("story M2 — an Account Manager holds client:read and not one money key")
    void anAccountManagerHoldsClientReadAndNotOneMoneyKey() {
        Set<String> held = heldOnProject(ANNA_ACCOUNT_MANAGER);

        assertThat(held).contains("client:read");
        assertThat(held)
                .as("the Account Manager owns the client relationship and never sees its price — "
                        + "roles spec §A3's Money table, asserted rather than read")
                .doesNotContainAnyElementsOf(MONEY_KEYS);

        assertThat(scoped(() -> summaries.summary(ANNA_ACCOUNT_MANAGER, null)))
                .as("billing.html's own callout: the sidebar entry disappears for them entirely — "
                        + "not greyed out, not empty. A screen that exists only to say no is the "
                        + "same refusal one click later (019 D-o).")
                .isInstanceOf(GateResult.Denied.class);
        assertThat(scoped(() -> invoices.list(ANNA_ACCOUNT_MANAGER, null, null)))
                .isInstanceOf(GateResult.Denied.class);
        assertThat(scoped(() -> rateCards.listSlots(ANNA_ACCOUNT_MANAGER)))
                .isInstanceOf(GateResult.Denied.class);
    }

    // ============================================================ the one exception ===

    @Test
    @DisplayName("019 D-i — the client billing summary answers an Account Manager with no amounts")
    void theClientBillingSummaryHidesItsAmountsFromAnAccountManager() throws Exception {
        setCard();
        UUID drafted = written(scoped(() -> invoices.draft(FIN, new InvoiceDraftInput(
                CLIENT, AUGUST_START, AUGUST_END, null)))).invoice().id();
        written(scoped(() -> invoices.issue(FIN, drafted)));

        ClientBillingSummaryView asFinance =
                allowed(scoped(() -> clientBilling.read(FIN, CLIENT)));
        assertThat(asFinance.amountsVisible()).isTrue();
        assertThat(asFinance.rows()).anySatisfy(row -> assertThat(row.amount()).isNotNull());

        ClientBillingSummaryView asManager =
                allowed(scoped(() -> clientBilling.read(ANNA_ACCOUNT_MANAGER, CLIENT)));
        assertThat(asManager.amountsVisible()).isFalse();
        assertThat(asManager.rows()).allSatisfy(row -> assertThat(row.amount()).isNull());
        assertThat(asManager.approvedHoursToDate())
                .as("they see the client and the HOURS — client.html gives them both — and never a "
                        + "rate. The hours are not withheld from anybody who may see the client.")
                .isEqualByComparingTo(asFinance.approvedHoursToDate());
        assertThat(asManager.invoiceCount())
                .as("that an invoice EXISTS is not money; what it is worth is")
                .isEqualTo(asFinance.invoiceCount());

        String body = json.writeValueAsString(asManager).toLowerCase(java.util.Locale.ROOT);
        assertThat(body)
                .as("a null amount is readable only because amountsVisible says which null it is — "
                        + "and no currency, rate or cost reaches an Account Manager at all")
                .doesNotContain("\"currency\"")
                .doesNotContain("rate")
                .doesNotContain("cost");
    }

    @Test
    @DisplayName("a caller with no client:read is refused the client billing summary outright")
    void theClientBillingSummaryStillNeedsClientRead() {
        assertThat(heldOnProject(DANA_TEST_ENGINEER)).doesNotContain("client:read");
        assertThat(scoped(() -> clientBilling.read(DANA_TEST_ENGINEER, CLIENT)))
                .as("the gate is client:read and the money is a second question asked after it, "
                        + "not instead of it")
                .isInstanceOf(GateResult.Denied.class);
    }

    // =====================================================================

    /**
     * Every path item in the contract carrying the {@code billing} tag on any of its operations.
     *
     * <p>Parsed from the text rather than from a YAML object model, which is
     * {@code ConfigConventionTest}'s device and is the right one here — and 019 T4 found the second
     * reason on the way through: the backend's YAML parser is SnakeYAML, which is YAML <em>1.1</em>
     * and reads an unquoted {@code on} as a boolean, so {@code GET /billing/summary}'s own
     * parameter parsed as {@code name: true} while {@code openapi-typescript} (YAML 1.2) read it
     * correctly and both generated copies were right. A text parser has no such opinion.
     *
     * <p>The floor on the count in {@link #everyBillingTaggedPathIsCoveredByThisTest} is what
     * catches this parser when the document's shape moves under it — a regex that silently matched
     * nothing would make every arm here pass over an empty list.
     */
    private static List<String> pathsTaggedBilling() {
        String contract = read(CONTRACT);
        int from = contract.indexOf("\npaths:");
        int to = contract.indexOf("\ncomponents:");
        String section = contract.substring(from, to > from ? to : contract.length());

        Set<String> tagged = new TreeSet<>();
        String current = null;
        StringBuilder body = new StringBuilder();
        for (String line : section.split("\n", -1)) {
            Matcher header = PATH_ITEM.matcher(line);
            if (header.matches()) {
                collect(tagged, current, body);
                current = header.group(1);
                body.setLength(0);
            } else if (current != null) {
                body.append(line).append('\n');
            }
        }
        collect(tagged, current, body);
        return List.copyOf(tagged);
    }

    private static void collect(Set<String> tagged, String path, StringBuilder body) {
        if (path != null && body.indexOf(BILLING_TAG) >= 0) {
            tagged.add(path);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(
                    path + " is this rule's population — backend/build.gradle.kts declares "
                            + "docs/specs/ as a test input so it is on disk and not cached away",
                    unreadable);
        }
    }

    private void setCard() {
        written(scoped(() -> rateCards.create(ADMIN, new RateCardWriteRequest(
                CLIENT, "EUR", List.of(new RateCardEntryInput(
                        RateCardScope.ALL_ROLES, null, null, new BigDecimal("78.00"), JANUARY))))));
    }

    private Set<String> heldOnProject(UUID userId) {
        return scoped(() -> tx.execute(status -> permissions.evaluate(userId, PROJECT).keys()));
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
