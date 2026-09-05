package com.tcms.billing;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * A cost recorded against a project — <strong>recorded and never invoiced</strong> (scope D24,
 * 019 D-j).
 *
 * <p>D24 admits exactly one invoice line shape, approved time at a rate-card rate, and
 * {@code invoice.html} draws no expense row. This table exists because {@code billing:expense} is in
 * the permission catalogue and a key with no reachable endpoint is the unreachable-row defect roles
 * spec §43 names; its callers are the CLI and MCP verbs. No mockup draws an expenses panel, so no
 * screen renders one, and that absence is a decision rather than an unfinished task.
 *
 * <p>The cost belongs to a <strong>project</strong> and never to a client directly. The client is
 * reached through {@code project.client_id}, which is nullable, so an internal project can carry
 * expenses it can never carry invoices for. That asymmetry is the point: cost is recorded for work
 * nobody bills — an AI assistant's usage is exactly that case, and {@code billing.html}'s
 * <em>Not billed</em> panel says so on the screen.
 *
 * <p><strong>{@link #amount} may be negative</strong> ({@code ck_expense_amount} is {@code <> 0}).
 * A credit note against a recorded cost is a negative expense; refusing it would push every
 * correction into a delete that loses what was corrected.
 */
@Entity
@Table(name = "expense")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class Expense {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "incurred_on", nullable = false)
    private LocalDate incurredOn;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA only. */
    protected Expense() {
    }

    static Expense create(
            UUID id,
            UUID companyId,
            UUID projectId,
            LocalDate incurredOn,
            String description,
            BigDecimal amount,
            String currency,
            Instant now) {

        Expense created = new Expense();
        created.id = id;
        created.companyId = companyId;
        created.projectId = projectId;
        created.incurredOn = incurredOn;
        created.description = description;
        created.amount = amount;
        created.currency = currency;
        created.createdAt = now;
        return created;
    }

    UUID getId() {
        return id;
    }

    UUID getCompanyId() {
        return companyId;
    }

    UUID getProjectId() {
        return projectId;
    }

    LocalDate getIncurredOn() {
        return incurredOn;
    }

    String getDescription() {
        return description;
    }

    BigDecimal getAmount() {
        return amount;
    }

    String getCurrency() {
        return currency;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
