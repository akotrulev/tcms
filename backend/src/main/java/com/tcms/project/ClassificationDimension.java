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
 * One classification axis — Test Type, Test Level, Execution Phase, Feature (plan §6d).
 *
 * <h2>{@link #projectId} is nullable, and that is the whole design</h2>
 *
 * <p><strong>Null means COMPANY-level</strong>: the dimension is shared by every project in that
 * company. Test Type, Test Level and Execution Phase are seeded that way and locked, so reporting
 * stays comparable across one client's products; Feature is seeded per project, hierarchical and
 * unlocked, because two products have different feature trees (ERD §3, plan Q12).
 *
 * <p>The consequence for any read is easy to get wrong in the direction that <em>looks</em>
 * correct: a query filtering {@code project_id = :projectId} returns only the empty Feature
 * dimension and drops the three vocabularies every case actually uses. See
 * {@link ClassificationDimensionRepository#findForProject}.
 *
 * <p>Note also what scopes the company-level rows: {@code project_id IS NULL} matches rows in
 * <em>every</em> company, so the project predicate contributes nothing there —
 * {@code company_id} is the only thing standing between one client's vocabulary and another's.
 * Which is why the {@code @Filter} below and the RLS policy underneath it are not decoration.
 *
 * <p>Company-level rather than instance-level is the multi-company correction: each client owns
 * its own COPY of the seeded vocabulary, because one client renaming "Compatibility" must not
 * rename it for another.
 *
 * <h2>No factory method, deliberately</h2>
 *
 * <p>Unlike {@link Project}, nothing in Java creates one of these: the rows come from
 * {@code tcms_seed_company_defaults} / {@code tcms_seed_project_defaults}, which are the only
 * sanctioned writers and are called by {@code provision-company}. A factory here would be dead
 * code until the vocabulary editor exists, and dead code is where the {@code company_id} rule
 * gets forgotten. Add one with its endpoint, not before.
 */
@Entity
@Table(name = "classification_dimension")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class ClassificationDimension {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    /** Null ⇒ company-level, shared by every project in the company. See the class comment. */
    @Column(name = "project_id", updatable = false)
    private UUID projectId;

    /** {@code test_type · test_level · execution_phase · feature}. Unique per company+project. */
    @Column(name = "key", nullable = false, updatable = false)
    private String key;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "cardinality", nullable = false)
    private Cardinality cardinality;

    /**
     * False ⇒ free tags rather than a picked-from list.
     *
     * <p>Mapped but <strong>not on the wire</strong>: the contract's
     * {@code ClassificationDimension} does not carry it, and inventing the field here would
     * commit the SPA to a distinction nothing renders yet. Every seeded dimension is controlled.
     */
    @Column(name = "controlled", nullable = false)
    private boolean controlled;

    /** Only Feature, in the seed. Tells the client to render values as a tree, not a list. */
    @Column(name = "hierarchical", nullable = false)
    private boolean hierarchical;

    /** Locked ⇒ the client may not edit the value list. True for the three company-level ones. */
    @Column(name = "locked", nullable = false)
    private boolean locked;

    /**
     * Display order, conveyed to clients as <em>array order</em> rather than as a field.
     *
     * <p>The contract does not expose it, so a consumer that re-sorts the response has no
     * ordering to re-sort by — which is intended. One authority for order, on this side.
     */
    @Column(name = "position", nullable = false)
    private int position;

    /** JPA only. */
    protected ClassificationDimension() {
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public String getKey() {
        return key;
    }

    public String getName() {
        return name;
    }

    public Cardinality getCardinality() {
        return cardinality;
    }

    public boolean isControlled() {
        return controlled;
    }

    public boolean isHierarchical() {
        return hierarchical;
    }

    public boolean isLocked() {
        return locked;
    }

    public int getPosition() {
        return position;
    }
}
