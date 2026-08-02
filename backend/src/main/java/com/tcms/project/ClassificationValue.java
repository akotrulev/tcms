package com.tcms.project;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.Filter;

/**
 * One value of one dimension — "Functional", "Regression", or a node of a project's Feature tree.
 *
 * <p>Soft-deleted ({@link #deletedAt}), because a value may already be attached to authored cases
 * and to executions that ran under it: hard-deleting one would rewrite what a past run was
 * classified as. Every read must therefore exclude deleted rows explicitly — see
 * {@link ClassificationValueRepository#findLiveForDimensions}.
 *
 * <p>{@link ClassificationDimension} still has no factory method for the reason it records — the
 * dimensions themselves are seeded and nothing in Java creates one. This class has one now: the
 * vocabulary editor creates <em>values</em>, and a Feature dimension a client cannot fill is the
 * gap that made the case editor's tree picker (handoff §68) usable and not fillable.
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
     *
     * <p><strong>{@code @ColumnTransformer} is the other half of {@code columnDefinition}, and it
     * was needed the moment {@link #create} appeared.</strong> {@code Suite.path} carries the same
     * pair and its javadoc predicted this one by name — <em>"it will fail exactly like this on the
     * day something does"</em> — because reading works without it (Postgres renders {@code ltree}
     * as text on the way out) while an insert binds the field as {@code varchar} and Postgres
     * refuses the assignment outright: <em>column "path" is of type ltree but expression is of
     * type character varying</em>. Loud, at the first insert, which is the good version.
     */
    @Column(name = "path", nullable = false, columnDefinition = "ltree")
    @ColumnTransformer(write = "CAST(? AS ltree)")
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

    /**
     * A new value of one dimension.
     *
     * <h2>The path label is derived from the id, not from the name — {@code Suite.create}'s
     * decision, for two reasons rather than one</h2>
     *
     * <p>{@code V2}'s {@code tcms_ltree_label()} slugs a name into a label, and the seed uses it,
     * so the obvious implementation here is the same call. {@code repository.Suite}'s factory
     * refused it and
     * wrote down why: a name-derived path makes <strong>every rename a subtree rewrite</strong>,
     * which is a lot of machinery to own for a column nothing displays. That argument transfers
     * unchanged, and this class has a second one the suite tree does not:
     *
     * <p><strong>{@code tcms_ltree_label} has no answer for a name with no ASCII alphanumerics in
     * it.</strong> {@code lower()} + {@code regexp_replace} + {@code trim} of "支付" is the empty
     * string, and an empty {@code ltree} label is not a valid label — the insert fails with a
     * message about the path rather than about the name. A suite is created by a colleague typing
     * a folder name; a <em>Feature</em> value is the client's own product taxonomy, in the client's
     * own language, which is precisely where non-ASCII names arrive first. The label is
     * {@code v} + the value's uuid: unique by construction, immune to renaming, and free of any
     * question about what characters a client may use.
     *
     * <p>The cost is stated rather than hidden: the seeded values keep their name-derived labels
     * ({@code functional}, {@code regression}) and everything this method creates gets a
     * {@code v…} one, so the column has two spellings. It carries no meaning in either — nothing
     * in Java reads {@code path} today, {@link ClassificationValueView} passes it through, and
     * §6q's descendant matching (unbuilt) needs prefixes to nest, which they do. Making the seed
     * agree would be a migration that rewrites rows to change nothing observable.
     *
     * @param parentPath the parent's {@link #path}, or null for a root value
     */
    static ClassificationValue create(
            UUID companyId,
            UUID dimensionId,
            UUID parentId,
            String parentPath,
            String name,
            String color,
            int position) {

        ClassificationValue created = new ClassificationValue();
        created.id = UUID.randomUUID();
        created.companyId = companyId;
        created.dimensionId = dimensionId;
        created.parentId = parentId;
        created.path = parentPath == null
                ? label(created.id)
                : parentPath + "." + label(created.id);
        created.name = name;
        created.color = color;
        created.position = position;
        return created;
    }

    /** The {@code ltree} label for a value — see {@link #create}. */
    private static String label(UUID id) {
        return "v" + id.toString().replace("-", "");
    }

    /**
     * Renames and recolours — the whole of what an edit may change.
     *
     * <p>{@link #parentId}, {@link #path} and {@link #dimensionId} are deliberately not assignable.
     * The first two are the move this slice refuses (see {@code ClassificationValueEdit}); the
     * third is not an edit in any reading — a value that changes axis is a different value, and
     * every case classified by it silently changes what it was asserting.
     *
     * <p>Nothing here touches {@link #path}, and that is the payoff of an id-derived label: a
     * rename is a field assignment rather than a subtree rewrite.
     */
    void rename(String name, String color) {
        this.name = name;
        this.color = color;
    }

    /**
     * Retires the value — a soft delete, for the reason the class comment gives.
     *
     * <p>Idempotence is not this method's problem: the write service refuses a value that is
     * already retired, because {@code findLiveForDimensions} never returns one and a second
     * success for a delete this request did not perform is the untruth {@code SuiteWriteService}
     * names about its own 404.
     */
    void retire(Instant at) {
        this.deletedAt = at;
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
