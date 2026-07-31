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
 * One selection tag on one case — {@code @smoke}, {@code @regression}: what a CI runner filters a
 * suite down to.
 *
 * <h2>The same shape as {@link CaseTag}, and a different job</h2>
 *
 * <p>{@link CaseTag} holds the organisational labels the grid shows; these are what a runner
 * <em>selects</em> on. Plan §6c keeps them in two tables precisely because merging them makes "run
 * the smoke suite" and "cases the release manager flagged for this sprint" the same query — and
 * then the first sprint label someone adds changes what CI executes.
 *
 * <p>Also distinct from {@code test_case.automation_key}, which is the single stable identifier a
 * CI result comes <em>back</em> on. Tags select a set; the key matches one case. Two directions,
 * two mechanisms, and conflating them is how a result lands on whichever case happened to share a
 * tag.
 *
 * <p>Shares {@link CaseTagId} rather than declaring an identical twin: {@code case_automation_tag}
 * and {@code case_tag} have the same primary key down to the column names and types, which is what
 * {@code @IdClass} matches on. A second class with the same two fields would be a copy kept in
 * sync by hand for the sake of a name.
 */
@Entity
@Table(name = "case_automation_tag")
@IdClass(CaseTagId.class)
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class CaseAutomationTag {

    @Id
    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Id
    @Column(name = "tag", nullable = false, updatable = false)
    private String tag;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    /** JPA only. */
    protected CaseAutomationTag() {
    }

    public UUID getCaseId() {
        return caseId;
    }

    public String getTag() {
        return tag;
    }
}
