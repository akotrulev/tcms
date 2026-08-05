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
 * One condition attached to one case directly — the case's <em>own</em> setup or cleanup, as
 * opposed to the {@link SuiteCondition} rows it inherits from the folders above it.
 *
 * <p>The row is a reference <em>or</em> an inline body, never both and never neither —
 * {@code CHECK ((condition_id IS NULL) <> (inline_body IS NULL))} in {@code V1}. Only the reference
 * form is mapped and served, for the reason {@link SuiteConditionView} sets out at length: the
 * contract requires a {@code title} on every condition reference and an inline attachment has no
 * column one could come from. The exclusion is enforced at the query
 * ({@link CaseConditionRepository#findForCase}) so the thing being left out is visible beside the
 * thing being selected.
 *
 * <p>A reference and not a copy while the case is live, because that is the entire value of the
 * library: edit "Log in as standard user" once and 200 cases follow. The copy happens later and
 * exactly once — the conditions are resolved and snapshotted into {@code test_case_version} at
 * version-pin time, so a run's history stays immutable while the live case stays DRY (plan §6f,
 * handoff §3 lesson 2). Nothing in this module writes either side yet.
 *
 * <p>{@link #kind} <em>is</em> mapped here, unlike on {@link SuiteCondition} until this slice: the
 * detail response has two lists and this is what decides which one a row lands in.
 */
@Entity
@Table(name = "case_condition")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class CaseCondition {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    private ConditionKind kind;

    @Column(name = "position", nullable = false)
    private int position;

    /** Null ⇒ the row carries an inline body instead. Never null in anything served today. */
    @Column(name = "condition_id")
    private UUID conditionId;

    /** JPA only. */
    protected CaseCondition() {
    }

    /**
     * Attaches a library condition to a case — the first writer this table has had, which is why the
     * class header said "nothing in this module writes either side yet" until this slice.
     *
     * <p>The reference form only. {@code inline_body} stays null and {@code V1}'s
     * {@code CHECK ((condition_id IS NULL) &lt;&gt; (inline_body IS NULL))} is satisfied by
     * {@code conditionId} being non-null — which the caller guarantees by having resolved the
     * condition before calling. An inline attachment has no column to hold the title the contract
     * requires, so nothing serves one and nothing should create one.
     *
     * @param position appended at the end of its kind's list. Bulk attachment has no meaningful
     *                 place to insert in the middle of a list it cannot see, and appending is the
     *                 only order that is the same for all 500 cases.
     */
    static CaseCondition of(
            UUID caseId, UUID conditionId, ConditionKind kind, int position, UUID companyId) {

        CaseCondition row = new CaseCondition();
        row.id = UUID.randomUUID();
        row.caseId = caseId;
        row.conditionId = conditionId;
        row.kind = kind;
        row.position = position;
        row.companyId = companyId;
        return row;
    }

    /**
     * Moves the row within its kind's list.
     *
     * <p>The only mutable thing about an attachment: which entry it names and which list it is in
     * are its identity, so changing either is a different attachment and goes through
     * {@link #of}. Position is the author's ordering and is assigned from payload order by
     * {@link CaseConditions#reconcile}, never taken from the request — a client-supplied position
     * can duplicate or skip and {@code case_condition} has no constraint that would refuse it, so
     * two attachments could share position 3 and the case detail would order them arbitrarily.
     * {@link CaseEdit}'s step rule, on the other ordered child table.
     */
    void moveTo(int newPosition) {
        this.position = newPosition;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCaseId() {
        return caseId;
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
