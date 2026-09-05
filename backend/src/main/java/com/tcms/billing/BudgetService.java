package com.tcms.billing;

import com.tcms.api.ClientRef;
import com.tcms.project.ClientDirectory;
import com.tcms.project.GateResult;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.CompanyContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Budgets — the ceiling, and the two readings of the burn against it.
 *
 * <h2>Two endpoints, two schemas, and that is 019 D-h</h2>
 *
 * <ul>
 *   <li>{@code GET /budgets} is the <strong>money view</strong>, gated on {@code billing:read}: the
 *       ceiling in its currency, what the approved hours are worth, and the percentage.</li>
 *   <li>{@code GET /projects/{projectId}/budget-burn} is the <strong>project view</strong>, gated on
 *       {@code project:read}, and {@link BudgetBurnView} has no amount member and no currency member
 *       at all. It is the one endpoint in this module a Test Engineer may call, and the reason that
 *       is safe is that there is nothing on it to withhold.</li>
 * </ul>
 *
 * <p>Two schemas rather than one with fields withheld, because a schema whose meaning depends on who
 * asked is one no reader can check. Gating the burn on {@code billing:read} was rejected: it would
 * hide a project's own ceiling from the people delivering against it, which is the opposite of what
 * the roles spec means by <em>"a Project Manager… whose budget view is a percentage on the project
 * overview rather than a number in a currency"</em>.
 *
 * <p><strong>The writes are gated on {@code billing:budget} at COMPANY scope and not on a project
 * grant.</strong> A budget is a money decision and the seven money keys are company-wide (roles spec
 * §A3's Money table), so a Project Manager sets no budget and reads the burn. That is also why they
 * cannot use {@code writeGranted}: Finance holds no project grant, so a project-scoped gate would
 * answer 404 to the one role the endpoint exists for.
 *
 * <h2>What {@code GET /budgets} lists, and the disagreement that is recorded rather than resolved</h2>
 *
 * <p><strong>Projects that have a budget.</strong> {@code Budget.basis} is required and not nullable
 * on the contract, so a budget-less project has no representable row here. {@code billing.html}
 * draws <em>Internal Platform · None (internal) · budget — · 126h approved · no budget set</em> in
 * the same table as three billed projects, which is a screen joining every project to the budgets
 * that exist — the projects list is where the other half of that row comes from, and T5 assembles
 * it. Noted here rather than resolved by widening the schema: the contract is authoritative over
 * both this plan and the mockup, and a nullable {@code basis} would make every consumer branch on a
 * field whose absence already has a home in {@link BudgetBurnView}.
 *
 * <p>A project with no client is listed when it has a budget, and its {@link BudgetView#pricedToDate}
 * is {@code null} with no warning tone and no call to action — a company that builds its own
 * products is the owner's own case (scope D1 rule 1, 019 D-p).
 */
@Service
public class BudgetService {

    private static final String READ = "billing:read";
    private static final String BUDGET = "billing:budget";

    /** {@code project:read}, and it is the whole of 019 D-h's gate. */
    private static final String PROJECT_READ = "project:read";

    /** ISO 4217, the same expression {@code ck_budget_shape} holds for a {@code MONEY} budget. */
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    /** Percentages render to one decimal — {@code billing.html} draws {@code 102%}, not {@code 102.04%}. */
    private static final int PERCENT_SCALE = 1;

    /** A money figure is stored {@code NUMERIC(14,2)} and is served at the same scale. */
    private static final int MONEY_SCALE = 2;

    private final BudgetRepository budgets;
    private final ProjectRepository projects;
    private final ClientDirectory clients;
    private final ApprovedTimeFacts hours;
    private final RateCardService rateCards;
    private final PricingService pricing;
    private final PersonRoles personRoles;
    private final ProjectService access;
    private final Clock clock;

    BudgetService(
            BudgetRepository budgets,
            ProjectRepository projects,
            ClientDirectory clients,
            ApprovedTimeFacts hours,
            RateCardService rateCards,
            PricingService pricing,
            PersonRoles personRoles,
            ProjectService access,
            Clock clock) {
        this.budgets = budgets;
        this.projects = projects;
        this.clients = clients;
        this.hours = hours;
        this.rateCards = rateCards;
        this.pricing = pricing;
        this.personRoles = personRoles;
        this.access = access;
        this.clock = clock;
    }

    /* ------------------------------------------------------------------- reads --- */

    /** {@code GET /budgets} — {@code billing:read}, optionally narrowed to one client. */
    @Transactional(readOnly = true)
    public GateResult<List<BudgetView>> list(UUID userId, UUID clientId) {
        return access.readInCompany(userId, READ, () -> {
            List<Project> candidates = projects.findAllByOrderByNameAsc().stream()
                    .filter(project -> clientId == null || clientId.equals(project.getClientId()))
                    .toList();
            Map<UUID, Project> byId = new HashMap<>();
            for (Project project : candidates) {
                byId.put(project.getId(), project);
            }
            List<Budget> rows = byId.isEmpty()
                    ? List.of() : budgets.findByProjectIdIn(byId.keySet());

            List<UUID> budgeted = rows.stream().map(Budget::getProjectId).toList();
            Map<UUID, BigDecimal> approved = hours.approvedHoursOn(budgeted);
            Map<UUID, Money> priced = pricedFor(
                    budgeted.stream().map(byId::get).filter(java.util.Objects::nonNull).toList());
            Map<UUID, String> clientNames = clientNamesOf(candidates);

            List<BudgetView> views = new ArrayList<>(rows.size());
            for (Budget budget : rows) {
                Project project = byId.get(budget.getProjectId());
                if (project == null) {
                    // Unreachable while RLS scopes both reads to one company, and cheaper than a
                    // NullPointerException on the day something makes it reachable.
                    continue;
                }
                views.add(viewOf(
                        project,
                        budget,
                        clientNames,
                        approved.getOrDefault(project.getId(), BigDecimal.ZERO),
                        priced.get(project.getId())));
            }
            return List.copyOf(views);
        });
    }

    /**
     * {@code GET /projects/{projectId}/budget-burn} — gated on {@code project:read} (019 D-h).
     *
     * <p><strong>The one endpoint in this module a Test Engineer may call, and it is safe because
     * there is nothing on it to withhold.</strong> {@link BudgetBurnView} has no amount member and
     * no currency member: a {@code MONEY}-basis budget renders as {@code burnPercent} alone, and
     * where the project's client has no rate card that percentage is {@code null} — the burn is
     * <em>unknown</em>, never zero.
     *
     * <p>It is deliberately not gated on {@code billing:read}. That would hide a project's own
     * ceiling from the people delivering against it, which is the opposite of what the roles spec
     * means by <em>"a Project Manager… whose budget view is a percentage on the project overview
     * rather than a number in a currency"</em>.
     *
     * <p>A project with no budget answers {@code 200} with a {@code null} basis, not {@code 404}.
     * The 404 on this path is for a project that is not there.
     */
    @Transactional(readOnly = true)
    public GateResult<BudgetBurnView> burn(UUID userId, UUID projectId) {
        return access.readGranted(userId, projectId, PROJECT_READ, project -> {
            BigDecimal approved = hours.approvedHoursOn(List.of(project.getId()))
                    .getOrDefault(project.getId(), BigDecimal.ZERO);
            Optional<Budget> budget = budgets.findByProjectId(project.getId());
            if (budget.isEmpty()) {
                return new BudgetBurnView(null, null, approved, null);
            }
            Budget ceiling = budget.get();
            Money priced = ceiling.getBasis() == BudgetBasis.MONEY
                    ? pricedFor(List.of(project)).get(project.getId())
                    : null;
            return new BudgetBurnView(
                    ceiling.getBasis(),
                    ceiling.getBasis() == BudgetBasis.HOURS ? ceiling.getAmount() : null,
                    approved,
                    burnPercent(ceiling, approved, priced));
        });
    }

    /**
     * The budgets of a named set of projects, <strong>ungated</strong> — {@code invoice.html}'s
     * <em>Budget effect</em> panel (019 T4).
     *
     * <p>Package-private and gating nothing, exactly as {@link RateCardService#cardFor} is: the only
     * caller is {@link InvoiceService}, which has already refused a caller without
     * {@code billing:read}, and a second gate here would be a second answer to a question one place
     * already answered. Callers say so at the call site.
     *
     * <p>A project with no budget has no row, which is what makes the panel empty rather than
     * dashed for an invoice against projects nobody has set a ceiling on — an ordinary state, and
     * one {@code project-overview.html} draws by drawing no panel at all.
     */
    List<BudgetView> viewsFor(Collection<UUID> projectIds) {
        if (projectIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, Project> byId = new HashMap<>();
        for (Project project : projects.findAllByOrderByNameAsc()) {
            if (projectIds.contains(project.getId())) {
                byId.put(project.getId(), project);
            }
        }
        if (byId.isEmpty()) {
            return List.of();
        }
        List<Budget> rows = budgets.findByProjectIdIn(byId.keySet());
        List<UUID> budgeted = rows.stream().map(Budget::getProjectId).toList();
        Map<UUID, BigDecimal> approved = hours.approvedHoursOn(budgeted);
        Map<UUID, Money> priced = pricedFor(
                budgeted.stream().map(byId::get).filter(java.util.Objects::nonNull).toList());
        Map<UUID, String> clientNames = clientNamesOf(byId.values());

        List<BudgetView> views = new ArrayList<>(rows.size());
        for (Budget budget : rows) {
            Project project = byId.get(budget.getProjectId());
            if (project == null) {
                continue;
            }
            views.add(viewOf(
                    project,
                    budget,
                    clientNames,
                    approved.getOrDefault(project.getId(), BigDecimal.ZERO),
                    priced.get(project.getId())));
        }
        return List.copyOf(views);
    }

    /* ------------------------------------------------------------------ writes --- */

    /** {@code PUT /projects/{projectId}/budget} — {@code billing:budget}, a target state. */
    @Transactional
    public GateResult<Outcome<BudgetView>> set(
            UUID userId, UUID projectId, BudgetWriteRequest submitted) {

        return access.createGranted(userId, BUDGET, () -> {
            Optional<Project> project = projects.findScopedById(projectId);
            if (project.isEmpty()) {
                return new Outcome.Missing<BudgetView>();
            }
            Optional<Map<String, List<String>>> errors = validate(submitted);
            if (errors.isPresent()) {
                return new Outcome.Invalid<BudgetView>(errors.get());
            }
            BudgetEdit edit = new BudgetEdit(
                    submitted.basis(),
                    submitted.amount(),
                    submitted.basis() == BudgetBasis.MONEY ? submitted.currency() : null);

            Budget budget = budgets.findByProjectId(projectId).orElse(null);
            if (budget == null) {
                budget = Budget.create(
                        UUID.randomUUID(), CompanyContext.require(), projectId, edit,
                        clock.instant());
            } else {
                budget.apply(edit, clock.instant());
            }
            Budget saved = budgets.save(budget);
            Project owner = project.get();
            return new Outcome.Written<>(viewOf(
                    owner,
                    saved,
                    clientNamesOf(List.of(owner)),
                    hours.approvedHoursOn(List.of(projectId))
                            .getOrDefault(projectId, BigDecimal.ZERO),
                    pricedFor(List.of(owner)).get(projectId)));
        });
    }

    /**
     * {@code DELETE /projects/{projectId}/budget} — {@code204} whether or not there was one.
     *
     * <p>The target state is the same either way, and a project with no budget is not a project in a
     * deficient state: {@code settings-general.html}'s Budget row reads <em>no budget set</em> and
     * the overview draws no panel at all.
     */
    @Transactional
    public GateResult<Outcome<Void>> clear(UUID userId, UUID projectId) {
        return access.createGranted(userId, BUDGET, () -> {
            if (projects.findScopedById(projectId).isEmpty()) {
                return new Outcome.Missing<Void>();
            }
            budgets.findByProjectId(projectId).ifPresent(budgets::delete);
            return new Outcome.Removed<Void>();
        });
    }

    /* ----------------------------------------------------------------- innards --- */

    /**
     * What each project's approved hours are worth, per project — {@code null} for a project with no
     * client or whose client has no card.
     *
     * <p>Every lookup is done once for the whole batch: the cards per client, the roles per person,
     * and the entries per project. Priced one entry at a time all the same, because the rate depends
     * on who logged the hour and on the day they logged it — a project's total is not
     * {@code hours × rate} and there is no rate to multiply by.
     */
    private Map<UUID, Money> pricedFor(List<Project> forProjects) {
        if (forProjects.isEmpty()) {
            return Map.of();
        }
        Map<UUID, RateCardService.PricedCard> cardByClient = new HashMap<>();
        for (Project project : forProjects) {
            UUID clientId = project.getClientId();
            if (clientId != null && !cardByClient.containsKey(clientId)) {
                rateCards.cardFor(clientId).ifPresent(card -> cardByClient.put(clientId, card));
            }
        }
        if (cardByClient.isEmpty()) {
            return Map.of();
        }
        List<UUID> priceable = forProjects.stream()
                .filter(project -> cardByClient.containsKey(project.getClientId()))
                .map(Project::getId)
                .toList();

        List<ApprovedTimeFacts.ApprovedEntry> entries = hours.approvedOn(priceable);
        Set<UUID> people = new HashSet<>();
        for (ApprovedTimeFacts.ApprovedEntry entry : entries) {
            people.add(entry.personId());
        }
        PersonRoles.Held held = personRoles.heldBy(people);

        Map<UUID, UUID> clientOf = new HashMap<>();
        for (Project project : forProjects) {
            clientOf.put(project.getId(), project.getClientId());
        }
        Map<UUID, BigDecimal> totals = new HashMap<>();
        for (UUID projectId : priceable) {
            totals.put(projectId, BigDecimal.ZERO);
        }
        for (ApprovedTimeFacts.ApprovedEntry entry : entries) {
            RateCardService.PricedCard card = cardByClient.get(clientOf.get(entry.projectId()));
            if (card == null) {
                continue;
            }
            pricing.rateFor(card.entries(), held, entry.personId(), entry.projectId(),
                            entry.spentOn())
                    .ifPresent(priced -> totals.merge(
                            entry.projectId(),
                            priced.rate().multiply(entry.hours()),
                            BigDecimal::add));
        }
        Map<UUID, Money> byProject = new HashMap<>();
        totals.forEach((projectId, amount) -> byProject.put(projectId, new Money(
                amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                cardByClient.get(clientOf.get(projectId)).currency())));
        return byProject;
    }

    /**
     * Approved hours over an {@code HOURS} ceiling, or priced hours over a {@code MONEY} one.
     *
     * <p><strong>Not capped at 100.</strong> {@code billing.html} draws Bergstrom Portal at 102% in a
     * danger tone, and clamping it would hide the one row the screen exists to surface.
     *
     * <p>{@code null} in two cases, and both mean <em>unknown</em> rather than zero: a {@code MONEY}
     * budget whose project cannot be priced at all, and a {@code MONEY} budget whose currency is not
     * the currency its client's card prices in. The second is 019 D-l applied to a percentage —
     * dividing CHF by EUR produces a number that is wrong in both currencies and looks like a
     * percentage in neither.
     */
    private BigDecimal burnPercent(Budget budget, BigDecimal approved, Money priced) {
        BigDecimal spent;
        if (budget.getBasis() == BudgetBasis.HOURS) {
            spent = approved;
        } else if (priced == null || !priced.currency().equals(budget.getCurrency())) {
            return null;
        } else {
            spent = priced.amount();
        }
        if (budget.getAmount().signum() == 0) {
            // ck_budget_amount refuses a zero ceiling, so this is unreachable — and a division by
            // zero here would be a 500 on the one endpoint a Test Engineer may call.
            return null;
        }
        return spent.multiply(BigDecimal.valueOf(100))
                .divide(budget.getAmount(), PERCENT_SCALE, RoundingMode.HALF_UP);
    }

    private BudgetView viewOf(
            Project project,
            Budget budget,
            Map<UUID, String> clientNames,
            BigDecimal approved,
            Money priced) {

        UUID clientId = project.getClientId();
        String clientName = clientId == null ? null : clientNames.get(clientId);
        return new BudgetView(
                project.getId(),
                project.getKey(),
                project.getName(),
                clientName == null ? null : new ClientRef(clientId, clientName),
                budget.getBasis(),
                budget.getBasis() == BudgetBasis.HOURS ? budget.getAmount() : null,
                budget.getBasis() == BudgetBasis.MONEY
                        ? new Money(budget.getAmount(), budget.getCurrency()) : null,
                approved,
                priced,
                burnPercent(budget, approved, priced));
    }

    private Map<UUID, String> clientNamesOf(Collection<Project> forProjects) {
        List<UUID> ids = forProjects.stream()
                .map(Project::getClientId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        return ids.isEmpty() ? Map.of() : clients.namesOf(ids);
    }

    /**
     * A {@code MONEY} basis requires a currency and an {@code HOURS} basis forbids one.
     *
     * <p>{@code ck_budget_shape} holds the same rule, and both copies are deliberate: this one so
     * the caller gets a field error naming {@code currency}, that one so no writer — including the
     * demo seeder — can store a ceiling that reads two ways.
     */
    private Optional<Map<String, List<String>>> validate(BudgetWriteRequest submitted) {
        if (submitted == null || submitted.basis() == null) {
            return Optional.of(Map.of("basis", List.of("A budget needs a basis.")));
        }
        BigDecimal amount = submitted.amount();
        if (amount == null || amount.signum() <= 0) {
            return Optional.of(Map.of("amount", List.of("A budget has to be more than zero.")));
        }
        String currency = submitted.currency();
        if (submitted.basis() == BudgetBasis.MONEY
                && (currency == null || !CURRENCY.matcher(currency).matches())) {
            return Optional.of(Map.of(
                    "currency",
                    List.of("A budget in money needs a three-letter ISO 4217 currency code.")));
        }
        if (submitted.basis() == BudgetBasis.HOURS && currency != null && !currency.isBlank()) {
            return Optional.of(Map.of(
                    "currency", List.of("A budget in hours has no currency.")));
        }
        return Optional.empty();
    }
}
