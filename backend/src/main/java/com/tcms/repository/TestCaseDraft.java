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
 * One case's unsaved editor state — <strong>the autosave buffer, and the half of plan §6j that
 * never shipped.</strong>
 *
 * <p>V1's own comment on the table says what it is for: <em>"Autosave buffer. NEVER versioned. An
 * explicit save promotes this into test_case + a new test_case_version and clears the row — this is
 * what keeps history free of keystroke noise."</em> The version half shipped in §29; this row had no
 * writer until now, so the editor had no autosave at all and closing a tab lost the work.
 *
 * <h2>The buffer is keyed on the CASE, not on the case and the person — and that is the schema's
 * decision, not this class's</h2>
 *
 * <p>{@code case_id} is the primary key. There is exactly one buffer per case, so two people editing
 * one case share it and the last keystroke wins. That is a real consequence and it is why
 * {@link #updatedBy} is served on the wire rather than kept as an internal audit column: a returning
 * editor must be told <em>whose</em> unsaved words they are being offered before they adopt them.
 * Restoring a colleague's draft silently and then pressing Save would publish their half-finished
 * sentence under your name, and nothing in the audit trail would show it had ever been theirs.
 *
 * <p>Making it per-person instead is a migration and a decision, not a refactor — the primary key
 * would have to become {@code (case_id, user_id)}, and "which of the four drafts do I open" is a
 * question the editor has no way to ask. It is recorded here as the option that was <em>not</em>
 * taken, so the single-row shape does not read as an oversight.
 *
 * <h2>No {@code @Version}, deliberately</h2>
 *
 * <p>Every other contested row in this module carries optimistic locking. This one must not: an
 * autosave that answers 409 is an autosave the user cannot resolve, because they never asked for it
 * and there is no dialog to show them. Last-write-wins <em>is</em> the semantics of a keystroke
 * buffer — the loss it permits is bounded by one save interval, and the thing it protects (the
 * committed case) is guarded by {@code test_case}'s own {@code lock_version} on the path that
 * actually writes it.
 *
 * <h2>Never versioned, and therefore never a second history</h2>
 *
 * <p>Nothing here is written to {@code test_case_version} and nothing here is hashed. A draft is not
 * authored content until somebody presses Save, at which point it travels through the ordinary
 * {@code PATCH} — validated, versioned and audited like any other edit. {@link CaseDraftService}
 * states why the server has no "promote" endpoint of its own: it would be a second write path into
 * {@code test_case} that skipped {@link CaseEdit}'s validation, and the buffer's whole premise is
 * that its contents have never been validated.
 */
@Entity
@Table(name = "test_case_draft")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class TestCaseDraft {

    /**
     * The case this buffers, and the primary key — see the class header for what the 1:1 costs.
     *
     * <p>{@code ON DELETE CASCADE} in V1, so deleting a case takes its draft with it. That is only
     * true of a <em>hard</em> delete; this product soft-deletes cases, so a deleted case keeps its
     * buffer and {@link CaseDraftService} refuses to read it through the same
     * {@code findLiveInProject} gate every other case endpoint uses.
     */
    @Id
    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    /**
     * The editor's unsaved state, as JSON.
     *
     * <p>{@code JdbcTypeCode(JSON)} for {@link TestCaseVersion#getSnapshot()}'s reason — the column
     * is {@code JSONB} and Hibernate needs telling — and held as a {@code String} for a different
     * one: this class must not acquire an opinion about the shape. {@link CaseDraftService} owns the
     * one mapping, and the envelope it stores carries the {@code lockVersion} the draft was authored
     * against, which is the field the staleness check reads.
     *
     * <p>Updatable, unlike every other JSONB column in this module. That is the point of a buffer.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content", nullable = false)
    private String content;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Who typed this — nullable in the schema, and served on the wire.
     *
     * <p>Not an internal column. See the class header: the buffer is shared per case, so this is the
     * field that stops a restore silently adopting a colleague's work.
     */
    @Column(name = "updated_by")
    private UUID updatedBy;

    /** JPA only. */
    protected TestCaseDraft() {
    }

    /**
     * A case's first buffered draft.
     *
     * <p>{@code companyId} comes from {@link CompanyContext} rather than from the caller, for the
     * reason ERD §0.1 point 2 gives about every write — and it matters as much here as on the
     * version table, because this row holds a full copy of client content too.
     */
    static TestCaseDraft of(UUID caseId, String content, UUID authorId, Instant now) {
        TestCaseDraft draft = new TestCaseDraft();
        draft.caseId = caseId;
        draft.companyId = CompanyContext.require();
        draft.content = content;
        draft.updatedAt = now;
        draft.updatedBy = authorId;
        return draft;
    }

    /**
     * Replaces the buffered content wholesale.
     *
     * <p>Not a merge. An autosave carries the entire form, so a merge would have to decide what an
     * absent field means — and {@link CaseUpdateRequest}'s header records that absent and null are
     * the same Java value here, which makes that decision undecidable rather than merely awkward.
     */
    void overwrite(String content, UUID authorId, Instant now) {
        this.content = content;
        this.updatedAt = now;
        this.updatedBy = authorId;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public String getContent() {
        return content;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }
}
