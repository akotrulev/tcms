package com.tcms.billing;

import com.tcms.api.ClientRef;
import com.tcms.project.ClientDirectory;
import com.tcms.project.GateResult;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.CompanyContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rate cards — one per client, and the currency that makes an invoice's currency its client's.
 *
 * <h2>Two keys, and the split is the roles spec's rather than this module's</h2>
 *
 * <ul>
 *   <li>{@code ratecard:read} — the reads. Finance, Company Admin and Instance Admin hold it and
 *       nobody else, which is what makes a Test Engineer with {@code time:log} unable to see what
 *       their hours cost (story T1).</li>
 *   <li>{@code ratecard:manage} — the three writes.</li>
 * </ul>
 *
 * <p>Both are COMPANY scope, so every gate here is {@code readInCompany} or {@code createGranted}
 * and none of them names a project. A rate card is a fact about a client and a client carries no
 * scope (scope D1).
 *
 * <h2>Three refusals, each a different status on purpose</h2>
 *
 * <ul>
 *   <li><strong>403</strong> — the caller lacks the key.</li>
 *   <li><strong>404</strong> — no such card, in this company or at all, folded together because
 *       telling them apart makes the endpoint an oracle for ids in another client's tenancy
 *       (§6t).</li>
 *   <li><strong>409</strong> — the client already has a card ({@code uq_rate_card_client}). Not a
 *       field the caller can fix and not a concealment question, so it is neither of the two above —
 *       the same shape of answer {@code DELETE /clients/{clientId}} gives (018 D-k).</li>
 * </ul>
 *
 * <h2>A delete is never refused because an invoice exists</h2>
 *
 * <p>That is the difference from {@code DELETE /clients/{clientId}}, and it follows from 019 D-c: an
 * issued invoice does not reference this card, because its lines carry the rate they were priced at
 * as a number. Removing the card stops future hours being priceable and changes nothing already
 * sent.
 */
@Service
public class RateCardService {

    private static final String READ = "ratecard:read";
    private static final String MANAGE = "ratecard:manage";

    /**
     * ISO 4217, upper case — the same expression {@code ck_rate_card_currency} holds.
     *
     * <p>Checked here as well as in the database so the caller gets a field error rather than a
     * constraint violation surfacing as a 500. The database keeps its copy because it is the layer
     * no writer can go around, including the demo seeder.
     */
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    private final RateCardRepository cards;
    private final RateCardEntryRepository entries;
    private final ClientDirectory clients;
    private final EntryLabels labels;
    private final ProjectService access;
    private final Clock clock;

    RateCardService(
            RateCardRepository cards,
            RateCardEntryRepository entries,
            ClientDirectory clients,
            EntryLabels labels,
            ProjectService access,
            Clock clock) {
        this.cards = cards;
        this.entries = entries;
        this.clients = clients;
        this.labels = labels;
        this.access = access;
        this.clock = clock;
    }

    /* ------------------------------------------------------------------- reads --- */

    /**
     * {@code GET /rate-cards} — one slot per ACTIVE client, card or {@code null}.
     *
     * <p><strong>The population is the clients and not the cards.</strong> A client with no card is
     * the state every new client is in, and {@code billing.html} draws it as a row reading <em>no
     * rate card yet</em> beside the {@code Set} button that fixes it — a table built from the cards
     * alone would give a Finance user no way to reach that button. Answers {@code []} for a company
     * with no clients, which is the owner's own case and a complete answer (scope D1 rule 2).
     */
    @Transactional(readOnly = true)
    public GateResult<List<RateCardSlotView>> listSlots(UUID userId) {
        return access.readInCompany(userId, READ, () -> {
            List<ClientRef> active = clients.activeClients();
            Map<UUID, RateCard> byClient = new HashMap<>();
            for (RateCard card : cardsFor(active)) {
                byClient.put(card.getClientId(), card);
            }
            Map<UUID, List<RateCardEntry>> rungs = entriesOf(byClient.values());

            List<RateCardSlotView> slots = new ArrayList<>(active.size());
            for (ClientRef client : active) {
                RateCard card = byClient.get(client.id());
                slots.add(new RateCardSlotView(
                        client,
                        card == null ? null : viewOf(card, client, rungs.getOrDefault(
                                card.getId(), List.of()))));
            }
            return List.copyOf(slots);
        });
    }

    /** {@code GET /rate-cards/{rateCardId}} — the card and every rung on it. */
    @Transactional(readOnly = true)
    public GateResult<RateCardView> read(UUID userId, UUID rateCardId) {
        if (!access.holdsInCompany(userId, READ)) {
            return new GateResult.Denied<>();
        }
        Optional<RateCard> found = cards.findById(rateCardId);
        if (found.isEmpty()) {
            return new GateResult.Unreachable<>();
        }
        return new GateResult.Allowed<>(viewOf(found.get()));
    }

    /* ------------------------------------------------------------------ writes --- */

    /** {@code POST /rate-cards} — {@code ratecard:manage}. One per client. */
    @Transactional
    public GateResult<Outcome<RateCardView>> create(UUID userId, RateCardWriteRequest submitted) {
        return access.createGranted(userId, MANAGE, () -> {
            UUID clientId = submitted == null ? null : submitted.clientId();
            /*
              A client this company cannot see is a 400 on `clientId` and never a 404 and never a
              500 from the foreign key. fk_rate_card_client is on `id` alone and referential
              integrity is not subject to RLS, so another company's client would satisfy the
              constraint and price one company's work against another's customer; existsInCompany
              runs under RLS and is the check the constraint is not (018 D-l, §6t).
            */
            if (clientId == null || !clients.existsInCompany(clientId)) {
                return new Outcome.Invalid<RateCardView>(Map.of(
                        "clientId", List.of("That client does not exist.")));
            }
            Optional<Map<String, List<String>>> errors = validate(submitted);
            if (errors.isPresent()) {
                return new Outcome.Invalid<RateCardView>(errors.get());
            }
            if (cards.findByClientId(clientId).isPresent()) {
                return new Outcome.Conflict<RateCardView>(
                        "That client already has a rate card",
                        "A client has one rate card, and its entries are how a rate changes over "
                                + "time. Edit the existing card instead.");
            }
            Instant now = clock.instant();
            RateCard card = cards.save(RateCard.create(
                    UUID.randomUUID(), CompanyContext.require(), clientId,
                    submitted.currency(), now));
            writeEntries(card, submitted.entries(), now);
            return new Outcome.Written<>(viewOf(card));
        });
    }

    /**
     * {@code PUT /rate-cards/{rateCardId}} — a <strong>full replacement, entries included</strong>.
     *
     * <p>The entries sent are the entries the card then has, and one omitted is one removed:
     * {@code PUT /clients/{clientId}} is the same shape for the same reason, because the screen
     * shows and submits the whole card.
     *
     * <p><strong>It reprices nothing already issued.</strong> An {@code invoice_line} carries its
     * rate as a number, so a card doubled today leaves last month's invoice exactly as it was sent.
     * It does change every DRAFT invoice for this client on that draft's next read, which is the
     * same decision (019 D-c) seen from the other side.
     */
    @Transactional
    public GateResult<Outcome<RateCardView>> update(
            UUID userId, UUID rateCardId, RateCardWriteRequest submitted) {

        return access.createGranted(userId, MANAGE, () -> {
            Optional<RateCard> found = cards.findById(rateCardId);
            if (found.isEmpty()) {
                return new Outcome.Missing<RateCardView>();
            }
            Optional<Map<String, List<String>>> errors = validate(submitted);
            if (errors.isPresent()) {
                return new Outcome.Invalid<RateCardView>(errors.get());
            }
            /*
              `clientId` on the body is ignored, which the contract states and RateCard.clientId's
              `updatable = false` enforces. Moving a card between clients would reprice every draft
              on both sides, and no screen asks for it.
            */
            RateCard card = found.get();
            Instant now = clock.instant();
            card.apply(submitted.currency(), now);
            entries.deleteAll(entries.findByRateCardIdOrderByEffectiveFromDesc(card.getId()));
            entries.flush();
            writeEntries(card, submitted.entries(), now);
            return new Outcome.Written<>(viewOf(cards.save(card)));
        });
    }

    /**
     * {@code DELETE /rate-cards/{rateCardId}} — {@code ratecard:manage}, and never refused because
     * an invoice exists.
     *
     * <p>The entries go with the card ({@code ON DELETE CASCADE}). They are deleted here as well so
     * the persistence context and the database agree within this transaction rather than only after
     * it — a subsequent read in the same request would otherwise see rows the database has already
     * removed.
     */
    @Transactional
    public GateResult<Outcome<Void>> delete(UUID userId, UUID rateCardId) {
        return access.createGranted(userId, MANAGE, () -> {
            Optional<RateCard> found = cards.findById(rateCardId);
            if (found.isEmpty()) {
                return new Outcome.Missing<Void>();
            }
            entries.deleteAll(entries.findByRateCardIdOrderByEffectiveFromDesc(rateCardId));
            cards.delete(found.get());
            return new Outcome.Removed<Void>();
        });
    }

    /* ------------------------------------------------------- what pricing needs --- */

    /**
     * The card that prices this project's work, and its rungs — {@link Optional#empty()} when the
     * project has no client, or its client has no card.
     *
     * <p>Package-private and not on the wire: {@link BudgetService} needs it to value a budget, and
     * the invoice surface needs it to compose a draft. Neither is a money read the caller has not
     * already been gated for, and this method gates nothing — it is a lookup, and callers say so.
     */
    Optional<PricedCard> cardFor(UUID clientId) {
        if (clientId == null) {
            return Optional.empty();
        }
        return cards.findByClientId(clientId).map(card -> new PricedCard(
                card.getCurrency(), entries.findByRateCardIdOrderByEffectiveFromDesc(card.getId())));
    }

    /** A card reduced to what pricing asks of it: the currency, and the rungs. */
    record PricedCard(String currency, List<RateCardEntry> entries) {
    }

    /* ----------------------------------------------------------------- innards --- */

    private List<RateCard> cardsFor(List<ClientRef> clientRefs) {
        if (clientRefs.isEmpty()) {
            return List.of();
        }
        return cards.findByClientIdIn(clientRefs.stream().map(ClientRef::id).toList());
    }

    private Map<UUID, List<RateCardEntry>> entriesOf(java.util.Collection<RateCard> forCards) {
        if (forCards.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<RateCardEntry>> byCard = new HashMap<>();
        List<RateCardEntry> rows = entries.findByRateCardIdInOrderByEffectiveFromDesc(
                forCards.stream().map(RateCard::getId).toList());
        for (RateCardEntry row : rows) {
            byCard.computeIfAbsent(row.getRateCardId(), card -> new ArrayList<>()).add(row);
        }
        return byCard;
    }

    private void writeEntries(RateCard card, List<RateCardEntryInput> submitted, Instant now) {
        if (submitted == null) {
            return;
        }
        for (RateCardEntryInput input : submitted) {
            entries.save(RateCardEntry.create(
                    UUID.randomUUID(),
                    card.getCompanyId(),
                    card.getId(),
                    input.scope(),
                    input.scope() == RateCardScope.PERSON ? input.personId() : null,
                    input.scope() == RateCardScope.ROLE ? input.roleId() : null,
                    input.hourlyRate(),
                    input.effectiveFrom(),
                    now));
        }
    }

    private RateCardView viewOf(RateCard card) {
        String name = clients.namesOf(List.of(card.getClientId())).get(card.getClientId());
        return viewOf(
                card,
                new ClientRef(card.getClientId(), name),
                entries.findByRateCardIdOrderByEffectiveFromDesc(card.getId()));
    }

    private RateCardView viewOf(RateCard card, ClientRef client, List<RateCardEntry> rungs) {
        EntryLabels.Labels resolved = labels.forEntries(rungs);
        List<RateCardEntryView> views = new ArrayList<>(rungs.size());
        for (RateCardEntry rung : rungs) {
            views.add(new RateCardEntryView(
                    rung.getId(),
                    rung.getScope(),
                    rung.getPersonId(),
                    rung.getRoleId(),
                    rung.getHourlyRate(),
                    rung.getEffectiveFrom(),
                    resolved.of(rung.getScope(), rung.getPersonId(), rung.getRoleId())));
        }
        return new RateCardView(card.getId(), client, card.getCurrency(), List.copyOf(views));
    }

    /**
     * Every field error a card body can carry.
     *
     * <p>The entry shape is checked here as well as by {@code ck_rate_card_entry_shape} for the
     * reason the currency is: the caller is told which field is wrong, and the database is what no
     * writer can go around.
     */
    private Optional<Map<String, List<String>>> validate(RateCardWriteRequest submitted) {
        if (submitted == null) {
            return Optional.of(Map.of("currency", List.of("A rate card needs a currency.")));
        }
        String currency = submitted.currency();
        if (currency == null || !CURRENCY.matcher(currency).matches()) {
            return Optional.of(Map.of(
                    "currency", List.of("A currency is a three-letter ISO 4217 code, upper case.")));
        }
        List<RateCardEntryInput> rungs = submitted.entries();
        if (rungs == null) {
            return Optional.empty();
        }
        for (int index = 0; index < rungs.size(); index++) {
            Optional<String> wrong = validateEntry(rungs.get(index));
            if (wrong.isPresent()) {
                return Optional.of(Map.of("entries", List.of(
                        "Entry " + (index + 1) + ": " + wrong.get())));
            }
        }
        return Optional.empty();
    }

    private Optional<String> validateEntry(RateCardEntryInput input) {
        if (input == null || input.scope() == null) {
            return Optional.of("an entry needs a scope.");
        }
        if (input.effectiveFrom() == null) {
            return Optional.of("an entry needs an effective date.");
        }
        BigDecimal rate = input.hourlyRate();
        if (rate == null || rate.signum() < 0) {
            return Optional.of("an hourly rate cannot be negative.");
        }
        return switch (input.scope()) {
            case PERSON -> input.personId() == null
                    ? Optional.of("a PERSON entry has to name a person.") : Optional.empty();
            case ROLE -> input.roleId() == null
                    ? Optional.of("a ROLE entry has to name a role.") : Optional.empty();
            case ALL_ROLES -> Optional.empty();
        };
    }
}
