package com.tcms.repository;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * One free-text label on one case — {@code @regression}, {@code needs-data}, a sprint name.
 *
 * <p><strong>Not</strong> {@code case_automation_tag}, which is the other table with exactly this
 * shape and a different job: those are selection tags a CI runner filters on ({@code @smoke}),
 * these are the organisational labels the grid shows. Plan §6c keeps them apart precisely because
 * merging them makes "run the smoke suite" and "cases the release manager flagged" the same query.
 *
 * <p>An entity rather than a projection off a native query, unlike {@code SuiteCaseCount}: this one
 * is company-owned and read by a set of case ids, so the Hibernate filter is worth having applied
 * on top of RLS. {@code SuiteCaseCount} could not have it — there is no entity behind an aggregate
 * — and that asymmetry is the reason, not inconsistency.
 */
@Entity
@Table(name = "case_tag")
@IdClass(CaseTagId.class)
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class CaseTag {

    @Id
    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Id
    @Column(name = "tag", nullable = false, updatable = false)
    private String tag;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    /** JPA only. */
    protected CaseTag() {
    }

    /**
     * A label on a case — the first writer this table has had.
     *
     * <p>{@code companyId} is taken from the caller rather than defaulted or read from a trigger:
     * every company-owned table carries it, it leads every index, and RLS refuses an insert whose
     * value does not match {@code app.current_company} (ERD §0.1). Passing the case's own company is
     * what makes that check pass rather than a row this connection can write and never read again.
     *
     * <p>No position and no id. The primary key is {@code (case_id, tag)}, which is the schema
     * saying tags are a <em>set</em>: adding one a case already carries is not a second row, and the
     * caller checks membership before inserting rather than relying on a constraint violation to
     * tell it so halfway through a batch of 500.
     */
    static CaseTag of(UUID caseId, String tag, UUID companyId) {
        CaseTag row = new CaseTag();
        row.caseId = caseId;
        row.tag = tag;
        row.companyId = companyId;
        return row;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public String getTag() {
        return tag;
    }
}
