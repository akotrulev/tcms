package com.tcms.project;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * One value of one dimension — "Functional", "Regression", or a node of a project's Feature tree.
 *
 * <p>Soft-deleted ({@link #deletedAt}), because a value may already be attached to authored cases
 * and to executions that ran under it: hard-deleting one would rewrite what a past run was
 * classified as. Every read must therefore exclude deleted rows explicitly — see
 * {@link ClassificationValueRepository#findLiveForDimensions}.
 *
 * <p>No factory method, for the reason given on {@link ClassificationDimension}.
 */
@Entity
@Table(name = "classification_value")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class ClassificationValue {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "dimension_id", nullable = false, updatable = false)
    private UUID dimensionId;

    /** Null for a root value — every value of a flat dimension, and a Feature tree's top level. */
    @Column(name = "parent_id")
    private UUID parentId;

    /**
     * Materialized path, so selecting a Feature includes its descendants by default (plan §6q).
     *
     * <p><strong>The {@code columnDefinition} is load-bearing, not decoration</strong> — the same
     * trap {@code AppUser.email} documents for {@code citext}. {@code ltree} is an extension type
     * reporting as {@code Types#OTHER} while a plain {@code String} field maps to {@code varchar},
     * so {@code ddl-auto: validate} rejects the mismatch. Hibernate's validator falls back to
     * comparing the SQL type <em>name</em> when the type codes differ, and naming {@code ltree}
     * here is what satisfies that fallback. Reading stays the default {@code getString}, which
     * pgjdbc answers with the path text.
     *
     * <p><strong>Verified by removing it:</strong> the application context fails to build at all —
     * {@code SchemaManagementException} out of {@code AbstractSchemaValidator} — taking every test
     * in the class with it. This fails at boot rather than at the first query, which is the good
     * kind of failure, and it is the reason a {@code String} field can front an extension type at
     * all. {@code @JdbcTypeCode(OTHER)} is the tempting alternative and is wrong for the reason
     * {@code AppUser.email} records.
     *
     * <p>It is not the display name and must not be treated as one: {@code ltree} labels admit
     * only {@code [A-Za-z0-9_]}, so {@code tcms_ltree_label} sanitizes on the way in and "UI /
     * E2E" has a path that does not spell it. {@link #name} is what a human reads.
     */
    @Column(name = "path", nullable = false, columnDefinition = "ltree")
    private String path;

    @Column(name = "name", nullable = false)
    private String name;

    /** Null for everything the seed creates. Brand colours; status colours are not brandable. */
    @Column(name = "color")
    private String color;

    @Column(name = "position", nullable = false)
    private int position;

    /** Set ⇒ retired. Excluded from every read; kept so past classifications still resolve. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** JPA only. */
    protected ClassificationValue() {
    }

    public UUID getId() {
        return id;
    }

    public UUID getDimensionId() {
        return dimensionId;
    }

    public UUID getParentId() {
        return parentId;
    }

    public String getPath() {
        return path;
    }

    public String getName() {
        return name;
    }

    public String getColor() {
        return color;
    }

    public int getPosition() {
        return position;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
