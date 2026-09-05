package com.tcms.billing;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * One project's ceiling, in hours or in money — one per project ({@code uq_budget_project}).
 *
 * <p><strong>One {@link #amount} column and not two</strong>, because a budget has exactly one
 * ceiling: a second nullable column would allow a row with both set and no way to say which one is
 * the ceiling. {@link #currency} is null unless the basis is {@code MONEY}, held by
 * {@code ck_budget_shape} as well as by {@link BudgetService}.
 *
 * <p>What a Project Manager reads off this row is a percentage and, for an hours budget, the hours
 * — never an amount and never a currency (019 D-h). That is {@link BudgetBurnView}'s whole subject,
 * and it is why the project-scope read has a different schema rather than the same one with fields
 * withheld: a schema whose meaning depends on who asked is one no reader can check.
 */
@Entity
@Table(name = "budget")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class Budget {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "basis", nullable = false)
    private BudgetBasis basis;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "currency")
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    /** JPA only. */
    protected Budget() {
    }

    static Budget create(
            UUID id, UUID companyId, UUID projectId, BudgetEdit edit, Instant now) {

        Budget created = new Budget();
        created.id = id;
        created.companyId = companyId;
        created.projectId = projectId;
        created.createdAt = now;
        created.apply(edit, now);
        return created;
    }

    /**
     * Replace the ceiling.
     *
     * <p>{@code PUT /projects/{projectId}/budget} is a target state rather than a verb, so sending a
     * budget for a project that already has one lands here. The currency is set unconditionally,
     * including to null: switching a {@code MONEY} budget to {@code HOURS} must clear it, or the row
     * fails {@code ck_budget_shape} — a shape the database holds is one this method cannot drift
     * from, and it would fail loudly rather than storing a ceiling that reads two ways.
     */
    void apply(BudgetEdit edit, Instant now) {
        this.basis = edit.basis();
        this.amount = edit.amount();
        this.currency = edit.currency();
        this.updatedAt = now;
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

    BudgetBasis getBasis() {
        return basis;
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

    Instant getUpdatedAt() {
        return updatedAt;
    }

    long getLockVersion() {
        return lockVersion;
    }
}
