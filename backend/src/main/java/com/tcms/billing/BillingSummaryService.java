package com.tcms.billing;

import com.tcms.project.GateResult;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.project.ProjectService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /billing/summary} — {@code billing.html}'s four tiles.
 *
 * <h2>Two of the four are arrays, and it is a disagreement with the mockup rather than a slip</h2>
 *
 * <p>019 D-l. {@code invoicedYearToDate} and {@code draftableNow} carry one entry per currency. The
 * mockup draws a single {@code €52.4k} tile while its own rate-card table carries a EUR client and a
 * CHF client two rows apart; that is a single-currency render of a multi-currency company, and a
 * number summed across two currencies is wrong in both of them. Plan 009's precedence rule decides
 * it: on what a screen shows the mockup wins, on what the system does the plan wins, and <em>what is
 * this company's invoiced total</em> is a question about the system.
 *
 * <h2>The window is a calendar month</h2>
 *
 * <p>{@code draftableNow} is computed over the month containing {@code on}, because that is the
 * period {@code invoice.html} draws — <em>Period 1–30 Sep 2026</em> — and an invoice period is not a
 * timesheet period. {@code billing} may not import {@code time} and so cannot ask {@code Periods}
 * what a fortnight is, and it should not: a semi-monthly approval cycle is how hours are signed for,
 * and a month is how they are billed. The year-to-date figure is always the calendar year containing
 * that day.
 *
 * <h2>What it does not do</h2>
 *
 * <p>It draws no invoice and reserves nothing. {@code draftableNow} is what a draft raised right now
 * <em>would</em> be worth, computed the same way {@link InvoiceService} computes one — the same
 * exclusion of hours already on an issued invoice, and the same rate ladder — so the tile and the
 * document cannot disagree.
 */
@Service
public class BillingSummaryService {

    private static final String READ = "billing:read";

    private static final int MONEY_SCALE = 2;

    private final InvoiceRepository invoices;
    private final ProjectRepository projects;
    private final ApprovedTimeFacts hours;
    private final RateCardService rateCards;
    private final PricingService pricing;
    private final PersonRoles personRoles;
    private final ProjectService access;
    private final Clock clock;

    BillingSummaryService(
            InvoiceRepository invoices,
            ProjectRepository projects,
            ApprovedTimeFacts hours,
            RateCardService rateCards,
            PricingService pricing,
            PersonRoles personRoles,
            ProjectService access,
            Clock clock) {

        this.invoices = invoices;
        this.projects = projects;
        this.hours = hours;
        this.rateCards = rateCards;
        this.pricing = pricing;
        this.personRoles = personRoles;
        this.access = access;
        this.clock = clock;
    }

    /** The four tiles, for a company that may have no clients at all and is not deficient for it. */
    @Transactional(readOnly = true)
    public GateResult<BillingSummaryView> summary(UUID userId, LocalDate on) {
        return access.readInCompany(userId, READ, () -> {
            LocalDate day = on == null ? LocalDate.now(clock) : on;
            LocalDate monthStart = day.withDayOfMonth(1);
            LocalDate monthEnd = day.withDayOfMonth(day.lengthOfMonth());

            List<Invoice> issuedThisYear =
                    invoices.findByStatusAndIssuedAtGreaterThanEqualAndIssuedAtLessThan(
                            InvoiceStatus.ISSUED, startOfYear(day), startOfYear(day.plusYears(1)));

            Map<String, BigDecimal> invoiced = new TreeMap<>();
            Set<UUID> billedClients = new HashSet<>();
            for (Invoice invoice : issuedThisYear) {
                invoiced.merge(
                        invoice.getCurrency(),
                        invoice.getTotalAmount() == null ? BigDecimal.ZERO
                                : invoice.getTotalAmount(),
                        BigDecimal::add);
                billedClients.add(invoice.getClientId());
            }

            Draftable draftable = draftable(monthStart, monthEnd);

            return new BillingSummaryView(
                    moneyOf(invoiced),
                    moneyOf(draftable.byCurrency()),
                    draftable.unapprovedHours(),
                    billedClients.size());
        });
    }

    /* ----------------------------------------------------------------- innards --- */

    /** What this month's unbilled approved hours are worth per currency, and what is not approved. */
    private record Draftable(Map<String, BigDecimal> byCurrency, BigDecimal unapprovedHours) {
    }

    private Draftable draftable(LocalDate from, LocalDate to) {
        List<Project> all = projects.findAllByOrderByNameAsc();
        Map<UUID, UUID> clientOf = new HashMap<>();
        for (Project project : all) {
            if (project.getClientId() != null) {
                clientOf.put(project.getId(), project.getClientId());
            }
        }
        /*
          Every project, not only the billable ones, for the unapproved figure: billing.html labels
          that tile "excluded from every draft", and an unapproved hour on a client-less project is
          excluded from every draft in the strongest possible sense. The priced half below is
          narrowed to projects that have a client, because those are the only hours a draft could
          ever offer.
        */
        List<UUID> everyProject = all.stream().map(Project::getId).toList();
        BigDecimal unapproved = BigDecimal.ZERO;
        for (ApprovedTimeFacts.PersonHours row : hours.unapprovedOn(everyProject, from, to)) {
            unapproved = unapproved.add(row.hours());
        }

        List<UUID> billable = new ArrayList<>(clientOf.keySet());
        List<ApprovedTimeFacts.ApprovedEntry> entries = hours.draftableOn(billable, from, to);
        if (entries.isEmpty()) {
            return new Draftable(Map.of(), unapproved);
        }
        Map<UUID, RateCardService.PricedCard> cardByClient = new HashMap<>();
        for (UUID clientId : new HashSet<>(clientOf.values())) {
            rateCards.cardFor(clientId).ifPresent(card -> cardByClient.put(clientId, card));
        }
        Set<UUID> people = new HashSet<>();
        for (ApprovedTimeFacts.ApprovedEntry entry : entries) {
            people.add(entry.personId());
        }
        PersonRoles.Held held = personRoles.heldBy(people);

        Map<String, BigDecimal> byCurrency = new TreeMap<>();
        for (ApprovedTimeFacts.ApprovedEntry entry : entries) {
            RateCardService.PricedCard card = cardByClient.get(clientOf.get(entry.projectId()));
            if (card == null) {
                // No card is not a zero and not an error — the hours are recorded and unpriceable,
                // which is the NO_RATE bucket on a draft and simply absent from a currency total.
                continue;
            }
            Optional<PricingService.Priced> priced = pricing.rateFor(
                    card.entries(), held, entry.personId(), entry.projectId(), entry.spentOn());
            if (priced.isEmpty()) {
                continue;
            }
            byCurrency.merge(
                    card.currency(),
                    priced.get().rate().multiply(entry.hours()),
                    BigDecimal::add);
        }
        return new Draftable(byCurrency, unapproved);
    }

    /** One {@link Money} per currency, in a stable order so two reads render the same tile. */
    private static List<Money> moneyOf(Map<String, BigDecimal> byCurrency) {
        return byCurrency.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .map(entry -> new Money(
                        entry.getValue().setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                        entry.getKey()))
                .toList();
    }

    /**
     * Midnight UTC on 1 January of that day's year.
     *
     * <p>UTC because {@code issued_at} is a {@code TIMESTAMPTZ} and this product has no company time
     * zone — the same statement {@link InvoiceNumbers#render} makes about the year in an invoice
     * number, and they have to agree or a number could say 2027 while the tile counts it in 2026.
     */
    private static Instant startOfYear(LocalDate day) {
        return day.withDayOfYear(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
