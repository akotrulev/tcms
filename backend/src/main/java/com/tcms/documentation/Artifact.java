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
 * <em>block</em> of it and removing the row would leave that link resolving to nothing. Nothing
 * targets an artifact: {@code superseded_by_id} points the other way, from the replacement to the
 * replaced, so removing a row breaks no inbound edge. What replaces soft deletion here is
 * {@link ArtifactStatus#SUPERSEDED} — a record that stays reachable *because it is still the truth
 * about what was signed off*, not because something points at it.
 *
 * <p><strong>Four columns of {@code V1}'s table are deliberately unmapped:</strong>
 * {@code attachment_id}, {@code report_artifact_id} and the two halves of the tags/version story
 * plan §6n describes. {@code ddl-auto: validate} checks that every mapped field has a column, never
 * the reverse, so an unmapped nullable column is inert. Mapping a field this slice cannot set would
 * put an always-null accessor on the wire's other side and invite a caller to believe it means
 * something — the call {@code TestCaseDetailView} made for {@code relatedDocuments} and §57 made for
 * {@code coverage}: absent rather than zeroed.
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
     */
    static Artifact register(
            UUID projectId,
            ArtifactScope scope,
            UUID planId,
            ArtifactKind kind,
            String category,
            String title,
            String description,
            UUID docPageId,
            String externalUrl,
            UUID authorId,
            Instant now) {

        Artifact artifact = new Artifact();
        artifact.id = UUID.randomUUID();
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
