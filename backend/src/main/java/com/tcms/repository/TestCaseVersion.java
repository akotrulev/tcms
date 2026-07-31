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
 * One authored version of one case — a full snapshot of what it said at that moment (plan §6j).
 *
 * <h2>This is the table the whole product's honesty rests on</h2>
 *
 * <p>A run pins the case <em>version</em> it executed ({@code test_execution.case_version_id}), so
 * without these rows editing a case silently rewrites what every past report claimed was covered
 * (handoff §3, load-bearing detail 1). That is why it is written in the <strong>same
 * transaction</strong> as the change to {@code test_case} and never afterwards: a committed edit
 * whose version row failed to write is a case whose history has a hole exactly where somebody
 * changed something.
 *
 * <h2>A snapshot, not a delta — and the reason is not disk</h2>
 *
 * <p>V1's own comment on the column says it: reconstruction is O(1) and corruption stays local.
 * A delta chain means reading version 3 of a case requires replaying 1 and 2 correctly, so one
 * malformed link silently changes what every later version claims to have said, and the report that
 * cites it looks fine. The snapshot is the resolved content — steps and conditions as they were,
 * not as the library now defines them — which is the other half of the same property: a shared
 * condition edited today must not retroactively change what a tester was told to do last March
 * (handoff §3, load-bearing detail 2).
 *
 * <h2>Why there are no mutators and never will be</h2>
 *
 * <p>{@code audit_event}'s comment says "append-only, NEVER updated"; this table is the same and
 * says it in code instead. A version is created and then only ever read, so the only writable thing
 * on it is {@link #pinned} — and even that is set by a run pinning it, not by anything here. Give
 * this class a setter for {@code snapshot} and "the case as it was executed" becomes editable,
 * which is the one property the table exists to provide.
 *
 * <p>{@link #pinned} is therefore mapped and read and never written by this module. Nothing pins
 * yet — {@code test_execution} has no Java writer — so it is {@code false} on every row this class
 * creates, which is the honest value rather than a placeholder: no execution references any of
 * these versions, because no execution exists.
 */
@Entity
@Table(name = "test_case_version")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class TestCaseVersion {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Column(name = "version_no", nullable = false, updatable = false)
    private int versionNo;

    /**
     * The resolved case, as JSON.
     *
     * <p>{@code JdbcTypeCode(JSON)} rather than a mapped graph or a {@code String}: the column is
     * {@code JSONB} and Hibernate needs telling, and the alternative — a text column holding JSON —
     * would let a malformed snapshot commit. Held as a {@code String} on the Java side all the same,
     * because nothing in this module reads *into* a snapshot: it is written once and served whole to
     * a diff view that does not exist yet. Deserializing it into a type here would create a second
     * definition of a case's shape that has to be migrated every time the first one changes.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot", nullable = false, updatable = false)
    private String snapshot;

    /**
     * A stable digest of {@link #snapshot}, and the reason a no-op save writes no version.
     *
     * <p>Plan §6j: a save whose hash is unchanged writes nothing. Without it, opening a case and
     * pressing Save produces a version, and a case's history becomes a log of who looked at it —
     * at which point the version list stops being usable for the thing it is for, finding the edit
     * that broke a test.
     */
    @Column(name = "content_hash", nullable = false, updatable = false)
    private String contentHash;

    @Column(name = "change_comment", updatable = false)
    private String changeComment;

    /**
     * Set when a run, an approval or a report references this version, making it permanently exempt
     * from retention. Read-only from this module — see the class header.
     */
    @Column(name = "pinned", nullable = false, updatable = false)
    private boolean pinned;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Who authored this version — nullable in the schema, and therefore nullable here.
     *
     * <p>There is no FK to {@code app_user} on this column, or on any of the twenty-three
     * {@code created_by} columns in the schema, despite handoff §3 lesson 10 stating that there is.
     * That is a real gap and its own migration; it is recorded rather than quietly fixed here
     * because adding the constraint needs a decision about what happens to authored content when an
     * account is removed, under a policy that says accounts are suspended and never deleted.
     */
    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    /** JPA only. */
    protected TestCaseVersion() {
    }

    /**
     * The next version of a case.
     *
     * <p>{@code companyId} comes from {@link CompanyContext} rather than from the caller, for the
     * reason ERD §0.1 point 2 gives about every write: a company taken from an argument is a company
     * some future call site can get wrong, and this is a table holding a full copy of client
     * content. The id is assigned here rather than by the database so the caller has it without a
     * flush.
     */
    static TestCaseVersion of(
            UUID caseId,
            int versionNo,
            String snapshot,
            String contentHash,
            String changeComment,
            UUID authorId,
            Instant now) {

        TestCaseVersion version = new TestCaseVersion();
        version.id = UUID.randomUUID();
        version.companyId = CompanyContext.require();
        version.caseId = caseId;
        version.versionNo = versionNo;
        version.snapshot = snapshot;
        version.contentHash = contentHash;
        version.changeComment = changeComment;
        version.pinned = false;
        version.createdAt = now;
        version.createdBy = authorId;
        return version;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCaseId() {
        return caseId;
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
