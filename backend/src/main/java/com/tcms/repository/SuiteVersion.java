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
 * A name is content. What writes one is a change to the membership or the nesting, and this slice
 * makes exactly one such change — the folder coming into existence. See {@link SuiteVersions} for
 * what owes the rest.
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

    @Column(name = "content_hash", nullable = false, updatable = false)
    private String contentHash;

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
        version.changeComment = null;
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
