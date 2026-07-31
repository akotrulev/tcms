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

    public UUID getCaseId() {
        return caseId;
    }

    public String getTag() {
        return tag;
    }
}
