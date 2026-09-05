package com.tcms.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rate ladder (019 D-d), which is the one rule in this module that decides money.
 *
 * <p><strong>No Spring context and no database.</strong> {@link PricingService} takes the card's
 * entries and the people's roles as values rather than fetching them, precisely so this can be a
 * question about arithmetic — and a rule that only runs when Docker is up is one that gets skipped
 * exactly when somebody is in a hurry. Where those values come from is
 * {@code BudgetBurnTest}'s subject, end to end against real rows.
 *
 * <p><strong>UUID prefix {@code 50}, verified against the tree rather than assumed.</strong> 018
 * records what assuming costs: {@code fa} and {@code fb} looked free, were not, and the suite failed
 * on a {@code project_pkey} duplicate naming neither class. Nothing here reaches a database, so the
 * ids are only labels — they are namespaced anyway, because the next class to grep for a free prefix
 * has no way to tell which of them are.
 */
@DisplayName("Pricing — person beats role beats all-roles, as of the day worked (019 D-d)")
class PricingTest {

    private static final UUID CARD = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID COMPANY = UUID.fromString("50001000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("50002000-0000-4000-8000-000000000001");
    private static final UUID OTHER_PROJECT =
            UUID.fromString("50002000-0000-4000-8000-000000000002");
    private static final UUID MIRA = UUID.fromString("50003000-0000-4000-8000-000000000001");
    private static final UUID TOMAS = UUID.fromString("50003000-0000-4000-8000-000000000002");
    private static final UUID TEST_LEAD = UUID.fromString("50004000-0000-4000-8000-000000000001");
    private static final UUID TEST_ENGINEER =
            UUID.fromString("50004000-0000-4000-8000-000000000002");

    private static final LocalDate JANUARY = LocalDate.of(2026, 1, 1);
    private static final LocalDate JULY = LocalDate.of(2026, 7, 1);
    private static final LocalDate A_JUNE_DAY = LocalDate.of(2026, 6, 15);
    private static final LocalDate A_JULY_DAY = LocalDate.of(2026, 7, 15);

    private final PricingService pricing = new PricingService();

    @Test
    @DisplayName("a PERSON entry beats a ROLE entry, which beats ALL_ROLES")
    void theLadderIsWalkedMostSpecificFirst() {
        List<RateCardEntry> card = List.of(
                entry(RateCardScope.ALL_ROLES, null, null, "95.00", JANUARY),
                entry(RateCardScope.ROLE, null, TEST_LEAD, "120.00", JANUARY),
                entry(RateCardScope.PERSON, MIRA, null, "140.00", JULY));

        assertThat(rate(card, staffed(MIRA, PROJECT, TEST_LEAD), MIRA, A_JULY_DAY))
                .as("Mira's own entry is effective in July, so it is what her July hour costs")
                .isEqualByComparingTo("140.00");

        assertThat(rate(card, staffed(MIRA, PROJECT, TEST_LEAD), MIRA, A_JUNE_DAY))
                .as("in June her entry is not yet effective, so the ladder falls to her role")
                .isEqualByComparingTo("120.00");

        assertThat(rate(card, staffed(TOMAS, PROJECT), TOMAS, A_JULY_DAY))
                .as("Tomas is staffed with no role on this project, so the card's floor applies")
                .isEqualByComparingTo("95.00");
    }

    @Test
    @DisplayName("a ROLE entry matches only a role the person holds ON THAT PROJECT")
    void aRoleEntryOnlyMatchesARoleHeldOnThatProject() {
        List<RateCardEntry> card = List.of(
                entry(RateCardScope.ALL_ROLES, null, null, "95.00", JANUARY),
                entry(RateCardScope.ROLE, null, TEST_LEAD, "120.00", JANUARY));

        PersonRoles.Held held = staffed(MIRA, OTHER_PROJECT, TEST_LEAD);

        assertThat(rate(card, held, MIRA, A_JULY_DAY))
                .as("""
                        She is a Test Lead somewhere else. Pricing her hour on THIS project at the \
                        Test Lead rate would bill the client for a seniority nobody gave her here, \
                        and it is the kind of wrong number an invoice carries to a customer.""")
                .isEqualByComparingTo("95.00");
    }

    @Test
    @DisplayName("019 D-d — where two ROLE entries both match, the higher rate wins")
    void twoMatchingRolesArePricedAtTheHigherRate() {
        List<RateCardEntry> card = List.of(
                entry(RateCardScope.ROLE, null, TEST_ENGINEER, "95.00", JANUARY),
                entry(RateCardScope.ROLE, null, TEST_LEAD, "120.00", JANUARY));

        /*
          Deterministic by construction and a decision rather than a coin toss: a person granted both
          Test Lead and Test Engineer on one project is being billed as a Test Lead, which is the
          number the client agreed. Refusing the hour as ambiguous was rejected — it turns a rate
          card a Finance user could not have anticipated into hours that silently fail to bill.
        */
        assertThat(rate(card, staffed(MIRA, PROJECT, TEST_LEAD, TEST_ENGINEER), MIRA, A_JULY_DAY))
                .isEqualByComparingTo("120.00");
    }

    @Test
    @DisplayName("per role the latest effective entry wins BEFORE the higher rate does")
    void theEffectiveDateIsResolvedPerRoleAndOnlyThenTheHigherRate() {
        List<RateCardEntry> card = List.of(
                entry(RateCardScope.ROLE, null, TEST_LEAD, "150.00", JANUARY),
                entry(RateCardScope.ROLE, null, TEST_LEAD, "90.00", JULY),
                entry(RateCardScope.ROLE, null, TEST_ENGINEER, "110.00", JANUARY));

        PersonRoles.Held held = staffed(MIRA, PROJECT, TEST_LEAD, TEST_ENGINEER);

        assertThat(rate(card, held, MIRA, A_JUNE_DAY))
                .as("in June the Test Lead rate is still 150, and it is the higher of the two")
                .isEqualByComparingTo("150.00");

        assertThat(rate(card, held, MIRA, A_JULY_DAY))
                .as("""
                        In July the client renegotiated the Test Lead rate DOWN to 90, so the Test \
                        Engineer rate is now the higher of the two roles she holds. Applying \
                        "higher wins" across all matching rows first would have kept billing the \
                        superseded 150 forever — the rule is about two roles, not about two \
                        dates.""")
                .isEqualByComparingTo("110.00");
    }

    @Test
    @DisplayName("019 D-g — no entry effective that day is the NO_RATE bucket, not an error")
    void anHourWithNoEffectiveEntryIsNotPriced() {
        List<RateCardEntry> card = List.of(
                entry(RateCardScope.ALL_ROLES, null, null, "95.00", JULY));

        assertThat(pricing.rateFor(card, staffed(MIRA, PROJECT), MIRA, PROJECT, A_JUNE_DAY))
                .as("""
                        The card starts in July and the hour was worked in June. That hour is still \
                        recorded and still approved — it simply cannot be priced, and it lands in \
                        the NO_RATE exclusion bucket where it is counted and named. An empty \
                        Optional here is what makes that bucket possible; a zero would put a \
                        free hour on an invoice.""")
                .isEmpty();

        assertThat(pricing.rateFor(List.of(), staffed(MIRA, PROJECT), MIRA, PROJECT, A_JULY_DAY))
                .as("a card with no entries prices nothing, which is the state a new card is in")
                .isEmpty();
    }

    @Test
    @DisplayName("roles spec §A1.1 — a project-scoped grant REPLACES the inherited one")
    void aProjectScopedGrantReplacesTheInheritedRole() {
        List<RateCardEntry> card = List.of(
                entry(RateCardScope.ROLE, null, TEST_LEAD, "120.00", JANUARY),
                entry(RateCardScope.ROLE, null, TEST_ENGINEER, "95.00", JANUARY));

        PersonRoles.Held demoted = new PersonRoles.Held(
                Map.of(MIRA, Map.of(PROJECT, Set.of(TEST_ENGINEER))),
                Map.of(MIRA, Set.of(TEST_LEAD)));

        /*
          §A1.1 is the one place in this model where adding a grant REMOVES a permission, and the
          same override has to hold for a rate or a demoted colleague keeps being billed at the
          higher number. Unioning the two would produce 120 here, which reads as correct and is a
          client being overcharged.
        */
        assertThat(rate(card, demoted, MIRA, A_JULY_DAY)).isEqualByComparingTo("95.00");

        PersonRoles.Held inheritedOnly = new PersonRoles.Held(
                Map.of(), Map.of(MIRA, Set.of(TEST_LEAD)));
        assertThat(rate(card, inheritedOnly, MIRA, A_JULY_DAY))
                .as("with no grant naming the project, the company-wide role is her role there")
                .isEqualByComparingTo("120.00");
    }

    @Test
    @DisplayName("the matched rung carries no amount and no currency")
    void thePricedRungIsARateAndNotAnAmount() {
        List<RateCardEntry> card = List.of(
                entry(RateCardScope.PERSON, MIRA, null, "140.00", JANUARY));

        PricingService.Priced priced = pricing
                .rateFor(card, staffed(MIRA, PROJECT), MIRA, PROJECT, A_JULY_DAY)
                .orElseThrow();

        assertThat(priced.scope()).isEqualTo(RateCardScope.PERSON);
        assertThat(priced.personId()).isEqualTo(MIRA);
        assertThat(priced.roleId()).isNull();
        assertThat(priced.rate()).isEqualByComparingTo("140.00");
    }

    // =====================================================================

    private BigDecimal rate(
            List<RateCardEntry> card, PersonRoles.Held held, UUID personId, LocalDate spentOn) {

        Optional<PricingService.Priced> priced =
                pricing.rateFor(card, held, personId, PROJECT, spentOn);
        assertThat(priced).as("the ladder found no rung for " + spentOn).isPresent();
        return priced.orElseThrow().rate();
    }

    private static PersonRoles.Held staffed(UUID personId, UUID projectId, UUID... roleIds) {
        return new PersonRoles.Held(Map.of(personId, Map.of(projectId, Set.of(roleIds))), Map.of());
    }

    private static RateCardEntry entry(
            RateCardScope scope, UUID personId, UUID roleId, String rate, LocalDate effectiveFrom) {

        return RateCardEntry.create(
                UUID.randomUUID(), COMPANY, CARD, scope, personId, roleId,
                new BigDecimal(rate), effectiveFrom, Instant.EPOCH);
    }
}
