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
 * One tag a suite claims — the third arm of suite membership (V31), and the one whose membership
 * changes without anything writing to the suite.
 *
 * <p><strong>Unioned, never intersected.</strong> A suite with rules {@code smoke} and
 * {@code payments} holds every case carrying <em>either</em>. Intersection is the other defensible
 * reading and it is not this one: a two-tag suite that silently holds nothing is indistinguishable
 * from an empty project, and AND-ing tags is a saved filter on the case grid, which the URL already
 * expresses (`?tag=smoke&amp;tag=payments`, which does AND — a grid narrows, a collection widens).
 *
 * <p>Matched against {@link CaseTag} exactly — same case, same spelling — and never against
 * {@code case_automation_tag}, which is CI selection (plan §6c). Merging the two would make "run
 * the smoke suite" and "cases the release manager flagged" the same query.
 */
@Entity
@Table(name = "suite_tag_rule")
@IdClass(SuiteTagRuleId.class)
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class SuiteTagRule {

    @Id
    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Id
    @Column(name = "suite_id", nullable = false, updatable = false)
    private UUID suiteId;

    @Id
    @Column(name = "tag", nullable = false, updatable = false)
    private String tag;

    /** JPA only. */
    protected SuiteTagRule() {
    }

    /** See {@link SuiteCase#of} for why {@code companyId} is the caller's to pass. */
    static SuiteTagRule of(UUID suiteId, String tag, UUID companyId) {
        SuiteTagRule row = new SuiteTagRule();
        row.companyId = companyId;
        row.suiteId = suiteId;
        row.tag = tag;
        return row;
    }

    public UUID getSuiteId() {
        return suiteId;
    }

    public String getTag() {
        return tag;
    }
}
