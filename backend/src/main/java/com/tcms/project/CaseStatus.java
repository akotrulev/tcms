package com.tcms.project;

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
 * A configurable execution status — the six defaults, plus whatever a project adds.
 *
 * <p><strong>Project-scoped, unlike the three classification vocabularies.</strong> That split
 * resolves a contradiction between plan §6f and §6s and reads correctly once a project is a whole
 * client product (ERD §3): two products legitimately differ in login flow and status vocabulary,
 * while "what counts as a Performance test" should not.
 *
 * <p>{@link #projectId} is nullable in the DDL for symmetry with
 * {@link ClassificationDimension}, but the table comment in {@code V1} settles the semantics as
 * project-scoped and the seed writes nothing else. Reads therefore match on the project id alone
 * — a company-level fallback would be inventing a tier no writer produces.
 *
 * <p>No factory method, for the reason given on {@link ClassificationDimension}.
 */
@Entity
@Table(name = "case_status")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class CaseStatus {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "project_id", updatable = false)
    private UUID projectId;

    @Column(name = "name", nullable = false)
    private String name;

    /** The fixed aggregation class behind the configurable name — see {@link StatusKind}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    private StatusKind kind;

    /**
     * Nullable in the schema, {@code required} in the contract — see
     * {@link CaseStatusView#color} for what that mismatch means and what closes it.
     */
    @Column(name = "color")
    private String color;

    /** False ⇒ a result in this status leaves the case still owed a verdict. Retest, Untested. */
    @Column(name = "is_final", nullable = false)
    private boolean isFinal;

    /**
     * Separate from {@link #kind} on purpose: a project may add "Passed with comment", of kind
     * {@code PASSED}, that a stricter exit criterion still wants excluded from its pass rate.
     */
    @Column(name = "counts_as_pass", nullable = false)
    private boolean countsAsPass;

    @Column(name = "position", nullable = false)
    private int position;

    /** JPA only. */
    protected CaseStatus() {
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public String getName() {
        return name;
    }

    public StatusKind getKind() {
        return kind;
    }

    public String getColor() {
        return color;
    }

    public boolean isFinal() {
        return isFinal;
    }

    public boolean isCountsAsPass() {
        return countsAsPass;
    }

    public int getPosition() {
        return position;
    }
}
