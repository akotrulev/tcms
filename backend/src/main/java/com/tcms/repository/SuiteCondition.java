package com.tcms.repository;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * <p>{@link #kind} ({@code PRE}/{@code POST}) <strong>is mapped as of the case detail slice</strong>,
 * having been left off through §24 on the rule that a field mapped before its reader goes stale
 * unnoticed. The reader is {@link CaseConditions}: a case's response has two lists, and this is
 * what decides which one an inherited row lands in. The gap it was recorded as is narrower now and
 * still real — {@code GET /projects/{projectId}/suites} serves {@code CaseConditionRef}, which
 * carries no {@code kind} field, so <em>that</em> response still arrives undifferentiated. See
 * {@link SuiteConditionView}.
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

    /** Setup or cleanup — which of the inheriting case's two lists this row joins. */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    private ConditionKind kind;

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

    public ConditionKind getKind() {
        return kind;
    }

    public int getPosition() {
        return position;
    }

    public UUID getConditionId() {
        return conditionId;
    }
}
