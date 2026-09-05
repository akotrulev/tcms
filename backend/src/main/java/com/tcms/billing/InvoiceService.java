package com.tcms.billing;

import com.tcms.api.ClientRef;
import com.tcms.api.UserRef;
import com.tcms.project.ClientContactRef;
import com.tcms.project.ClientDirectory;
import com.tcms.project.GateResult;
import com.tcms.project.PeopleDirectory;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.CompanyContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The invoice lifecycle, and the decision the whole module rests on (019 D-c).
 *
 * <h2>A draft stores no lines</h2>
 *
 * <p>{@link #draft} writes <strong>one row</strong>: client, period, terms, {@code DRAFT}. Its lines
 * are computed by {@link #compose} on every read, from the approved entries that exist <em>now</em>.
 * That is what makes {@code invoice.html}'s central promise — <em>"Approve them and this draft
 * recalculates"</em> — true with no recalculate endpoint, no staleness flag, and no window in which
 * a draft disagrees with the timesheets behind it.
 *
 * <h2>Issuing is the one write, and the lock is therefore structural</h2>
 *
 * <p>{@link #issue} runs {@link #compose} one last time, persists the result as {@code invoice_line}
 * and {@code invoice_line_entry} rows, allocates the number and flips the status.
 * <strong>No operation on this surface updates an {@code invoice_line}</strong> — not because a
 * guard refuses one, but because none was built. The proof is not that a {@code PUT} returns 405; it
 * is that issuing, editing the rate card and re-reading returns a byte-identical line, which is what
 * {@code InvoiceLifecycleTest} asserts.
 *
 * <h2>The cost, stated rather than discovered</h2>
 *
 * <p>Two drafts open over an overlapping period for one client both offer the same hours, and
 * whichever issues first takes them: the second recomputes on its next read with those hours gone.
 * <em>Rejected:</em> reserving entries at draft time, which makes an open draft a lock on the
 * company's time data — a much worse failure than a recomputation somebody can see.
 *
 * <h2>Three refusals, each a different status on purpose</h2>
 *
 * <ul>
 *   <li><strong>403</strong> — the caller lacks the key. Three keys here and not one:
 *       {@code billing:read} for the reads, {@code billing:invoice} for drafting and discarding, and
 *       {@code billing:issue} for issuing, which is its own gate for the reason
 *       {@code release:publish} is separate from {@code release:manage} — it moves a state that
 *       cannot be moved back.</li>
 *   <li><strong>404</strong> — no such invoice, in this company or at all, folded together because
 *       telling them apart makes the endpoint an oracle for ids in another tenancy (§6t).</li>
 *   <li><strong>409</strong> — discarding an issued invoice, or issuing one twice. The caller may
 *       do it, the invoice exists, and it is not a field they can fix, which is exactly how
 *       {@code DELETE /clients/{clientId}} reasons about a client a project still names
 *       (018 D-k).</li>
 * </ul>
 */
@Service
public class InvoiceService {

    private static final String READ = "billing:read";
    private static final String INVOICE = "billing:invoice";
    private static final String ISSUE = "billing:issue";

    /** Every money figure is stored {@code NUMERIC(_,2)} and is served at the same scale. */
    private static final int MONEY_SCALE = 2;

    /** The contract's own default, applied here because the schema declares no {@code default:}. */
    private static final int DEFAULT_TERMS_DAYS = 30;

    /** What an entry whose person cannot be resolved is called — never an invented name. */
    private static final String UNRESOLVED = "Unknown";

    /** A total with nothing on it, at the scale every money figure on this surface is served at. */
    private static final BigDecimal ZERO_MONEY = new BigDecimal("0.00");

    private final InvoiceRepository invoices;
    private final InvoiceLineRepository lines;
    private final InvoiceLineEntryRepository lineEntries;
    private final ProjectRepository projects;
    private final ClientDirectory clients;
    private final PeopleDirectory people;
    private final ApprovedTimeFacts hours;
    private final IssueSubjectFacts subjects;
    private final RateCardService rateCards;
    private final PricingService pricing;
    private final PersonRoles personRoles;
    private final EntryLabels labels;
    private final BudgetService budgets;
    private final InvoiceNumbers numbers;
    private final ProjectService access;
    private final Clock clock;

    InvoiceService(
            InvoiceRepository invoices,
            InvoiceLineRepository lines,
            InvoiceLineEntryRepository lineEntries,
            ProjectRepository projects,
            ClientDirectory clients,
            PeopleDirectory people,
            ApprovedTimeFacts hours,
            IssueSubjectFacts subjects,
            RateCardService rateCards,
            PricingService pricing,
            PersonRoles personRoles,
            EntryLabels labels,
            BudgetService budgets,
            InvoiceNumbers numbers,
            ProjectService access,
            Clock clock) {

        this.invoices = invoices;
        this.lines = lines;
        this.lineEntries = lineEntries;
        this.projects = projects;
        this.clients = clients;
        this.people = people;
        this.hours = hours;
        this.subjects = subjects;
        this.rateCards = rateCards;
        this.pricing = pricing;
        this.personRoles = personRoles;
        this.labels = labels;
        this.budgets = budgets;
        this.numbers = numbers;
        this.access = access;
        this.clock = clock;
    }

    /* ------------------------------------------------------------------- reads --- */

    /**
     * {@code GET /invoices} — {@code billing:read}, newest period first, drafts and issued together.
     *
     * <p>{@code billing.html}'s panel renders two issued invoices and one draft in one list with the
     * draft outlined rather than filtered away, which is why {@code ALL} is the default and why the
     * status filter exists at all.
     *
     * <p><strong>Every draft in the list is composed.</strong> {@code Invoice.total},
     * {@code lineCount} and {@code approvedHours} are required on the contract and a draft stores
     * none of them, so the list pays the same computation the detail does, once per draft. The cost
     * is named rather than avoided: caching it would be the staleness flag 019 D-c exists to remove,
     * and a company has a handful of open drafts — the issued rows, which are the ones that
     * accumulate, cost one read of their stored lines.
     */
    @Transactional(readOnly = true)
    public GateResult<List<InvoiceView>> list(UUID userId, UUID clientId, InvoiceStatus status) {
        return access.readInCompany(userId, READ, () -> {
            List<Invoice> rows = clientId == null
                    ? invoices.findAllByOrderByPeriodStartDescCreatedAtDesc()
                    : invoices.findByClientIdOrderByPeriodStartDesc(clientId);

            List<InvoiceView> views = new ArrayList<>(rows.size());
            for (Invoice invoice : rows) {
                if (status != null && invoice.getStatus() != status) {
                    continue;
                }
                views.add(viewOf(invoice, compose(invoice)));
            }
            return List.copyOf(views);
        });
    }

    /**
     * {@code GET /invoices/{invoiceId}} — {@code billing:read}.
     *
     * <p>A draft's lines are computed now; an issued invoice's are the rows written when it was
     * issued. Both arrive in the same shape, and the difference is visible in {@code status} and in
     * the fact that an issued invoice's figures never move again.
     */
    @Transactional(readOnly = true)
    public GateResult<InvoiceDetailView> read(UUID userId, UUID invoiceId) {
        if (!access.holdsInCompany(userId, READ)) {
            return new GateResult.Denied<>();
        }
        Optional<Invoice> found = invoices.findById(invoiceId);
        if (found.isEmpty()) {
            return new GateResult.Unreachable<>();
        }
        return new GateResult.Allowed<>(detailOf(found.get()));
    }

    /* ------------------------------------------------------------------ writes --- */

    /**
     * {@code POST /invoices} — {@code billing:invoice}. Writes a header and not one line.
     *
     * <p><strong>A client with no rate card is refused with a 400.</strong> A draft that could price
     * nothing would be a document whose every hour is in the {@code NO_RATE} bucket, and
     * {@code billing.html}'s {@code Set} button is where that is fixed. The currency comes from the
     * card, which is what makes an invoice's currency a property of its client rather than a field
     * on a form (019 D-d).
     */
    @Transactional
    public GateResult<Outcome<InvoiceDetailView>> draft(UUID userId, InvoiceDraftInput submitted) {
        return access.createGranted(userId, INVOICE, () -> {
            UUID clientId = submitted == null ? null : submitted.clientId();
            /*
              A client this company cannot see is a 400 on `clientId` and never a 404 and never a
              500 from the foreign key: fk_invoice_client is on `id` alone and referential integrity
              is not subject to RLS, so another company's client would satisfy the constraint and
              raise one company's invoice against another's customer (018 D-l, §6t).
            */
            if (clientId == null || !clients.existsInCompany(clientId)) {
                return new Outcome.Invalid<InvoiceDetailView>(Map.of(
                        "clientId", List.of("That client does not exist.")));
            }
            Optional<Map<String, List<String>>> wrong = validate(submitted);
            if (wrong.isPresent()) {
                return new Outcome.Invalid<InvoiceDetailView>(wrong.get());
            }
            Optional<RateCardService.PricedCard> card = rateCards.cardFor(clientId);
            if (card.isEmpty()) {
                return new Outcome.Invalid<InvoiceDetailView>(Map.of(
                        "clientId",
                        List.of("That client has no rate card, so nothing could be priced. "
                                + "Set one on the Billing screen first.")));
            }
            Invoice drafted = invoices.save(Invoice.draft(
                    UUID.randomUUID(),
                    CompanyContext.require(),
                    clientId,
                    submitted.periodStart(),
                    submitted.periodEnd(),
                    card.get().currency(),
                    submitted.termsDays() == null ? DEFAULT_TERMS_DAYS : submitted.termsDays(),
                    /*
                      The contact is CHOSEN here and STORED, rather than resolved on every read.
                      An invoice keeps addressing the person it was raised for — a client that adds
                      a contact called "Aaron" next month does not re-address a document already
                      sent to Anna. bill_to_id is ON DELETE SET NULL, so a deleted contact leaves a
                      null the screen renders as no address rather than a dangling id.
                    */
                    clients.contactsOf(clientId).stream()
                            .findFirst().map(ClientContactRef::id).orElse(null),
                    clock.instant()));

            return new Outcome.Written<>(detailOf(drafted));
        });
    }

    /**
     * {@code POST /invoices/{invoiceId}/issue} — {@code billing:issue}.
     *
     * <p><strong>The one method in this product that writes an {@code invoice_line}</strong>, and
     * nothing updates one afterwards.
     *
     * <p><strong>It is deliberately not annotated {@code @Transactional}</strong>, which is the one
     * departure from every other write in this module. The transaction begins inside
     * {@link ProjectService#createGranted}, so a {@code uq_invoice_seq} or
     * {@code uq_invoice_line_entry_time} violation rolls that transaction back and unwinds to here —
     * where the single retry can run in a <em>new</em> one. A retry inside the failed transaction
     * would fail on the abort rather than on the number, which is the shape that makes "one retry"
     * look implemented and do nothing (019 D-k).
     *
     * <p>The second attempt recomposes from scratch, which is what makes it correct rather than
     * merely a second try: if the race was another invoice taking these hours,
     * {@link ApprovedTimeFacts#draftableOn} no longer offers them and this invoice issues without
     * them. Two attempts and no more — a third would be a spin on a contended row rather than a
     * recovery from a collision.
     */
    public GateResult<Outcome<InvoiceDetailView>> issue(UUID userId, UUID invoiceId) {
        try {
            return access.createGranted(userId, ISSUE, () -> issueOnce(userId, invoiceId));
        } catch (DataIntegrityViolationException raced) {
            return access.createGranted(userId, ISSUE, () -> issueOnce(userId, invoiceId));
        }
    }

    /**
     * {@code DELETE /invoices/{invoiceId}} — {@code billing:invoice}. {@code invoice.html}'s
     * <em>Discard</em>.
     *
     * <p><strong>409 for an issued invoice</strong>, and the status is the point: the caller may
     * delete, the invoice exists, and it is not a field they can fix — so it is neither 403 nor 404.
     * An issued invoice is a document that was sent, and there is no operation on this surface that
     * unsends one.
     *
     * <p>Discarding a draft frees nothing, because a draft reserved nothing: its hours were never
     * taken (019 D-c), so they were draftable the whole time it existed.
     */
    @Transactional
    public GateResult<Outcome<Void>> discard(UUID userId, UUID invoiceId) {
        return access.createGranted(userId, INVOICE, () -> {
            Optional<Invoice> found = invoices.findById(invoiceId);
            if (found.isEmpty()) {
                return new Outcome.Missing<Void>();
            }
            Invoice invoice = found.get();
            if (invoice.getStatus() == InvoiceStatus.ISSUED) {
                return new Outcome.Conflict<Void>(
                        "That invoice has been issued",
                        "Its lines are a record of what was sent to " + clientNameOf(invoice)
                                + ". Nothing on this surface unsends an invoice, so an issued one "
                                + "cannot be discarded.");
            }
            invoices.delete(invoice);
            return new Outcome.Removed<Void>();
        });
    }

    /* ------------------------------------------------------------------ issuing --- */

    private Outcome<InvoiceDetailView> issueOnce(UUID userId, UUID invoiceId) {
        Optional<Invoice> found = invoices.findById(invoiceId);
        if (found.isEmpty()) {
            return new Outcome.Missing<>();
        }
        Invoice invoice = found.get();
        if (invoice.getStatus() == InvoiceStatus.ISSUED) {
            return new Outcome.Conflict<>(
                    "That invoice is already issued",
                    "It is " + invoice.getNumber() + ", issued once and numbered once. Re-issuing "
                            + "would either reprice a sent document or silently do nothing, and "
                            + "both are worse than a refusal that names the state.");
        }
        Instant now = clock.instant();
        Composition composed = compose(invoice);
        writeLines(invoice, composed, now);

        long seq = numbers.nextSeq();
        invoice.issue(seq, InvoiceNumbers.render(now, seq), composed.total(), userId, now);
        invoices.save(invoice);
        /*
          Flushed here rather than at the transaction boundary so that uq_invoice_seq and
          uq_invoice_line_entry_time raise INSIDE the try in issue() above. A violation surfacing at
          commit would unwind past the retry with nothing left to catch it, and the caller would see
          a 500 for a collision this design has an answer for.
        */
        invoices.flush();
        return new Outcome.Written<>(detailOf(invoice));
    }

    /**
     * The one place {@code invoice_line} and {@code invoice_line_entry} rows come into existence.
     *
     * <p>Every line carries the rate as a number and the label as a resolved string, and every entry
     * behind it is a snapshot — the hours, the day, the person's name, the subject and the period's
     * approval, copied rather than joined. That is what makes an issued invoice readable after the
     * person leaves, the role is renamed and the issue is deleted.
     */
    private void writeLines(Invoice invoice, Composition composed, Instant now) {
        UUID companyId = invoice.getCompanyId();
        for (ComposedLine line : composed.lines()) {
            UUID lineId = UUID.randomUUID();
            lines.save(InvoiceLine.record(
                    lineId,
                    companyId,
                    invoice.getId(),
                    line.view().projectId(),
                    line.view().scope(),
                    line.key().personId(),
                    line.key().roleId(),
                    line.view().appliesTo(),
                    line.view().hours(),
                    line.view().rate(),
                    line.view().amount().amount(),
                    now));

            for (InvoiceEntryView entry : line.entries()) {
                lineEntries.save(InvoiceLineEntry.snapshot(
                        UUID.randomUUID(),
                        companyId,
                        lineId,
                        entry.timeEntryId(),
                        entry.spentOn(),
                        entry.hours(),
                        entry.personName(),
                        entry.subjectKey(),
                        entry.subjectTitle(),
                        entry.periodApprovedAt(),
                        entry.periodApprovedBy(),
                        now));
            }
        }
    }

    /* ---------------------------------------------------------------- composing --- */

    /**
     * What a line <em>is</em>: one project, one matched rung, one rate.
     *
     * <p>Built on both paths — from {@link PricingService.Priced} for a draft and from the stored
     * columns for an issued invoice — so one grouping rule and one ordering rule serve both states.
     * {@code invoice_line.person_id} and {@code role_id} exist for exactly this, and V12's comment
     * on them says so: they are kept for grouping, not for rendering.
     */
    private record LineKey(
            UUID projectId, RateCardScope scope, UUID personId, UUID roleId, BigDecimal rate) {
    }

    /** One line and the hours behind it — the key kept beside the view, because issuing needs both. */
    private record ComposedLine(LineKey key, InvoiceLineView view, List<InvoiceEntryView> entries) {
    }

    /**
     * The lines, the entries behind them, and <strong>both exclusion buckets</strong>. Never a
     * partial answer.
     */
    private record Composition(
            List<ComposedLine> lines, List<ExcludedHoursView> excluded, BigDecimal total) {

        List<InvoiceLineView> views() {
            return lines.stream().map(ComposedLine::view).toList();
        }

        List<InvoiceEntryView> entries() {
            return lines.stream()
                    .flatMap(line -> line.entries().stream())
                    .sorted(Comparator.comparing(InvoiceEntryView::spentOn)
                            .thenComparing(InvoiceEntryView::personName))
                    .toList();
        }
    }

    /**
     * A draft's lines are a query and an issued invoice's are rows — and the exclusions are a live
     * statement either way.
     *
     * <p><strong>An issued invoice reports the {@code NOT_APPROVED} bucket and not the other
     * one</strong>, and both halves of that are decisions. It reports the first because
     * <em>"38 hours in this period are still not approved"</em> is true and useful about a document
     * already sent, and it is a live figure rather than one pretending to have been frozen. It
     * reports no {@code NO_RATE} bucket because there is nothing to put in one: the hours still
     * visible for that period are UNBILLED rather than unpriceable — this invoice's own are
     * subtracted by {@link ApprovedTimeFacts#draftableOn}'s {@code NOT EXISTS} — and filing them
     * under <em>no rate card could price this</em> would name the wrong reason. They are visible
     * where they belong: as the lines of the next draft over the same period.
     */
    private Composition compose(Invoice invoice) {
        List<Project> clientProjects = projectsOf(invoice.getClientId());
        List<UUID> projectIds = clientProjects.stream().map(Project::getId).toList();
        Map<UUID, String> projectNames = new HashMap<>();
        for (Project project : clientProjects) {
            projectNames.put(project.getId(), project.getName());
        }
        boolean isDraft = invoice.getStatus() == InvoiceStatus.DRAFT;

        // Not asked for an issued invoice: its lines are rows, and what remains draftable for that
        // period is the next draft's business rather than this document's — see the header.
        List<ApprovedTimeFacts.ApprovedEntry> draftable = isDraft
                ? hours.draftableOn(projectIds, invoice.getPeriodStart(), invoice.getPeriodEnd())
                : List.of();
        List<ApprovedTimeFacts.PersonHours> unapproved =
                hours.unapprovedOn(projectIds, invoice.getPeriodStart(), invoice.getPeriodEnd());

        /*
          019 D-g. Three populations, and an hour is in exactly one of them:

            priced       — approved, issue-subject, on a project of this client, with a rate
            NOT_APPROVED — logged in the window and its period is not APPROVED
            NO_RATE      — approved, but PricingService found no entry effective that day

          The second bucket is the one a naive implementation loses, because from inside a join it
          looks exactly like the first. Both carry their hours and their reason; neither carries an
          amount, because neither has one.
        */
        Optional<RateCardService.PricedCard> card =
                isDraft ? rateCards.cardFor(invoice.getClientId()) : Optional.empty();
        List<ApprovedTimeFacts.ApprovedEntry> unpriced = new ArrayList<>();
        Map<LineKey, List<ApprovedTimeFacts.ApprovedEntry>> grouped =
                isDraft ? price(card, draftable, unpriced) : Map.of();

        Map<UUID, String> names = namesFor(draftable, unapproved);
        List<ExcludedHoursView> excluded = new ArrayList<>();
        Map<UUID, BigDecimal> pending = new HashMap<>();
        for (ApprovedTimeFacts.PersonHours row : unapproved) {
            pending.merge(row.personId(), row.hours(), BigDecimal::add);
        }
        addBucket(excluded, ExcludedReason.NOT_APPROVED, pending, names);

        if (isDraft) {
            Map<UUID, BigDecimal> unpriceable = new HashMap<>();
            for (ApprovedTimeFacts.ApprovedEntry entry : unpriced) {
                unpriceable.merge(entry.personId(), entry.hours(), BigDecimal::add);
            }
            addBucket(excluded, ExcludedReason.NO_RATE, unpriceable, names);
        }

        List<ComposedLine> composed = isDraft
                ? computed(invoice, card, grouped, projectNames)
                : stored(invoice, projectNames);

        BigDecimal total;
        if (isDraft) {
            total = ZERO_MONEY;
            for (ComposedLine line : composed) {
                total = total.add(line.view().amount().amount());
            }
        } else {
            total = invoice.getTotalAmount() == null ? ZERO_MONEY : invoice.getTotalAmount();
        }
        return new Composition(List.copyOf(composed), List.copyOf(excluded), total);
    }

    /**
     * The rate ladder, one hour at a time, with every lookup done once for the whole period.
     *
     * <p>Priced per entry rather than as {@code hours × rate} for the period, because the rate
     * depends on who logged the hour and on the day they logged it (019 D-d) — a project's total is
     * not a multiplication and there is no single rate to multiply by.
     */
    private Map<LineKey, List<ApprovedTimeFacts.ApprovedEntry>> price(
            Optional<RateCardService.PricedCard> card,
            List<ApprovedTimeFacts.ApprovedEntry> draftable,
            List<ApprovedTimeFacts.ApprovedEntry> unpriced) {

        if (card.isEmpty()) {
            /*
              A card is required to DRAFT and may be deleted afterwards — RateCardService refuses no
              delete because an invoice exists, because an issued invoice carries its rates as
              numbers. Every hour on this draft is then unpriceable, which is a bucket and not an
              error, and billing.html's Set button is where it is fixed.
            */
            unpriced.addAll(draftable);
            return Map.of();
        }
        Set<UUID> personIds = new HashSet<>();
        for (ApprovedTimeFacts.ApprovedEntry entry : draftable) {
            personIds.add(entry.personId());
        }
        PersonRoles.Held held = personRoles.heldBy(personIds);

        Map<LineKey, List<ApprovedTimeFacts.ApprovedEntry>> grouped = new LinkedHashMap<>();
        for (ApprovedTimeFacts.ApprovedEntry entry : draftable) {
            Optional<PricingService.Priced> priced = pricing.rateFor(
                    card.get().entries(), held, entry.personId(), entry.projectId(),
                    entry.spentOn());
            if (priced.isEmpty()) {
                unpriced.add(entry);
                continue;
            }
            PricingService.Priced rung = priced.get();
            LineKey key = new LineKey(
                    entry.projectId(), rung.scope(), rung.personId(), rung.roleId(),
                    rung.rate().setScale(MONEY_SCALE, RoundingMode.HALF_UP));
            grouped.computeIfAbsent(key, unused -> new ArrayList<>()).add(entry);
        }
        return grouped;
    }

    /** A draft's lines: computed now, stored nowhere. */
    private List<ComposedLine> computed(
            Invoice invoice,
            Optional<RateCardService.PricedCard> card,
            Map<LineKey, List<ApprovedTimeFacts.ApprovedEntry>> grouped,
            Map<UUID, String> projectNames) {

        EntryLabels.Labels resolved = labels.forEntries(
                card.map(RateCardService.PricedCard::entries).orElse(List.of()));

        Set<UUID> personIds = new HashSet<>();
        Set<UUID> issueIds = new HashSet<>();
        for (List<ApprovedTimeFacts.ApprovedEntry> entries : grouped.values()) {
            for (ApprovedTimeFacts.ApprovedEntry entry : entries) {
                personIds.add(entry.personId());
                if (entry.approvedBy() != null) {
                    personIds.add(entry.approvedBy());
                }
                if (entry.subjectId() != null) {
                    issueIds.add(entry.subjectId());
                }
            }
        }
        Map<UUID, UserRef> resolvedPeople = resolve(personIds);
        Map<UUID, IssueSubjectFacts.Subject> resolvedSubjects = subjects.byIds(issueIds);

        List<Map.Entry<LineKey, List<ApprovedTimeFacts.ApprovedEntry>>> ordered =
                new ArrayList<>(grouped.entrySet());
        /*
          Grouped by project, then by rate — invoice.html's own ordering, and the reason it is
          explicit is that a LinkedHashMap preserves the order the ENTRIES arrived in, which is
          spent_on. Two reads of one draft must not render its lines in two orders.
        */
        ordered.sort(Comparator
                .comparing((Map.Entry<LineKey, List<ApprovedTimeFacts.ApprovedEntry>> row) ->
                        Objects.requireNonNullElse(projectNames.get(row.getKey().projectId()), ""))
                .thenComparing(row -> row.getKey().rate(), Comparator.reverseOrder())
                .thenComparing(row -> row.getKey().scope().name()));

        List<ComposedLine> composed = new ArrayList<>(ordered.size());
        for (Map.Entry<LineKey, List<ApprovedTimeFacts.ApprovedEntry>> row : ordered) {
            LineKey key = row.getKey();
            BigDecimal lineHours = BigDecimal.ZERO;
            for (ApprovedTimeFacts.ApprovedEntry entry : row.getValue()) {
                lineHours = lineHours.add(entry.hours());
            }
            InvoiceLineView view = new InvoiceLineView(
                    key.projectId(),
                    projectNames.get(key.projectId()),
                    resolved.of(key.scope(), key.personId(), key.roleId()),
                    key.scope(),
                    lineHours,
                    key.rate(),
                    new Money(
                            key.rate().multiply(lineHours)
                                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                            invoice.getCurrency()));

            List<InvoiceEntryView> entries = new ArrayList<>(row.getValue().size());
            for (ApprovedTimeFacts.ApprovedEntry entry : row.getValue()) {
                IssueSubjectFacts.Subject subject = entry.subjectId() == null
                        ? null : resolvedSubjects.get(entry.subjectId());
                entries.add(new InvoiceEntryView(
                        entry.timeEntryId(),
                        entry.spentOn(),
                        nameOf(resolvedPeople, entry.personId()),
                        entry.hours(),
                        subject == null ? null : subject.issueKey(),
                        subject == null ? null : subject.title(),
                        entry.approvedAt(),
                        nameOf(resolvedPeople, entry.approvedBy())));
            }
            composed.add(new ComposedLine(key, view, List.copyOf(entries)));
        }
        return composed;
    }

    /** An issued invoice's lines: the rows written once, read back exactly as they were written. */
    private List<ComposedLine> stored(Invoice invoice, Map<UUID, String> projectNames) {
        List<InvoiceLine> rows = lines.findByInvoiceIdOrderByCreatedAtAsc(invoice.getId());
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<InvoiceLineEntry>> entriesByLine = new HashMap<>();
        for (InvoiceLineEntry snapshot : lineEntries.findByInvoiceLineIdInOrderBySpentOnAsc(
                rows.stream().map(InvoiceLine::getId).toList())) {
            entriesByLine.computeIfAbsent(snapshot.getInvoiceLineId(), id -> new ArrayList<>())
                    .add(snapshot);
        }

        List<ComposedLine> composed = new ArrayList<>(rows.size());
        for (InvoiceLine row : rows) {
            InvoiceLineView view = new InvoiceLineView(
                    row.getProjectId(),
                    /*
                      The project name is resolved now and the label is not, and the asymmetry is
                      the decision: applies_to is what the client was told and is frozen, while a
                      project renamed since is still the same project and this screen is this
                      company's own rather than the copy that was sent.
                    */
                    projectNames.get(row.getProjectId()),
                    row.getAppliesTo(),
                    row.getScope(),
                    row.getHours(),
                    row.getRateApplied(),
                    new Money(row.getAmount(), invoice.getCurrency()));

            List<InvoiceEntryView> entries = new ArrayList<>();
            for (InvoiceLineEntry snapshot : entriesByLine.getOrDefault(row.getId(), List.of())) {
                entries.add(new InvoiceEntryView(
                        snapshot.getTimeEntryId(),
                        snapshot.getSpentOn(),
                        snapshot.getPersonName(),
                        snapshot.getHours(),
                        snapshot.getSubjectKey(),
                        snapshot.getSubjectTitle(),
                        snapshot.getApprovedAt(),
                        snapshot.getApprovedBy()));
            }
            composed.add(new ComposedLine(
                    new LineKey(row.getProjectId(), row.getScope(), row.getPersonId(),
                            row.getRoleId(), row.getRateApplied()),
                    view,
                    List.copyOf(entries)));
        }
        return composed;
    }

    /* ----------------------------------------------------------------- innards --- */

    private InvoiceDetailView detailOf(Invoice invoice) {
        Composition composed = compose(invoice);
        List<UUID> touched = composed.views().stream()
                .map(InvoiceLineView::projectId)
                .distinct()
                .toList();
        return new InvoiceDetailView(
                viewOf(invoice, composed),
                billToOf(invoice),
                invoice.getTermsDays(),
                composed.views(),
                composed.entries(),
                composed.excluded(),
                budgets.viewsFor(touched));
    }

    private InvoiceView viewOf(Invoice invoice, Composition composed) {
        BigDecimal billed = BigDecimal.ZERO;
        for (InvoiceLineView line : composed.views()) {
            billed = billed.add(line.hours());
        }
        UUID issuedBy = invoice.getIssuedBy();
        return new InvoiceView(
                invoice.getId(),
                invoice.getNumber(),
                new ClientRef(invoice.getClientId(), clientNameOf(invoice)),
                invoice.getPeriodStart(),
                invoice.getPeriodEnd(),
                invoice.getStatus(),
                invoice.getCurrency(),
                new Money(composed.total(), invoice.getCurrency()),
                composed.views().size(),
                billed,
                invoice.getIssuedAt(),
                issuedBy == null ? null : nameOf(resolve(Set.of(issuedBy)), issuedBy));
    }

    /**
     * The projects an invoice for this client may bill.
     *
     * <p>Filtered from the company's projects rather than queried by client, because
     * {@code ProjectRepository} answers this module the same list every other read here starts from
     * and a company has tens of projects, not thousands. It is also the reason a project moved to
     * another client stops appearing on the next read of an open draft, which is the same
     * recomputation 019 D-c takes deliberately everywhere else on this surface.
     */
    private List<Project> projectsOf(UUID clientId) {
        return projects.findAllByOrderByNameAsc().stream()
                .filter(project -> clientId.equals(project.getClientId()))
                .toList();
    }

    /**
     * The contact this invoice was addressed to, resolved from its own {@code bill_to_id}.
     *
     * <p><strong>Not "the client's first contact", which is what chose it at draft time.</strong>
     * The two answers are the same on the day an invoice is raised and diverge the moment the client
     *'s contacts change, and the stored one is the correct one: an issued invoice is a record of
     * what was sent, and re-resolving would let a CRM edit rewrite the addressee on a document
     * somebody is holding a copy of.
     *
     * <p>{@code null} when the client had no contact, or when the one it named has since been
     * deleted ({@code ON DELETE SET NULL}). Both are states and neither is a blocker — issuing sends
     * nothing, so there is nothing an absent address prevents.
     */
    private ClientContactRef billToOf(Invoice invoice) {
        UUID billTo = invoice.getBillToId();
        if (billTo == null) {
            return null;
        }
        return clients.contactsOf(invoice.getClientId()).stream()
                .filter(contact -> billTo.equals(contact.id()))
                .findFirst()
                .orElse(null);
    }

    private String clientNameOf(Invoice invoice) {
        return clients.namesOf(List.of(invoice.getClientId())).get(invoice.getClientId());
    }

    private Map<UUID, UserRef> resolve(Collection<UUID> personIds) {
        return personIds.isEmpty()
                ? Map.of() : people.resolve(CompanyContext.require(), personIds);
    }

    private Map<UUID, String> namesFor(
            List<ApprovedTimeFacts.ApprovedEntry> approved,
            List<ApprovedTimeFacts.PersonHours> unapproved) {

        Set<UUID> ids = new HashSet<>();
        approved.forEach(entry -> ids.add(entry.personId()));
        unapproved.forEach(row -> ids.add(row.personId()));

        Map<UUID, UserRef> refs = resolve(ids);
        Map<UUID, String> names = new HashMap<>();
        ids.forEach(id -> names.put(id, nameOf(refs, id)));
        return names;
    }

    /**
     * The name, or {@link #UNRESOLVED} — never an invented one.
     *
     * <p>{@code DashboardService}'s rule: a row whose person cannot be placed says so rather than
     * attributing an act to an "Unknown user" who does not exist. On an invoice that argument is at
     * its strongest, because the row says who worked the hours somebody is being charged for.
     */
    private static String nameOf(Map<UUID, UserRef> resolved, UUID personId) {
        if (personId == null) {
            return UNRESOLVED;
        }
        UserRef person = resolved.get(personId);
        return person == null ? UNRESOLVED : person.displayName();
    }

    /**
     * One exclusion bucket, or none at all when it is empty.
     *
     * <p>An empty bucket is left out rather than served as a zero: {@code excluded: []} means every
     * approved hour in the period is on a line, and a zero row would make the screen draw an amber
     * note about nothing.
     */
    private static void addBucket(
            List<ExcludedHoursView> excluded,
            ExcludedReason reason,
            Map<UUID, BigDecimal> byPerson,
            Map<UUID, String> names) {

        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal personHours : byPerson.values()) {
            total = total.add(personHours);
        }
        if (total.signum() <= 0) {
            return;
        }
        // "Tomas Ek 24h · Jun Park 14h" — the mockup's own wording, biggest first so the note names
        // the person somebody has to chase before the person they do not.
        String detail = byPerson.entrySet().stream()
                .sorted(Comparator
                        .comparing(Map.Entry<UUID, BigDecimal>::getValue).reversed()
                        .thenComparing(row -> nameIn(names, row.getKey())))
                .map(row -> nameIn(names, row.getKey()) + " "
                        + row.getValue().stripTrailingZeros().toPlainString() + "h")
                .reduce((left, right) -> left + " · " + right)
                .orElse("");
        excluded.add(new ExcludedHoursView(reason, total, detail));
    }

    private static String nameIn(Map<UUID, String> names, UUID personId) {
        return Objects.requireNonNullElse(names.get(personId), UNRESOLVED);
    }

    /** Every field error a draft body can carry, beyond the client the caller is told about first. */
    private static Optional<Map<String, List<String>>> validate(InvoiceDraftInput submitted) {
        LocalDate start = submitted.periodStart();
        LocalDate end = submitted.periodEnd();
        if (start == null || end == null) {
            return Optional.of(Map.of(
                    "periodStart", List.of("An invoice covers a period, so it needs both dates.")));
        }
        if (end.isBefore(start)) {
            return Optional.of(Map.of(
                    "periodEnd", List.of("A period cannot end before it starts.")));
        }
        Integer terms = submitted.termsDays();
        if (terms != null && terms < 0) {
            return Optional.of(Map.of(
                    "termsDays", List.of("Payment terms cannot be negative.")));
        }
        return Optional.empty();
    }
}
