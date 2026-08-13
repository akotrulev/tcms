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
 * what decides which one an inherited row lands in.
 *
 * <p>~~The gap it was recorded as is narrower now and still real — {@code GET
 * /projects/{projectId}/suites} serves {@code CaseConditionRef}, which carries no {@code kind}
 * field, so <em>that</em> response still arrives undifferentiated.~~ → <strong>closed in
 * §186</strong>, and closed the way the case endpoint already had: the suite response carries
 * <em>two</em> arrays, so which array a row is in says what the field would have. Adding
 * {@code kind} to {@code CaseConditionRef} was the alternative and would have put a redundant field
 * on every case row. See {@link SuiteConditionView}.
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

    /**
     * Attaches a library condition to a suite — <strong>the first writer this table has ever
     * had</strong>, which is why {@link SuiteConditionView} could describe the read side in detail
     * while nothing in {@code src/main} constructed one of these.
     *
     * <p>The reference form only, on {@link CaseCondition#of}'s terms exactly: {@code inline_body}
     * stays null, {@code V1}'s {@code CHECK ((condition_id IS NULL) &lt;&gt; (inline_body IS NULL))}
     * is satisfied by {@code conditionId} being non-null, and the caller guarantees that by having
     * resolved the condition before calling.
     *
     * @param position assigned from the submitted list's order by {@link SuiteConditions#reconcile}
     *                 and never taken from the request — {@code suite_condition} has no constraint
     *                 that would refuse a duplicated or skipped one, so two attachments could share
     *                 position 3 and every case beneath would inherit them in an arbitrary order
     */
    static SuiteCondition of(
            UUID suiteId, UUID conditionId, ConditionKind kind, int position, UUID companyId) {

        SuiteCondition row = new SuiteCondition();
        row.id = UUID.randomUUID();
        row.suiteId = suiteId;
        row.conditionId = conditionId;
        row.kind = kind;
        row.position = position;
        row.companyId = companyId;
        return row;
    }

    /**
     * Moves the row within its kind's list.
     *
     * <p>{@link CaseCondition#moveTo}'s method and, on this table, the one that carries the weight.
     * There it is a preference — nothing references {@code case_condition.id}, so a
     * delete-and-reinsert would have been safe. Here {@code case_condition_optout.suite_condition_id}
     * references this row, so rewriting the list by deleting and reinserting would cascade away
     * every opt-out a case had recorded against an attachment that is still, from the author's point
     * of view, the same one in a new place. See {@link SuiteConditions#reconcile}.
     */
    void moveTo(int newPosition) {
        this.position = newPosition;
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
