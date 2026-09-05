package com.tcms.billing;

import com.tcms.project.ClientDirectory;
import com.tcms.project.GateResult;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.project.ProjectService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /clients/{clientId}/billing} — <strong>one path, two readers, different columns</strong>
 * (019 D-i).
 *
 * <h2>Gated on {@code client:read} and not on a money key</h2>
 *
 * <p>An <strong>Account Manager</strong> owns the client relationship: {@code client.html} draws
 * them the hours-and-invoices table with the amounts absent, and its footer states it from the other
 * side — <em>"Switch the top bar to Finance and this table gains a value column."</em> So the gate
 * is the one that lets somebody see the client at all, and the money is a separate question asked
 * once, server-side, as {@link ClientBillingSummaryView#amountsVisible()}.
 *
 * <p><strong>The hours are not withheld from anybody who may see the client.</strong> That is the
 * boundary scope §7 draws and {@code time.TimeMoneyBoundaryTest} asserts from the other end: an
 * Account Manager knows the client and the hours, and never a rate.
 *
 * <h2>Served by {@code billing} and tagged {@code clients}</h2>
 *
 * <p>The tag names the screen a reader is on, and this is the only path under a {@code clients} URL
 * that a money module answers — {@code crm.CrmCreatesNoLoginTest} records exactly that, beside the
 * assertion that {@code ClientDetail} still carries no money word (019 D-n).
 *
 * <h2>A row is a month and a project</h2>
 *
 * <p>{@code client.html}'s table reads <em>Aug 2026 · Acme Storefront · 168h · INV-2026-039</em>.
 * The month is where the hours were worked, not where the invoice was raised, so a period straddling
 * two months contributes to two rows and each names the same invoice — which is what a reader
 * checking <em>have we billed August</em> is actually asking.
 */
@Service
public class ClientBillingService {

    private static final String CLIENT_READ = "client:read";
    private static final String BILLING_READ = "billing:read";

    /** {@code Aug 2026} — {@code client.html}'s own spelling. */
    private static final DateTimeFormatter PERIOD_LABEL =
            DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

    private static final int MONTHS_IN_QUARTER = 3;

    private final InvoiceRepository invoices;
    private final InvoiceLineRepository lines;
    private final ProjectRepository projects;
    private final ClientDirectory clients;
    private final ApprovedTimeFacts hours;
    private final ProjectService access;
    private final Clock clock;

    ClientBillingService(
            InvoiceRepository invoices,
            InvoiceLineRepository lines,
            ProjectRepository projects,
            ClientDirectory clients,
            ApprovedTimeFacts hours,
            ProjectService access,
            Clock clock) {

        this.invoices = invoices;
        this.lines = lines;
        this.projects = projects;
        this.clients = clients;
        this.hours = hours;
        this.access = access;
        this.clock = clock;
    }

    /**
     * The two tiles and the table under them.
     *
     * <p>{@code amountsVisible} is evaluated <strong>here</strong> and once. The frontend never
     * decides it from its own permission cache: a client computing visibility locally would be one
     * stale {@code /me/permissions} away from rendering a column of nulls and calling it money.
     */
    @Transactional(readOnly = true)
    public GateResult<ClientBillingSummaryView> read(UUID userId, UUID clientId) {
        if (!access.holdsInCompany(userId, CLIENT_READ)) {
            return new GateResult.Denied<>();
        }
        if (!clients.existsInCompany(clientId)) {
            // 404 rather than an empty summary: a client this company does not have is not a client
            // with no invoices, and folding the two would make this an oracle for ids in another
            // tenancy (§6t).
            return new GateResult.Unreachable<>();
        }
        boolean amountsVisible = access.holdsInCompany(userId, BILLING_READ);

        List<Project> clientProjects = projects.findAllByOrderByNameAsc().stream()
                .filter(project -> clientId.equals(project.getClientId()))
                .toList();
        Map<UUID, String> projectNames = new HashMap<>();
        for (Project project : clientProjects) {
            projectNames.put(project.getId(), project.getName());
        }

        List<ApprovedTimeFacts.ApprovedEntry> approved =
                hours.approvedOn(clientProjects.stream().map(Project::getId).toList());
        List<Invoice> raised = invoices.findByClientIdOrderByPeriodStartDesc(clientId);

        return new GateResult.Allowed<>(new ClientBillingSummaryView(
                amountsVisible,
                totalOf(approved),
                totalOf(approved.stream().filter(this::inThisQuarter).toList()),
                (int) raised.stream()
                        .filter(invoice -> invoice.getStatus() == InvoiceStatus.ISSUED)
                        .count(),
                raised.stream()
                        .map(Invoice::getIssuedAt)
                        .filter(java.util.Objects::nonNull)
                        .max(Comparator.naturalOrder())
                        .orElse(null),
                rows(approved, raised, projectNames, amountsVisible)));
    }

    /* ----------------------------------------------------------------- innards --- */

    /** One row per month per project, newest month first, then by project name. */
    private List<ClientBillingRowView> rows(
            List<ApprovedTimeFacts.ApprovedEntry> approved,
            List<Invoice> raised,
            Map<UUID, String> projectNames,
            boolean amountsVisible) {

        record Cell(YearMonth month, UUID projectId) {
        }
        Map<Cell, BigDecimal> byCell = new LinkedHashMap<>();
        for (ApprovedTimeFacts.ApprovedEntry entry : approved) {
            byCell.merge(
                    new Cell(YearMonth.from(entry.spentOn()), entry.projectId()),
                    entry.hours(),
                    BigDecimal::add);
        }
        Map<UUID, Map<UUID, BigDecimal>> billedByInvoice = billedAmounts(raised);

        List<Map.Entry<Cell, BigDecimal>> ordered = new ArrayList<>(byCell.entrySet());
        ordered.sort(Comparator
                .comparing((Map.Entry<Cell, BigDecimal> row) -> row.getKey().month())
                .reversed()
                .thenComparing(row -> projectNames.getOrDefault(row.getKey().projectId(), "")));

        List<ClientBillingRowView> views = new ArrayList<>(ordered.size());
        for (Map.Entry<Cell, BigDecimal> row : ordered) {
            LocalDate monthStart = row.getKey().month().atDay(1);
            Invoice covering = coveringInvoice(raised, row.getKey().month());

            Money amount = null;
            if (amountsVisible && covering != null && covering.getStatus() == InvoiceStatus.ISSUED) {
                BigDecimal billed = billedByInvoice
                        .getOrDefault(covering.getId(), Map.of())
                        .get(row.getKey().projectId());
                amount = billed == null ? null : new Money(billed, covering.getCurrency());
            }
            views.add(new ClientBillingRowView(
                    PERIOD_LABEL.format(row.getKey().month()),
                    monthStart,
                    row.getKey().projectId(),
                    projectNames.get(row.getKey().projectId()),
                    row.getValue(),
                    covering == null ? null : covering.getId(),
                    covering == null ? null : covering.getNumber(),
                    covering == null ? null : covering.getStatus(),
                    amount));
        }
        return List.copyOf(views);
    }

    /**
     * What each issued invoice billed, per project — read from the stored lines, never recomputed.
     *
     * <p>A draft contributes nothing here, and that is 019 D-c rather than an omission: it stores no
     * lines, so there is no amount to show, and the mockup renders the word <em>Draft</em> in that
     * column instead. Recomputing one to fill the cell would put a figure that moves every time
     * somebody approves a timesheet into a table whose other rows are what was sent.
     */
    private Map<UUID, Map<UUID, BigDecimal>> billedAmounts(List<Invoice> raised) {
        List<UUID> issued = raised.stream()
                .filter(invoice -> invoice.getStatus() == InvoiceStatus.ISSUED)
                .map(Invoice::getId)
                .toList();
        if (issued.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Map<UUID, BigDecimal>> byInvoice = new HashMap<>();
        for (InvoiceLine line : lines.findByInvoiceIdIn(issued)) {
            byInvoice.computeIfAbsent(line.getInvoiceId(), id -> new HashMap<>())
                    .merge(line.getProjectId(), line.getAmount(), BigDecimal::add);
        }
        return byInvoice;
    }

    /**
     * The invoice a month's hours are on — <strong>issued first, then the most recent</strong>.
     *
     * <p>Two invoices can cover one month: a draft raised over September while August's issued
     * invoice still overlaps it, or two drafts somebody opened by mistake. The issued one wins
     * because it is the one that happened, and the ordering is stated rather than left to whichever
     * row the list returned — a table whose invoice column changed between two reads would be
     * unusable for the one question it exists to answer.
     */
    private static Invoice coveringInvoice(List<Invoice> raised, YearMonth month) {
        return raised.stream()
                .filter(invoice -> !invoice.getPeriodStart().isAfter(month.atEndOfMonth()))
                .filter(invoice -> !invoice.getPeriodEnd().isBefore(month.atDay(1)))
                .min(Comparator
                        .comparing((Invoice invoice) -> invoice.getStatus() == InvoiceStatus.ISSUED
                                ? 0 : 1)
                        .thenComparing(Invoice::getPeriodStart, Comparator.reverseOrder()))
                .orElse(null);
    }

    private boolean inThisQuarter(ApprovedTimeFacts.ApprovedEntry entry) {
        YearMonth now = YearMonth.now(clock);
        YearMonth quarterStart = YearMonth.of(
                now.getYear(), ((now.getMonthValue() - 1) / MONTHS_IN_QUARTER) * MONTHS_IN_QUARTER + 1);
        YearMonth worked = YearMonth.from(entry.spentOn());
        return !worked.isBefore(quarterStart) && !worked.isAfter(now);
    }

    private static BigDecimal totalOf(List<ApprovedTimeFacts.ApprovedEntry> entries) {
        BigDecimal total = BigDecimal.ZERO;
        for (ApprovedTimeFacts.ApprovedEntry entry : entries) {
            total = total.add(entry.hours());
        }
        return total;
    }
}
