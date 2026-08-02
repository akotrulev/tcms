package com.tcms.execution;

import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One version of one plan's <strong>scope</strong> — <em>the last of §6j's four versioned aggregates
 * to get an entity at all</em>.
 *
 * <p>{@code test_plan_version} has been in {@code V1} since the beginning with no JPA entity and no
 * native query naming it: the remaining-scope assessment listed it under "tables with schema and no
 * code", and §73 recorded it as the one gap of its family left, since case, suite and doc page each
 * have a writer. Nothing had ever inserted a row.
 *
 * <h2>Scope, explicitly not content — and the distinction is the schema's</h2>
 *
 * <p>{@code V1}'s comment on {@code snapshot} says it: <em>"A plan version captures its SCOPE. Gives
 * the as-executed membership needed by history"</em>. Plan §6j's own table spells the scope out —
 * selected suites and cases, milestone, entry/exit criteria — and {@link PlanSnapshot} is exactly
 * that list and nothing else.
 *
 * <p>Two consequences, both of which are the point rather than a limitation:
 *
 * <ul>
 *   <li><strong>Renaming a plan writes no version.</strong> A name and a description are content.
 *       {@code SuiteVersion} makes the identical call for the identical reason, and it is what stops
 *       the history filling with entries whose diff is empty.</li>
 *   <li><strong>A status move writes no version either</strong> — which {@code TestPlan.applyStatus}
 *       already said in §73, in a javadoc naming this class before it existed. Approving a plan does
 *       not change what is in it.</li>
 * </ul>
 *
 * <p>Configurations are absent from the snapshot even though §6j's table names them, and that is a
 * correction rather than an omission: {@code configuration} has no plan column — {@code V1}
 * implements it per <em>project</em>, and {@link PlanService} serves the project's list on the plan
 * because that is what a run created from it may choose between. A per-plan snapshot of a
 * project-level list would record, against this plan, a change somebody made to another one.
 *
 * <p>No mutators, for {@code repository.TestCaseVersion}'s reason: a version is created and then
 * only ever read. {@link #pinned} is mapped and never written — plan §6j's retention prunes unpinned
 * versions and nothing pins yet, so {@code false} is the honest value rather than a placeholder.
 */
@Entity
@Table(name = "test_plan_version")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class TestPlanVersion {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "plan_id", nullable = false, updatable = false)
    private UUID planId;

    @Column(name = "version_no", nullable = false, updatable = false)
    private int versionNo;

    /**
     * The scope, as JSON. {@code JsonType} rather than text so a malformed snapshot cannot commit —
     * the column is {@code JSONB} and the write is the only thing standing between a bad serializer
     * and a history row nobody can read back.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot", nullable = false, updatable = false)
    private String snapshot;

    @Column(name = "content_hash", nullable = false, updatable = false)
    private String contentHash;

    @Column(name = "change_comment", updatable = false)
    private String changeComment;

    @Column(name = "pinned", nullable = false, updatable = false)
    private boolean pinned;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Nullable in the schema, so nullable here — {@code created_by} carries no foreign key. */
    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    /** JPA only. */
    protected TestPlanVersion() {
    }

    static TestPlanVersion of(
            UUID planId,
            int versionNo,
            String snapshot,
            String contentHash,
            UUID authorId,
            Instant now) {

        TestPlanVersion version = new TestPlanVersion();
        version.id = UUID.randomUUID();
        // From the context and never from an argument (ERD §0.1 point 2) — a company taken from a
        // parameter is one a call site can get wrong, on a table holding a copy of client structure.
        version.companyId = CompanyContext.require();
        version.planId = planId;
        version.versionNo = versionNo;
        version.snapshot = snapshot;
        version.contentHash = contentHash;
        version.changeComment = null;
        version.pinned = false;
        version.createdAt = now;
        version.createdBy = authorId;
        return version;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPlanId() {
        return planId;
    }

    public int getVersionNo() {
        return versionNo;
    }

    public String getSnapshot() {
        return snapshot;
    }

    public String getContentHash() {
        return contentHash;
    }

    public String getChangeComment() {
        return changeComment;
    }

    public boolean isPinned() {
        return pinned;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }
}
