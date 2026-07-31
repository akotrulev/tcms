package com.tcms.repository;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * One condition attached to one suite, and therefore inherited by every case beneath it (§6f).
 *
 * <p>The row is a reference <em>or</em> an inline body, never both and never neither —
 * {@code CHECK ((condition_id IS NULL) <> (inline_body IS NULL))} in {@code V1}. Only the
 * reference form is mapped and served today; see {@link SuiteConditionView} for why the inline
 * form has no representable shape yet, and {@link SuiteConditionRepository#findForSuites} for
 * where that exclusion is enforced.
 *
 * <p>{@code kind} ({@code PRE}/{@code POST}) is deliberately not mapped: the contract's
 * {@code CaseConditionRef} has no field to carry it, so mapping it here would produce a value with
 * nowhere to go. It is a real gap and it is recorded on {@link SuiteConditionView}, not papered
 * over by inventing a field the SPA's generated types do not have.
 *
 * <p>{@code conditionId} is a plain {@code UUID} rather than a mapped {@code @ManyToOne}. Same
 * choice the {@code project} module made for {@code ClassificationValue.dimensionId}, same reason:
 * a mapped association makes the N+1 free to write, and the two aggregates stay independently
 * readable without one.
 */
@Entity
@Table(name = "suite_condition")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class SuiteCondition {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "suite_id", nullable = false, updatable = false)
    private UUID suiteId;

    @Column(name = "position", nullable = false)
    private int position;

    /** Null ⇒ the row carries an inline body instead. Never null in anything served today. */
    @Column(name = "condition_id")
    private UUID conditionId;

    /** JPA only. */
    protected SuiteCondition() {
    }

    public UUID getId() {
        return id;
    }

    public UUID getSuiteId() {
        return suiteId;
    }

    public int getPosition() {
        return position;
    }

    public UUID getConditionId() {
        return conditionId;
    }
}
