package com.tcms.billing;

import com.tcms.project.GateResult;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.CompanyContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Expenses — <strong>recorded, and never invoiced</strong> (scope D24, 019 D-j).
 *
 * <p>Scope D24 admits exactly one invoice line shape, approved time at a rate-card rate, and
 * {@code invoice.html} draws no expense row. Nothing in this class can produce one and nothing
 * downstream reads these rows onto an invoice.
 *
 * <p><strong>This surface exists because {@code billing:expense} is in the permission
 * catalogue.</strong> A key with no reachable endpoint is the unreachable-row defect roles spec §43
 * names — the same argument that put the company-scope half of staffing in its own table — and the
 * callers of these three endpoints are the CLI and MCP verbs 019 T1 landed. <strong>No mockup draws
 * an expenses panel, so no screen renders one</strong>, and that absence is a decision rather than
 * an unfinished task.
 *
 * <p>An AI assistant's usage is a <em>cost</em> and would be recorded here. It is never charged to a
 * client — {@code billing.html}'s <em>Not billed</em> panel says so on the screen.
 *
 * <h2>Two keys, and the read is the wider one</h2>
 *
 * <p>{@code billing:read} lists; {@code billing:expense} records and removes. Every role that holds
 * either holds both today, and the split is kept because the catalogue draws it: a key whose
 * endpoints are gated on a different key is the same unreachable row in another spelling.
 */
@Service
public class ExpenseService {

    private static final String READ = "billing:read";
    private static final String EXPENSE = "billing:expense";

    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    private static final int DESCRIPTION_MAX = 500;

    /**
     * The bounds an absent {@code from} or {@code to} widens to.
     *
     * <p>Not {@link LocalDate#MIN}: that is year -999999999, which Postgres cannot represent as a
     * {@code DATE} and which the driver rejects rather than treating as "no bound". These two are
     * inside the type's range on both sides and outside any date a cost was incurred on.
     */
    private static final LocalDate FROM_THE_BEGINNING = LocalDate.of(1, 1, 1);
    private static final LocalDate UNTIL_THE_END = LocalDate.of(9999, 12, 31);

    private final ExpenseRepository expenses;
    private final ProjectRepository projects;
    private final ProjectService access;
    private final Clock clock;

    ExpenseService(
            ExpenseRepository expenses,
            ProjectRepository projects,
            ProjectService access,
            Clock clock) {
        this.expenses = expenses;
        this.projects = projects;
        this.access = access;
        this.clock = clock;
    }

    /**
     * {@code GET /expenses} — {@code billing:read}, newest first, for one project.
     *
     * <p>A {@code projectId} naming nothing this company has answers an <strong>empty list and not a
     * 404</strong>. The contract declares no 404 on this operation and the reason is §6t's: the
     * filter is a filter, and distinguishing "no expenses" from "no such project" would make the
     * endpoint an oracle for project ids in another client's tenancy.
     */
    @Transactional(readOnly = true)
    public GateResult<List<ExpenseView>> list(
            UUID userId, UUID projectId, LocalDate from, LocalDate to) {

        return access.readInCompany(userId, READ, () -> {
            if (projectId == null) {
                return List.<ExpenseView>of();
            }
            Optional<Project> project = projects.findScopedById(projectId);
            List<Expense> rows =
                    expenses.findByProjectIdAndIncurredOnBetweenOrderByIncurredOnDescCreatedAtDesc(
                            projectId,
                            from == null ? FROM_THE_BEGINNING : from,
                            to == null ? UNTIL_THE_END : to);
            String name = project.map(Project::getName).orElse(null);
            List<ExpenseView> views = new ArrayList<>(rows.size());
            for (Expense row : rows) {
                views.add(viewOf(row, name));
            }
            return List.copyOf(views);
        });
    }

    /**
     * {@code POST /expenses} — {@code billing:expense}.
     *
     * <p>Recording one does not create an invoice line and cannot be made to.
     */
    @Transactional
    public GateResult<Outcome<ExpenseView>> record(UUID userId, ExpenseWriteRequest submitted) {
        return access.createGranted(userId, EXPENSE, () -> {
            Optional<Map<String, List<String>>> errors = validate(submitted);
            if (errors.isPresent()) {
                return new Outcome.Invalid<ExpenseView>(errors.get());
            }
            /*
              An unknown project is a 400 on `projectId` rather than a 404, for the reason
              crm.ClientService gives about an unknown client: the foreign key is on `id` alone and
              is not subject to RLS, so another company's project would satisfy it; findScopedById
              runs under RLS and is the check the constraint is not.
            */
            Optional<Project> project = projects.findScopedById(submitted.projectId());
            if (project.isEmpty()) {
                return new Outcome.Invalid<ExpenseView>(Map.of(
                        "projectId", List.of("That project does not exist.")));
            }
            Expense saved = expenses.save(Expense.create(
                    UUID.randomUUID(),
                    CompanyContext.require(),
                    submitted.projectId(),
                    submitted.incurredOn(),
                    submitted.description().strip(),
                    submitted.amount(),
                    submitted.currency(),
                    clock.instant()));
            return new Outcome.Written<>(viewOf(saved, project.get().getName()));
        });
    }

    /**
     * {@code DELETE /expenses/{expenseId}} — {@code billing:expense}.
     *
     * <p>Nothing references it; there is no invoice line to unpick, which is the whole of 019 D-j
     * stated as an absence of work.
     */
    @Transactional
    public GateResult<Outcome<Void>> delete(UUID userId, UUID expenseId) {
        return access.createGranted(userId, EXPENSE, () -> {
            Optional<Expense> found = expenses.findById(expenseId);
            if (found.isEmpty()) {
                return new Outcome.Missing<Void>();
            }
            expenses.delete(found.get());
            return new Outcome.Removed<Void>();
        });
    }

    private static ExpenseView viewOf(Expense row, String projectName) {
        return new ExpenseView(
                row.getId(),
                row.getProjectId(),
                projectName,
                row.getIncurredOn(),
                row.getDescription(),
                new Money(row.getAmount(), row.getCurrency()));
    }

    private Optional<Map<String, List<String>>> validate(ExpenseWriteRequest submitted) {
        if (submitted == null || submitted.projectId() == null) {
            return Optional.of(Map.of(
                    "projectId", List.of("An expense belongs to a project.")));
        }
        if (submitted.incurredOn() == null) {
            return Optional.of(Map.of("incurredOn", List.of("An expense needs a date.")));
        }
        String description = submitted.description();
        if (description == null || description.isBlank()) {
            return Optional.of(Map.of(
                    "description", List.of("An expense needs a description.")));
        }
        if (description.strip().length() > DESCRIPTION_MAX) {
            return Optional.of(Map.of("description", List.of(
                    "A description is at most " + DESCRIPTION_MAX + " characters.")));
        }
        BigDecimal amount = submitted.amount();
        if (amount == null || amount.signum() == 0) {
            /*
              Zero and not negative. ck_expense_amount is `<> 0`, deliberately: a credit note against
              a recorded cost is a negative expense, and refusing it would push every correction into
              a delete that loses what was corrected.
            */
            return Optional.of(Map.of("amount", List.of("An expense of zero is not a cost.")));
        }
        String currency = submitted.currency();
        if (currency == null || !CURRENCY.matcher(currency).matches()) {
            return Optional.of(Map.of("currency", List.of(
                    "A currency is a three-letter ISO 4217 code, upper case.")));
        }
        return Optional.empty();
    }
}
