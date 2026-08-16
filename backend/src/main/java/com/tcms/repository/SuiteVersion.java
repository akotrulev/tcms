package com.tcms.repository;

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
 * One version of one suite's <strong>composition</strong> — which cases it holds, in what order,
 * nested how (plan §6j).
 *
 * <h2>Composition, explicitly not content — and the distinction is the schema's, not a preference</h2>
 *
 * <p>{@code V1}'s comment on {@code suite_version.snapshot} says it outright: a suite version
 * captures composition and <em>not</em> the content of its cases, which version independently.
 * Otherwise every case edit bumps every ancestor suite, and a folder near the root of a busy project
 * accumulates a version per save made anywhere beneath it — a history nobody can read, attached to
 * the rows it is least useful on.
 *
 * <p>The consequence is the one that surprises: <strong>renaming a suite writes no version here.</strong>
 * A name is content. What writes one is a change to the membership or the nesting, and <del>this
 * slice makes exactly one such change — the folder coming into existence</del> → since §238 every act
 * that changes what the suite <em>resolves to</em> makes one, by any of the three membership arms.
 * See {@link SuiteVersions} for which acts those are and what the fan-out costs.
 *
 * <p>No mutators, for {@link TestCaseVersion}'s reason: a version is created and then only ever
 * read. {@link #pinned} is mapped and never written here — nothing pins yet, so {@code false} is the
 * honest value rather than a placeholder.
 */
@Entity
@Table(name = "suite_version")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class SuiteVersion {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "suite_id", nullable = false, updatable = false)
    private UUID suiteId;

    @Column(name = "version_no", nullable = false, updatable = false)
    private int versionNo;

    /**
     * The composition, as JSON. {@code JsonType} rather than text so a malformed snapshot cannot
     * commit — see {@link TestCaseVersion#getSnapshot()} for the full account.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot", nullable = false, updatable = false)
    private String snapshot;

    /**
     * <del><strong>Written and never read</strong> — in
     * {@code ArchitectureRulesTest.READERLESS_BY_DECISION} since §188, and it is the one member of
     * that list whose absent reader is a direct consequence of a decision argued elsewhere. A
     * content hash exists so a <em>later</em> version can be compared to an earlier one;
     * {@link SuiteVersions} writes v1 at create and has never written a second row, so there is
     * nothing to compare it against.</del>
     *
     * <p><strong>Read since §238 by {@link SuiteVersions#decide}</strong>, and it left
     * {@code READERLESS_BY_DECISION} in the same commit — the exit that entry predicted, arriving by
     * the route it predicted. It said {@code documentation.PageVersions#recordEdit} was what the
     * reader would look like when it came, and it is: compare the newest stored hash, write nothing
     * when it matches. What made the reader possible was giving the table a second writer, not
     * adding a query here.
     */
    @Column(name = "content_hash", nullable = false, updatable = false)
    private String contentHash;

    /**
     * <strong>Never written.</strong> {@code V1} gives all four version tables this column and only
     * {@code test_case_version} has an endpoint that takes one — {@code CaseUpdateRequest}'s
     * {@code changeComment}, which the contract declares and describes as optional by design.
     * <del>A suite version is written at creation and nowhere else ({@link SuiteVersions}), so there
     * is no save for an author to comment on.</del> → §238 gave it more writers and the column is
     * still never written, for a reason that survived the change: a composition version is a
     * consequence of an act on some <em>other</em> row — filing a case, tagging one, deleting one —
     * so there is no field on any of those requests where an author would be writing a note about
     * this folder's history, and inventing one on five endpoints to feed a column nothing renders is
     * a contract change rather than a wiring fix.
     *
     * <p>It was assigned {@code null} in {@link #of} until §188, which is worth knowing rather than
     * deleting quietly: an explicit no-op assignment is indistinguishable from a real one to
     * {@code ArchitectureRulesTest.everyMappedColumnHasAWriter}, which reads bytecode, so two of the
     * three unwritten {@code change_comment} columns were invisible to it and the third — the one
     * whose factory simply omitted the field — was the only one reported. Stating the absence in
     * the by-decision map is what an argument looks like; stating it in an assignment is what
     * silence looks like.
     */
    @Column(name = "change_comment", updatable = false)
    private String changeComment;

    @Column(name = "pinned", nullable = false, updatable = false)
    private boolean pinned;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Nullable in the schema, so nullable here. The missing FK is {@link TestCaseVersion}'s note. */
    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    /** JPA only. */
    protected SuiteVersion() {
    }

    static SuiteVersion of(
            UUID suiteId,
            int versionNo,
            String snapshot,
            String contentHash,
            UUID authorId,
            Instant now) {

        SuiteVersion version = new SuiteVersion();
        version.id = UUID.randomUUID();
        // From the context and never from an argument (ERD §0.1 point 2) — this table holds a copy
        // of client structure, and a company taken from a parameter is one a call site can get wrong.
        version.companyId = CompanyContext.require();
        version.suiteId = suiteId;
        version.versionNo = versionNo;
        version.snapshot = snapshot;
        version.contentHash = contentHash;
        version.pinned = false;
        version.createdAt = now;
        version.createdBy = authorId;
        return version;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSuiteId() {
        return suiteId;
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
