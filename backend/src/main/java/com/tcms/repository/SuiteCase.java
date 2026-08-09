package com.tcms.repository;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * One case added to one suite <strong>individually</strong> — the second of the three arms of suite
 * membership (V31), beside {@code test_case.suite_id} (filed) and {@link SuiteTagRule} (matched).
 *
 * <p>What it is for: a case belongs in the release suite because somebody decided so, not because
 * of where it is filed and not because of a label it happens to carry. A tag rule cannot express
 * that without inventing a tag per suite, and filing cannot express it at all — a case is filed in
 * one folder.
 *
 * <p><strong>No position.</strong> The primary key is {@code (company_id, suite_id, case_id)},
 * which is the schema saying membership is a <em>set</em>: adding a case the suite already holds is
 * not a second row, and a resolved collection is ordered by the case's own key rather than by the
 * order somebody added things to it.
 *
 * <p>An entity rather than a projection, on {@link CaseTag}'s terms: it is company-owned and read
 * by suite, so the Hibernate filter is worth having applied on top of RLS. The <em>resolution</em>
 * of membership is a native query in {@link SuiteMembership} — it unions three arms across two
 * other tables and no entity graph expresses that — and this mapping exists for the write side.
 */
@Entity
@Table(name = "suite_case")
@IdClass(SuiteCaseId.class)
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class SuiteCase {

    @Id
    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Id
    @Column(name = "suite_id", nullable = false, updatable = false)
    private UUID suiteId;

    @Id
    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    /**
     * When this case was put in this suite — stamped by {@link #of} on every membership and
     * <strong>read by nothing</strong> (§154,
     * {@code ArchitectureRulesTest.READERLESS_BY_DECISION}).
     *
     * <p>§98 made a suite flat: its members are three arms, filed ∪ added ∪ tagged, and this row is
     * the <em>added</em> arm. Its own provenance is written on every add and has never been asked
     * for — "when did this case join this suite" is answerable from the row and no screen, report
     * or query poses it.
     */
    @Column(name = "added_at", nullable = false, updatable = false)
    private Instant addedAt;

    /**
     * Nullable like every other actor column since §66 — offboarding does not un-add a case.
     *
     * <p>{@link #addedAt}'s pair and the worse half of it (§154): this one is not a clock stamp but
     * a <em>caller-supplied actor id</em>, carried from the endpoint through the service to the
     * database on every add, so that nothing can read who did it.
     */
    @Column(name = "added_by", updatable = false)
    private UUID addedBy;

    /** JPA only. */
    protected SuiteCase() {
    }

    /**
     * Adds a case to a suite.
     *
     * <p>{@code companyId} is taken from the caller rather than defaulted: RLS refuses an insert
     * whose value does not match {@code app.current_company} (ERD §0.1), so passing the suite's own
     * company is what makes the row readable again rather than one this connection can write once
     * and never see.
     */
    static SuiteCase of(UUID suiteId, UUID caseId, UUID companyId, UUID addedBy, Instant addedAt) {
        SuiteCase row = new SuiteCase();
        row.companyId = companyId;
        row.suiteId = suiteId;
        row.caseId = caseId;
        row.addedBy = addedBy;
        row.addedAt = addedAt;
        return row;
    }

    public UUID getSuiteId() {
        return suiteId;
    }

    public UUID getCaseId() {
        return caseId;
    }
}
