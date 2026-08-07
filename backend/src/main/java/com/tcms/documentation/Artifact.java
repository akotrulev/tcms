package com.tcms.documentation;

import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * One row of the artifact register — plan §6n.
 *
 * <p>Not soft-deleted, unlike {@link DocPage}, and the difference is worth stating because the two
 * sit in one module. A doc page is soft-deleted because a case's {@code doc_link} targets a
 * <em>block</em> of it and removing the row would leave that link resolving to nothing. What
 * replaces soft deletion here is {@link ArtifactStatus#SUPERSEDED} — a record that stays reachable
 * *because it is still the truth about what was signed off*, not because something points at it.
 *
 * <p>~~Nothing targets an artifact: {@code superseded_by_id} points the other way, from the
 * replacement to the replaced, so removing a row breaks no inbound edge.~~ <strong>→ corrected
 * 2026-08-07, when {@code artifact:delete} got its endpoint (plan §8 #53).</strong> That sentence
 * was written when nothing could delete a row, so nothing could disprove it. {@code superseded_by_id}
 * is a self-referencing foreign key: whichever direction it is read in, the row it points <em>at</em>
 * has an inbound edge, and deleting that row is a constraint violation. {@code ArtifactRepository}
 * refuses it explicitly rather than letting the database answer with a 500. The claim survived
 * because it described the schema from memory and no check read the schema.
 *
 * <p>~~<strong>Four columns of {@code V1}'s table are deliberately unmapped:</strong>
 * {@code attachment_id}, {@code report_artifact_id} and the two halves of the tags/version story
 * plan §6n describes.~~ <strong>→ three, since §126 phase 3 gave {@link ArtifactKind#FILE} a
 * writer.</strong> The rule stands and the reason expired for one column: mapping a field
 * <em>this slice cannot set</em> would put an always-null accessor on the wire's other side and
 * invite a caller to believe it means something — the call {@code TestCaseDetailView} made for
 * {@code relatedDocuments} and §57 made for {@code coverage}: absent rather than zeroed. This slice
 * can set {@code attachment_id}, so the condition the exclusion was written under no longer holds.
 * That is the fifth time in this project a "blocked" note has outlived its blocker, and the note
 * itself is what made it cheap to notice. {@code ddl-auto: validate} checks that every mapped field
 * has a column and never the reverse, so the remaining three stay inert.
 *
 * <h2>{@code attachment_id} and {@code attachment.owner_type = 'ARTIFACT'} are not rivals</h2>
 *
 * <p>Both are written for a {@code FILE}, and a reader meeting the two for the first time will
 * assume the schema declared the same relationship twice and picked wrong. It did not.
 * {@code attachment.owner_id}/{@code owner_type} are {@code NOT NULL}: there is no such thing as an
 * unowned blob, so the bytes behind an artifact <em>must</em> name it as their owner. This column is
 * the other direction and it carries different information — it is the {@code FILE} kind's
 * <strong>payload</strong>, the exact sibling of {@link #docPageId} for {@code DOC_PAGE_REF} and
 * {@link #externalUrl} for {@code EXTERNAL_URL}. Four kinds, four payload columns, and this one is
 * single-valued where the owner side is not: a case or a page has a <em>list</em> of files, while a
 * {@code FILE} artifact <em>is</em> one file. Plan §6n replaces it by superseding, never by
 * attaching a second.
 */
@Entity
@Table(name = "artifact")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class Artifact {

    /** {@code artifact.title} is unconstrained in the schema; the contract caps it. */
    static final int MAX_TITLE = 200;

    /** Likewise {@code category} — free text because §6n calls the list project-configurable. */
    static final int MAX_CATEGORY = 60;

    static final int MAX_DESCRIPTION = 2000;

    static final int MAX_URL = 2000;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, updatable = false)
    private ArtifactScope scope;

    /**
     * The project, at either scope.
     *
     * <p>{@code V1}'s {@code ck_artifact_scope} requires it at {@code PROJECT} scope and merely
     * permits it at {@code PLAN} scope. It is always set here regardless, because every read is
     * "this project's register" and a plan-scope row whose project were null could only be found by
     * joining through the plan — a join into {@code execution}, which this module may not make.
     */
    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    /**
     * The plan, at {@code PLAN} scope only, and {@code null} at {@code PROJECT} scope.
     *
     * <p>A raw {@code UUID} rather than a {@code @ManyToOne TestPlan}, and that is a module boundary
     * rather than a mapping preference: {@code test_plan} belongs to {@code execution}, which this
     * module's {@code allowedDependencies} does not list and must not. The database has the foreign
     * key — {@code V1} declares it, so referential integrity is real — while the Java side holds only
     * the id, which is exactly the shape ArchUnit's cross-module rule permits.
     */
    @Column(name = "plan_id", updatable = false)
    private UUID planId;

    @Column(name = "category", nullable = false)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false)
    private ArtifactKind kind;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ArtifactStatus status;

    /** Set for {@link ArtifactKind#DOC_PAGE_REF}. The page is in this same module and project. */
    @Column(name = "doc_page_id", updatable = false)
    private UUID docPageId;

    /** Set for {@link ArtifactKind#EXTERNAL_URL}. Validated to be http(s) before it is stored. */
    @Column(name = "external_url", updatable = false)
    private String externalUrl;

    /**
     * Set for {@link ArtifactKind#FILE} — the {@code attachment} row holding this artifact's bytes.
     *
     * <p>{@code updatable = false}, like its two sibling payload columns, and that is the
     * one-file-per-artifact rule expressed where it cannot be worked around. Replacing the document
     * is superseding it (plan §6n), which produces a second artifact naming the first; an update
     * here would swap the bytes under a row whose {@code created_at}, author and audit trail all go
     * on describing the file that was replaced.
     *
     * <p>A raw {@code UUID} rather than a {@code @ManyToOne Attachment}, for {@link #planId}'s
     * reason pointed at a different module: {@code storage} owns that entity, and while
     * {@code documentation} may import it (it is a foundation module since §125), an association
     * would make every register read fetch a join it does not want — the list renders a filename and
     * a size for one row in ten. The batch read is {@code ArtifactFiles}.
     */
    @Column(name = "attachment_id", updatable = false)
    private UUID attachmentId;

    /**
     * Sign-off records: a correction supersedes, never edits (plan §6n, and {@code V1}'s own column
     * comment). Nothing sets it true yet — no act in this slice creates a sign-off — but the reads
     * and the future edit path both have to honour it, so it is mapped rather than ignored.
     */
    @Column(name = "write_once", nullable = false)
    private boolean writeOnce;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    protected Artifact() {
        // JPA.
    }

    /**
     * Registers a reference.
     *
     * <p>The company comes from {@link CompanyContext} rather than a parameter, for the reason every
     * other entity in this codebase does the same: a company that can be passed in is a company that
     * can be passed in wrongly, and ERD §0.1 point 2 exists to remove that possibility rather than
     * to be careful about it.
     *
     * <p>Validation lives in {@link ArtifactWriteService} and not here. This constructor is reached
     * only after the scope/kind cross-field rules have been decided, because those rules need to
     * answer with a <em>field name</em> in a 400 and an entity cannot produce one.
     *
     * <p><strong>The id is a parameter and was generated here until §126 phase 3</strong>, because a
     * {@code FILE}'s two rows reference each other: the {@code attachment} names this artifact as its
     * owner ({@code owner_id} is {@code NOT NULL}) and this row names that attachment
     * ({@link #attachmentId}), so one of the two ids has to exist before either object does. The
     * alternative — construct the artifact, then set the column on the transient instance — works
     * and is a trap: {@code updatable = false} means the same call on a <em>managed</em> instance
     * compiles, runs, and persists nothing, which is a {@code FILE} artifact with no file and no
     * error. A parameter cannot be forgotten in the second position.
     */
    static Artifact register(
            UUID id,
            UUID projectId,
            ArtifactScope scope,
            UUID planId,
            ArtifactKind kind,
            String category,
            String title,
            String description,
            UUID docPageId,
            String externalUrl,
            UUID attachmentId,
            UUID authorId,
            Instant now) {

        Artifact artifact = new Artifact();
        artifact.id = id;
        artifact.companyId = CompanyContext.require();
        artifact.projectId = projectId;
        artifact.scope = scope;
        artifact.planId = planId;
        artifact.kind = kind;
        artifact.category = category;
        artifact.title = title;
        artifact.description = description;
        artifact.docPageId = docPageId;
        artifact.externalUrl = externalUrl;
        artifact.attachmentId = attachmentId;
        // Never from the input — plan §6g. A create that could choose its own status is an author
        // approving their own sign-off, which is the one thing the register exists to make auditable.
        artifact.status = ArtifactStatus.ACTIVE;
        artifact.writeOnce = false;
        artifact.createdAt = now;
        artifact.createdBy = authorId;
        artifact.updatedAt = now;
        artifact.updatedBy = authorId;
        return artifact;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public ArtifactScope getScope() {
        return scope;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getPlanId() {
        return planId;
    }

    public String getCategory() {
        return category;
    }

    public ArtifactKind getKind() {
        return kind;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public ArtifactStatus getStatus() {
        return status;
    }

    public UUID getDocPageId() {
        return docPageId;
    }

    public String getExternalUrl() {
        return externalUrl;
    }

    /** The blob row behind a {@link ArtifactKind#FILE}, and {@code null} for every other kind. */
    public UUID getAttachmentId() {
        return attachmentId;
    }

    public boolean isWriteOnce() {
        return writeOnce;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }
}
