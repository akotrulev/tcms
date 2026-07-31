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
 * One case's position on one classification axis — Test Type, Test Level, Phase, or a Feature
 * (plan §6d).
 *
 * <p>Four dimensions, one join table, because they differ only in cardinality and hierarchy and
 * both of those are properties of the <em>dimension</em> row rather than of the schema. Splitting
 * them into a column per axis is the shape that cannot survive a client adding a fifth.
 *
 * <h2>{@link #dimensionId} is denormalized, and that is what makes SINGLE enforceable</h2>
 *
 * <p>It is derivable from {@link #valueId} through {@code classification_value}, and it is stored
 * anyway: a SINGLE-cardinality dimension must admit at most one value per case, and that predicate
 * depends on a column in <em>another</em> table, so no unique index can express it. With the
 * dimension here it becomes a trigger over this row alone (V2). Reading it also saves this
 * endpoint a join it would otherwise do once per page.
 */
@Entity
@Table(name = "case_classification")
@IdClass(CaseClassificationId.class)
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class CaseClassification {

    @Id
    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Id
    @Column(name = "value_id", nullable = false, updatable = false)
    private UUID valueId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "dimension_id", nullable = false, updatable = false)
    private UUID dimensionId;

    /** JPA only. */
    protected CaseClassification() {
    }

    public UUID getCaseId() {
        return caseId;
    }

    public UUID getValueId() {
        return valueId;
    }

    public UUID getDimensionId() {
        return dimensionId;
    }
}
