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
import java.util.Locale;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.Filter;

/**
 * One page of one project's specification space.
 *
 * <p>Soft-deleted, like everything something else can point at: a case links to a <em>block</em> of
 * a page, and removing the row would leave that link resolving to nothing with no record of what it
 * used to mean. Every read must exclude deleted rows explicitly — see
 * {@link DocPageRepository#findLiveForProject}.
 */
@Entity
@Table(name = "doc_page")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class DocPage {

    /** {@code doc_page.title} is unconstrained in the schema; the contract caps it. */
    static final int MAX_TITLE = 200;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "space_id", nullable = false, updatable = false)
    private UUID spaceId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    /** Null for a root page. The client builds the tree from this, not from {@link #path}. */
    @Column(name = "parent_id")
    private UUID parentId;

    /** {@code DOC-1} — company-scoped and immutable (plan §6t). */
    @Column(name = "key", nullable = false, updatable = false)
    private String key;

    /** The number inside {@link #key}. {@code NOT NULL} with no default, so an insert must set it. */
    @Column(name = "key_seq", nullable = false, updatable = false)
    private long keySeq;

    /**
     * Materialized path, labelled from {@link #keySeq} rather than from the title.
     *
     * <p>Both halves of the mapping are load-bearing and {@code Suite.path} records why at length:
     * {@code columnDefinition} because {@code ltree} reports as {@code Types#OTHER} and
     * {@code ddl-auto: validate} rejects the mismatch at boot, and {@code @ColumnTransformer}
     * because an insert otherwise binds this as {@code varchar} and Postgres refuses the assignment
     * outright.
     *
     * <p>The label is {@code d} + {@code keySeq} for {@link com.tcms.repository.Suite#create}'s
     * reasons — unique by construction, stable across retitles, never empty, valid without escaping
     * — and the leading letter marks a path segment as a documentation page rather than a suite
     * when the two turn up beside each other.
     */
    @Column(name = "path", nullable = false, columnDefinition = "ltree")
    @ColumnTransformer(write = "CAST(? AS ltree)")
    private String path;

    /**
     * A URL-friendly form of the title, set once at creation and <strong>never updated</strong>.
     *
     * <p>Not because it is expensive to recompute — because this module exists to refuse
     * text-derived identifiers. A slug that follows the title is the heading-anchor failure at page
     * scale: a link built from it breaks silently the moment somebody rewrites a heading, which is
     * exactly what {@code doc_block.id} is immutable to prevent one level down. Nothing targets a
     * slug today, and it stays that way by never being a moving target.
     */
    @Column(name = "slug", nullable = false, updatable = false)
    private String slug;

    @Column(name = "title", nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private DocPageStatus status;

    /**
     * The authored version — {@code v3} beside the title, and what {@link DocPageVersion} rows are
     * numbered by.
     *
     * <p>Distinct from {@link #lockVersion}, which counts <em>every</em> row update including a
     * status change that authored nothing. Showing the wrong one puts a number beside the title
     * that no history entry matches — the defect {@code TestCase} records having had.
     */
    @Column(name = "current_version_no", nullable = false)
    private int currentVersionNo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    /** Set ⇒ deleted. Excluded from every read; kept so a link still resolves to something. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    /**
     * What this page documents, or null on a free-standing page — see {@link DocSubject} and
     * {@code V29}.
     *
     * <p>Mapped as two columns rather than an {@code @Embeddable}, because the pair is nullable as a
     * unit and Hibernate's null-embeddable handling turns "both null" into a value whose identity
     * depends on which fields it happens to compare. Two columns and one accessor that assembles
     * them is the boring version, and it cannot be wrong.
     *
     * <p><s><strong>Both are {@code updatable = false}.</strong> A page does not change what it is
     * about: the edit path replaces title and blocks, and re-pointing a plan's document at a
     * different plan would silently move somebody's business requirements to another cycle while the
     * partial unique index — which is on the live rows only — quietly allowed it.</s>
     * → <strong>superseded (§97).</strong> The template {@code POST} is retired and a plan now gets
     * its document by <em>adopting</em> a page somebody already wrote — which is a write to these two
     * columns and to nothing else, so the old rule made the feature unimplementable. The risk it
     * guarded against is real and is now held where it belongs: {@link #linkTo} refuses a page that
     * already has a subject, so re-pointing is {@link #unlinkSubject} and then {@link #linkTo} — two
     * requests, two audit rows — and there is still no path that silently moves a document from one
     * plan to another. The edit path is unaffected: nothing in {@link DocPageInput} names a subject,
     * so a rewrite of title and blocks still cannot change what the page is about.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type")
    private DocSubjectType subjectType;

    @Column(name = "subject_id")
    private UUID subjectId;

    /**
     * The {@code Idempotency-Key} the creating request carried, or null (migration {@code V42}).
     *
     * <p>{@code updatable = false} for {@code Suite}'s reason: it describes the request that made
     * the row, not the row's current state. Null on every page created on a plan's behalf — that
     * path carries no header and is deduplicated by {@code uq_doc_page_subject} instead.
     */
    @Column(name = "created_idempotency_key", updatable = false)
    private String createdIdempotencyKey;

    /** On the wire, because the SPA sends it back as {@code If-Match} (plan §6j). */
    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    /** JPA only. */
    protected DocPage() {
    }

    /**
     * A new page, always {@code DRAFT}.
     *
     * <p>The status is not an argument. §6g's rule, applied here for the third time: a state an
     * ordinary create could set is an approval its author granted themselves — and {@code doc:edit}
     * is held by everyone who writes while {@code doc:publish} is not.
     *
     * @param parentPath the parent's {@link #path}, or null for a root page
     * @param subject    what the page documents, or null for a free-standing page — which since §97
     *                   is what every create passes, because the only writer that ever supplied one
     *                   was the retired plan-document template. A page acquires its subject
     *                   afterwards, through {@link #linkTo}. The parameter stays because the column
     *                   pair is nullable as a unit and a page created already documenting something
     *                   is a legitimate shape for the next subject type to want.
     * @param idempotencyKey the creating request's {@code Idempotency-Key}, or null when it sent
     *                   none — always null when {@code subject} is non-null, because that path is
     *                   an internal call with no header and a stronger constraint of its own
     */
    static DocPage create(
            UUID projectId,
            UUID spaceId,
            UUID parentId,
            String parentPath,
            String key,
            long keySeq,
            String title,
            DocSubject subject,
            UUID creatorId,
            String idempotencyKey,
            Instant now) {

        DocPage created = new DocPage();
        if (subject != null) {
            created.subjectType = subject.type();
            created.subjectId = subject.id();
        }
        created.id = UUID.randomUUID();
        created.companyId = CompanyContext.require();
        created.projectId = projectId;
        created.spaceId = spaceId;
        created.parentId = parentId;
        created.key = key;
        created.keySeq = keySeq;
        created.path = parentPath == null ? label(keySeq) : parentPath + "." + label(keySeq);
        created.slug = slugify(title, keySeq);
        created.status = DocPageStatus.DRAFT;
        created.currentVersionNo = 1;
        created.createdAt = now;
        created.createdBy = creatorId;
        created.createdIdempotencyKey = idempotencyKey;
        created.title = title;
        created.updatedAt = now;
        created.updatedBy = creatorId;
        return created;
    }

    /** The {@code ltree} label for a page — see {@link #path}. */
    private static String label(long keySeq) {
        return "d" + keySeq;
    }

    /**
     * A readable slug, suffixed with the key sequence.
     *
     * <p>The suffix is not decoration: two pages legitimately share a title ("Overview" under three
     * different parents), and a slug is a URL fragment people paste. Making it unique by
     * construction means it can never become ambiguous later, which is the property a
     * title-derived string does not otherwise have.
     *
     * <p>A title of nothing but punctuation slugs to the empty string, which is why the sequence is
     * appended rather than used only on collision — {@code "…"} would otherwise produce a slug of
     * {@code ""} and fail a {@code NOT NULL} column with a message about nothing.
     */
    private static String slugify(String title, long keySeq) {
        String base = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        base = base.replaceAll("(^-+)|(-+$)", "");
        if (base.length() > 60) {
            base = base.substring(0, 60).replaceAll("-+$", "");
        }
        return base.isEmpty() ? "page-" + keySeq : base + "-" + keySeq;
    }

    /**
     * Retitles the page, and stamps who did it.
     *
     * <p>One method rather than a setter, for {@code Suite#applyEdit}'s reason: it is not possible
     * to change the title without recording the change. {@link #parentId} and {@link #path} are
     * never re-assigned — {@link DocPageInput} has already refused a payload that asked to move, and
     * assigning a parent here without rewriting every descendant's path would produce the corrupted
     * tree rather than the feature.
     *
     * <p>Does <strong>not</strong> touch {@link #currentVersionNo}: whether an edit authored a new
     * version is {@link PageVersions}' question, and it is answered from the content hash rather
     * than from the fact that a save happened.
     */
    void retitle(String title, UUID editorId, Instant now) {
        this.title = title;
        this.updatedAt = now;
        this.updatedBy = editorId;
    }

    /** Records that a new authored version exists. Called only by {@link PageVersions}. */
    void bumpVersion(int versionNo) {
        this.currentVersionNo = versionNo;
    }

    /**
     * Sets the publication status.
     *
     * <p>Separate from {@link #retitle} because it is a separate permission, and because the two
     * mean different things to a reader: a retitle is authorship and shows up as a version, a
     * status change is a decision about a page whose content nobody touched.
     */
    void setStatus(DocPageStatus status, UUID actorId, Instant now) {
        this.status = status;
        this.updatedAt = now;
        this.updatedBy = actorId;
    }

    /**
     * Adopts this page as the document of {@code subject}.
     *
     * <p>Refuses a page that already documents something, and the refusal is here rather than only
     * in the service for the reason {@link #retitle} is a method rather than a setter: the
     * invariant is the entity's, and a second caller — the next subject type — would otherwise have
     * to remember it. The service checks first so the caller gets a {@code 409} naming the plan;
     * this throw is what a caller who skipped that check gets, and it is a bug rather than a
     * request.
     *
     * <p>Writes no version and does not touch {@link #currentVersionNo}: adopting a page changes
     * nothing about what it says, and a history entry whose diff is empty is §96's rule for tags
     * arriving at a second column.
     */
    void linkTo(DocSubject subject, UUID actorId, Instant now) {
        if (this.subjectType != null) {
            throw new IllegalStateException(
                    "Page " + key + " already documents " + subjectType + " " + subjectId);
        }
        this.subjectType = subject.type();
        this.subjectId = subject.id();
        this.updatedAt = now;
        this.updatedBy = actorId;
    }

    /**
     * Releases this page's subject, keeping the page.
     *
     * <p>Both columns together, because {@code ck_doc_page_subject_paired} refuses a half-cleared
     * row and {@link #getSubject} would read a type with no id as a page documenting nothing it can
     * name.
     */
    void unlinkSubject(UUID actorId, Instant now) {
        this.subjectType = null;
        this.subjectId = null;
        this.updatedAt = now;
        this.updatedBy = actorId;
    }

    /** Soft-deletes this page. The caller has already established it has no children. */
    void softDelete(UUID actorId, Instant now) {
        this.deletedAt = now;
        this.updatedAt = now;
        this.updatedBy = actorId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getParentId() {
        return parentId;
    }

    public String getKey() {
        return key;
    }

    public long getKeySeq() {
        return keySeq;
    }

    public String getPath() {
        return path;
    }

    public String getSlug() {
        return slug;
    }

    public String getTitle() {
        return title;
    }

    public DocPageStatus getStatus() {
        return status;
    }

    public int getCurrentVersionNo() {
        return currentVersionNo;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    /**
     * What this page documents, or null when it is free-standing.
     *
     * <p>Assembled here rather than exposing the two columns, so no caller can read one without the
     * other and conclude a page has a subject from a type alone.
     */
    DocSubject getSubject() {
        return subjectType == null ? null : new DocSubject(subjectType, subjectId);
    }

    public long getLockVersion() {
        return lockVersion;
    }
}
