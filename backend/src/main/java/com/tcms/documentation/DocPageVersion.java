package com.tcms.documentation;

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
 * A page's content at one moment — {@code doc_page_version}.
 *
 * <p>Written from this slice even though nothing reads it, for handoff §3 lesson 5's reason:
 * history you did not record is history you never get back. The diff view is a later slice and can
 * be built against real data or against none, and only one of those is a choice still available
 * afterwards.
 *
 * <p>Held as a {@code String} rather than a mapped graph, the call {@code TestCaseVersion} makes:
 * nothing reads into a snapshot, and deserializing one here would create a second definition of a
 * page's shape that has to be migrated every time the first changes.
 */
@Entity
@Table(name = "doc_page_version")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class DocPageVersion {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "page_id", nullable = false, updatable = false)
    private UUID pageId;

    @Column(name = "version_no", nullable = false, updatable = false)
    private int versionNo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot", nullable = false, updatable = false)
    private String snapshot;

    /** SHA-256 of {@link #snapshot} — the reason a no-op save writes no version (plan §6j). */
    @Column(name = "content_hash", nullable = false, updatable = false)
    private String contentHash;

    /**
     * <strong>Never written</strong> — and the only one of the three that has always looked that
     * way. {@code repository.SuiteVersion} and {@code execution.TestPlanVersion} carry the same
     * unwritten column and both assigned it {@code null} in their factories, which is why §188 found
     * this field alone: {@link #of} omits it, and omission is the only spelling the writer rule can
     * see. All three are in {@code ArchitectureRulesTest.WRITERLESS_BY_DECISION} now.
     *
     * <p>The exit is a contract field. {@code test_case_version} is the one version table whose save
     * takes a comment, and a page save that took one would be a change to {@code DocPageUpdateRequest}
     * rather than a wiring fix here.
     */
    @Column(name = "change_comment", updatable = false)
    private String changeComment;

    /** Kept indefinitely when set. Nothing pins yet; the column's default is false. */
    @Column(name = "pinned", nullable = false)
    private boolean pinned;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    /** JPA only. */
    protected DocPageVersion() {
    }

    static DocPageVersion of(
            UUID pageId, int versionNo, String snapshot, String contentHash, UUID authorId,
            Instant now) {

        DocPageVersion version = new DocPageVersion();
        version.id = UUID.randomUUID();
        version.companyId = CompanyContext.require();
        version.pageId = pageId;
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

    public UUID getPageId() {
        return pageId;
    }

    public int getVersionNo() {
        return versionNo;
    }

    public String getContentHash() {
        return contentHash;
    }
}
