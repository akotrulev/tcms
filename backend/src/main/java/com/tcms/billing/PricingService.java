package com.tcms.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The rate ladder (019 D-d), and <strong>the only place in this product that answers "what is this
 * hour worth"</strong>.
 *
 * <p>Most specific first, each as of the hour's own {@code spentOn}:
 *
 * <ol>
 *   <li>a {@code PERSON} entry naming the person who logged it;
 *   <li>a {@code ROLE} entry naming a role that person actually holds <em>on that project</em>;
 *   <li>the {@code ALL_ROLES} entry.
 * </ol>
 *
 * <p><strong>Effective on or before the day worked, never the day invoiced.</strong> Within one
 * rung, an entry added in July does not reprice June: the candidates are filtered to those whose
 * {@code effectiveFrom} is on or before {@code spentOn}, and the latest surviving one wins. That is
 * the same promise {@code invoice_line.rate_applied} keeps one step further downstream, where the
 * number is frozen so a card edited next month cannot reprice an invoice sent last month.
 *
 * <p><strong>Where two {@code ROLE} entries both match, the higher rate wins.</strong> That is
 * deterministic by construction and it is a decision rather than a coin toss: a person granted both
 * Test Lead and Test Engineer on one project is being billed as a Test Lead, and that is the number
 * the client agreed. <em>Rejected:</em> refusing the hour as ambiguous, which turns a rate-card
 * configuration a Finance user could not have anticipated into hours that silently fail to bill —
 * the failure would appear as an unexplained gap on an invoice rather than as anything anybody could
 * act on.
 *
 * <p><strong>No card, or no entry effective that day, is not an error.</strong> It returns
 * {@link Optional#empty()} and the hour lands in the {@code NO_RATE} exclusion bucket, counted and
 * named (019 D-g). {@code billing.html} says so on the screen: <em>"Without a card, approved hours
 * are still recorded — they just cannot be priced."</em>
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p><strong>The label.</strong> 019 T3's draft put {@code appliesTo} on {@link Priced}, which would
 * have made this class resolve a person's display name and a role's name in order to answer an
 * arithmetic question — and would have put {@code PeopleDirectory} in the one class that must be
 * readable as a rule. {@link EntryLabels} owns the label instead, and it is the same resolution the
 * rate-card table renders with, so a line and the card it came from cannot disagree about what an
 * entry is called.
 *
 * <p><strong>The lookups.</strong> The card's entries and the people's roles come in as values
 * rather than being fetched here, so pricing a fortnight of a client's work asks each question once
 * instead of once per hour — and so this rule is testable without a database.
 */
@Service
public class PricingService {

    /**
     * A matched rung.
     *
     * <p>The {@link #scope} and the two ids are what an invoice line is grouped by and what its
     * label is resolved from; the {@link #rate} is what the hour costs. No amount and no currency:
     * the currency is the card's, the amount is hours times this, and both belong to the caller that
     * knows how many hours it is pricing.
     */
    record Priced(BigDecimal rate, RateCardScope scope, UUID personId, UUID roleId) {
    }

    /**
     * Later effective date first; where two entries share one, the higher rate.
     *
     * <p>The tie-break is not decoration. Two entries with the same {@code effectiveFrom} on one
     * rung is a rate card a Finance user can build on one screen, and picking whichever row the
     * database returned first would price the same hour two ways on two reads of the same draft.
     */
    private static final Comparator<RateCardEntry> MOST_RECENT_THEN_HIGHEST =
            Comparator.comparing(RateCardEntry::getEffectiveFrom)
                    .thenComparing(RateCardEntry::getHourlyRate);

    /**
     * The ladder, for one hour.
     *
     * @param cardEntries every entry on the client's card, in any order
     * @param held        the roles each person is staffed with, from {@link PersonRoles}
     * @param personId    who logged the hour
     * @param projectId   the project it was logged against — an {@code ISSUE} entry always has one
     * @param spentOn     the day worked, which is the date the whole ladder is resolved as of
     */
    Optional<Priced> rateFor(
            List<RateCardEntry> cardEntries,
            PersonRoles.Held held,
            UUID personId,
            UUID projectId,
            LocalDate spentOn) {

        List<RateCardEntry> effective = cardEntries.stream()
                .filter(entry -> !entry.getEffectiveFrom().isAfter(spentOn))
                .toList();

        Optional<RateCardEntry> person = effective.stream()
                .filter(entry -> entry.getScope() == RateCardScope.PERSON)
                .filter(entry -> personId.equals(entry.getPersonId()))
                .max(MOST_RECENT_THEN_HIGHEST);
        if (person.isPresent()) {
            return person.map(PricingService::priced);
        }

        Optional<RateCardEntry> role = highestMatchingRole(effective, held.on(personId, projectId));
        if (role.isPresent()) {
            return role.map(PricingService::priced);
        }

        return effective.stream()
                .filter(entry -> entry.getScope() == RateCardScope.ALL_ROLES)
                .max(MOST_RECENT_THEN_HIGHEST)
                .map(PricingService::priced);
    }

    /**
     * The middle rung, in two steps that are not interchangeable.
     *
     * <p>First, <em>per role</em>, the entry effective on the day worked — because a role whose rate
     * was raised in July must be priced at July's number for a July hour and at January's for a
     * January one. Only then, <em>across</em> roles, the higher rate: 019 D-d's rule is about two
     * roles, not about two dates, and applying it first would let a superseded rate for one role beat
     * the current rate for another.
     */
    private static Optional<RateCardEntry> highestMatchingRole(
            List<RateCardEntry> effective, Set<UUID> heldRoleIds) {

        if (heldRoleIds.isEmpty()) {
            return Optional.empty();
        }
        Map<UUID, RateCardEntry> currentPerRole = new HashMap<>();
        for (RateCardEntry entry : effective) {
            if (entry.getScope() != RateCardScope.ROLE || !heldRoleIds.contains(entry.getRoleId())) {
                continue;
            }
            currentPerRole.merge(entry.getRoleId(), entry, (existing, candidate) ->
                    MOST_RECENT_THEN_HIGHEST.compare(candidate, existing) > 0 ? candidate : existing);
        }
        return currentPerRole.values().stream()
                .max(Comparator.comparing(RateCardEntry::getHourlyRate)
                        // Two roles at one rate is a tie the client cannot tell apart in money, and
                        // any answer is correct — but it must be the SAME answer on every read, or
                        // one draft renders two labels for one line across two page loads.
                        .thenComparing(RateCardEntry::getId));
    }

    private static Priced priced(RateCardEntry entry) {
        return new Priced(
                entry.getHourlyRate(), entry.getScope(), entry.getPersonId(), entry.getRoleId());
    }
}
