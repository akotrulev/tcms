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
 * The rate-card surface — one card per client, the label that renders on it, and the three refusals.
 *
 * <p>The slot list is what makes {@code billing.html}'s Kessler Health row possible: the population
 * is the <strong>clients</strong> and the cards are joined onto it, so a client with no card is a
 * row reading <em>no rate card yet</em> beside the {@code Set} button rather than a client that has
 * quietly vanished from a Finance user's table.
 *
 * <p><strong>UUID prefix {@code 52}, verified against the tree rather than assumed</strong> — 018
 * records what assuming costs.
 */
@AutoConfigureMockMvc
@DisplayName("Rate cards — one per client, a resolved label, and three different refusals")
class RateCardEndpointTest extends BillingTestBase {

    private static final UUID COMPANY_ADMIN_ROLE =
            UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID TEST_LEAD_ROLE =
            UUID.fromString("10000000-0000-4000-8000-000000000003");

    private static final UUID BERGSTROM = UUID.fromString("52000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("52001000-0000-4000-8000-000000000001");
    private static final UUID ADMIN = UUID.fromString("52002000-0000-4000-8000-000000000001");
    private static final UUID MIRA = UUID.fromString("52002000-0000-4000-8000-000000000002");
    private static final UUID ACME_RETAIL = UUID.fromString("52005000-0000-4000-8000-000000000001");
    private static final UUID KESSLER = UUID.fromString("52005000-0000-4000-8000-000000000002");
    private static final UUID STRANGER = UUID.fromString("52009000-0000-4000-8000-000000000001");

    private static final LocalDate JANUARY = LocalDate.of(2026, 1, 1);

    @Autowired
    private RateCardService cards;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();
        tx.executeWithoutResult(status -> {
            company(BERGSTROM, "billing-bergstrom", "Bergstrom", now);
            activeUser(ADMIN, "admin@ratecard.test", "Ada Admin", now);
            activeUser(MIRA, "mira@ratecard.test", "Mira Sund", now);
        });
        inCompany(BERGSTROM, () -> tx.executeWithoutResult(status -> {
            seedProject(PROJECT, BERGSTROM, "RCD", "Bergstrom Portal", now);
            member(BERGSTROM, ADMIN, "Ada Admin", now);
            member(BERGSTROM, MIRA, "Mira Sund", now);
        }));
        grantInCompany(UUID.fromString("52003000-0000-4000-8000-000000000001"), BERGSTROM,
                COMPANY_ADMIN_ROLE, ADMIN);
        seedProjectDefaults(BERGSTROM, PROJECT);
    }

    @AfterEach
    void wipe() throws SQLException {
        wipeBilling(BERGSTROM);
    }

    @Test
    @DisplayName("a company with no clients answers an empty slot list, and that is complete")
    void aCompanyWithNoClientsAnswersAnEmptyList() {
        /*
          The owner's own case (scope D1 rule 2): a company that builds its own products never
          records a client, and nothing in this product marks that as incomplete. An endpoint that
          403'd or errored here would make the Billing screen render an error where it must render
          an empty table.
        */
        assertThat(allowed(scoped(() -> cards.listSlots(ADMIN)))).isEmpty();
    }

    @Test
    @DisplayName("every ACTIVE client gets a slot — card or null, which is a row and not an absence")
    void everyActiveClientGetsASlot() throws SQLException {
        client(BERGSTROM, ACME_RETAIL, "Acme Retail");
        client(BERGSTROM, KESSLER, "Kessler Health");
        written(scoped(() -> cards.create(ADMIN, new RateCardWriteRequest(
                ACME_RETAIL, "EUR", List.of(allRoles("95.00"))))));

        List<RateCardSlotView> slots = allowed(scoped(() -> cards.listSlots(ADMIN)));

        assertThat(slots).extracting(slot -> slot.client().name())
                .containsExactly("Acme Retail", "Kessler Health");
        assertThat(slots.get(0).card()).isNotNull();
        assertThat(slots.get(1).card())
                .as("billing.html's Kessler Health row reads \"no rate card yet\" beside a Set "
                        + "button — a table built from the cards alone could not draw it")
                .isNull();
    }

    @Test
    @DisplayName("a second card for one client is a 409, and the refusal names the way forward")
    void oneCardPerClient() throws SQLException {
        client(BERGSTROM, ACME_RETAIL, "Acme Retail");
        written(scoped(() -> cards.create(ADMIN, new RateCardWriteRequest(
                ACME_RETAIL, "EUR", List.of(allRoles("95.00"))))));

        Outcome<RateCardView> refused = allowed(scoped(() -> cards.create(
                ADMIN, new RateCardWriteRequest(ACME_RETAIL, "EUR", List.of()))));

        assertThat(refused).isInstanceOf(Outcome.Conflict.class);
        assertThat(((Outcome.Conflict<RateCardView>) refused).detail())
                .as("a refusal that names no way forward is a dead end")
                .contains("Edit the existing card");
    }

    @Test
    @DisplayName("an unknown client is a 400 on `clientId`, and a bad currency a 400 on `currency`")
    void theTwoFieldErrors() {
        Outcome<RateCardView> unknownClient = allowed(scoped(() -> cards.create(
                ADMIN, new RateCardWriteRequest(STRANGER, "EUR", List.of()))));

        /*
          A 400 and not a 500 from fk_rate_card_client, which is on `id` alone and would accept
          another company's client because referential integrity is not subject to RLS (018 D-l).
          And not a 404, which would be an oracle for client ids in another tenancy (§6t).
        */
        assertThat(unknownClient).isInstanceOf(Outcome.Invalid.class);
        assertThat(((Outcome.Invalid<RateCardView>) unknownClient).errors()).containsKey("clientId");
    }

    @Test
    @DisplayName("a currency that is not three upper-case letters is a field error, not a 500")
    void aBadCurrencyIsAFieldError() throws SQLException {
        client(BERGSTROM, ACME_RETAIL, "Acme Retail");

        Outcome<RateCardView> refused = allowed(scoped(() -> cards.create(
                ADMIN, new RateCardWriteRequest(ACME_RETAIL, "euros", List.of()))));

        assertThat(refused).isInstanceOf(Outcome.Invalid.class);
        assertThat(((Outcome.Invalid<RateCardView>) refused).errors()).containsKey("currency");
    }

    @Test
    @DisplayName("`appliesTo` is resolved server-side — a name, a role, or All roles")
    void everyRungCarriesAResolvedLabel() throws SQLException {
        client(BERGSTROM, ACME_RETAIL, "Acme Retail");

        RateCardView card = written(scoped(() -> cards.create(ADMIN, new RateCardWriteRequest(
                ACME_RETAIL, "CHF", List.of(
                        allRoles("95.00"),
                        new RateCardEntryInput(
                                RateCardScope.ROLE, null, TEST_LEAD_ROLE,
                                new BigDecimal("120.00"), JANUARY),
                        new RateCardEntryInput(
                                RateCardScope.PERSON, MIRA, null,
                                new BigDecimal("140.00"), JANUARY))))));

        assertThat(card.entries()).extracting(RateCardEntryView::appliesTo)
                .as("the client renders this string and never joins a person or a role to build "
                        + "it — and a person's name is their company membership's, per V1 line 99")
                .containsExactlyInAnyOrder("All roles", "Test Lead", "Mira Sund");
        assertThat(card.currency()).isEqualTo("CHF");
        assertThat(card.client().name()).isEqualTo("Acme Retail");
    }

    @Test
    @DisplayName("a PUT is a full replacement — an omitted entry is a removed one")
    void aPutReplacesEveryEntry() throws SQLException {
        client(BERGSTROM, ACME_RETAIL, "Acme Retail");
        client(BERGSTROM, KESSLER, "Kessler Health");
        RateCardView card = written(scoped(() -> cards.create(ADMIN, new RateCardWriteRequest(
                ACME_RETAIL, "EUR", List.of(
                        allRoles("95.00"),
                        new RateCardEntryInput(
                                RateCardScope.PERSON, MIRA, null,
                                new BigDecimal("140.00"), JANUARY))))));
        assertThat(card.entries()).hasSize(2);

        RateCardView replaced = written(scoped(() -> cards.update(
                ADMIN, card.id(),
                // The clientId on the body names a DIFFERENT client, and it is ignored: a card does
                // not move between clients, and moving one would reprice every draft on both sides.
                new RateCardWriteRequest(KESSLER, "EUR", List.of(allRoles("105.00"))))));

        assertThat(replaced.entries()).hasSize(1);
        assertThat(replaced.entries().get(0).hourlyRate()).isEqualByComparingTo("105.00");
        assertThat(replaced.client().id()).isEqualTo(ACME_RETAIL);

        assertThat(allowed(scoped(() -> cards.read(ADMIN, card.id()))).entries())
                .as("read back rather than trusted: the write's own answer cannot show that the "
                        + "replaced rows are gone from the database")
                .hasSize(1);
    }

    @Test
    @DisplayName("a delete takes the card and its entries, and is never refused by an invoice")
    void aDeleteTakesTheEntriesWithIt() throws SQLException {
        client(BERGSTROM, ACME_RETAIL, "Acme Retail");
        RateCardView card = written(scoped(() -> cards.create(ADMIN, new RateCardWriteRequest(
                ACME_RETAIL, "EUR", List.of(allRoles("95.00"))))));

        assertThat(allowed(scoped(() -> cards.delete(ADMIN, card.id()))))
                .isInstanceOf(Outcome.Removed.class);
        assertThat(scoped(() -> cards.read(ADMIN, card.id())))
                .isInstanceOf(GateResult.Unreachable.class);
        assertThat(allowed(scoped(() -> cards.listSlots(ADMIN))).get(0).card())
                .as("the client stays, and its slot is the one a Set button belongs on again")
                .isNull();
    }

    // =====================================================================

    private static RateCardEntryInput allRoles(String rate) {
        return new RateCardEntryInput(
                RateCardScope.ALL_ROLES, null, null, new BigDecimal(rate), JANUARY);
    }

    private <T> T scoped(Supplier<T> call) {
        try (var ignored = CompanyContext.open(BERGSTROM)) {
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
