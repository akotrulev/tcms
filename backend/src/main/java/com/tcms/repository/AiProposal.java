package com.tcms.repository;

import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A generated test case waiting for a person — {@code V43}'s {@code ai_proposal}, and the boundary
 * §6w.6 names in one sentence: <em>"Nothing generated writes to {@code test_case} without a human
 * accepting it."</em>
 *
 * <h2>What this is not</h2>
 *
 * <p><strong>Not {@link TestCaseDraft}</strong>, and the MCP WIP plan §1.3 refused the reuse in
 * writing. A draft is keyed on an existing case and cleared on save; a proposal has no case — that
 * is what makes it a proposal — and its decision is kept after the decision is made, because a
 * profile whose proposals are rejected four times in five is a measurable fact and rejections that
 * vanish make it unknowable.
 *
 * <p><strong>Not a second write path into {@code test_case}.</strong> {@code TestCaseDraft}'s own
 * javadoc forbids a promote endpoint — <em>"a second write path into test_case that skipped
 * CaseEdit's validation"</em> — and that argument binds here unchanged. {@link #payload} is stored
 * in the {@code CaseUpdateRequest} shape precisely so the accept path can hand it to
 * {@link CaseCreateService} rather than inserting a case of its own: same validator, same
 * {@code TC-n} allocator, same v1 version row, same audit line. An accepted proposal is
 * indistinguishable from a case authored any other way, which is what makes §6g's review workflow
 * apply to it without a single exception clause.
 *
 * <h2>The two verdict columns are two questions, and separating them is the point</h2>
 *
 * <p>{@link #verdict} is what the proposer <em>claims</em>. {@link #verdictSource} is who checked,
 * and therefore what the claim is worth. §6w.1a is categorical that an agent's assertion is never
 * counted as verified, so the second column is <strong>derived by the server</strong> and has no
 * request field — see {@link VerdictSource}. A single merged column would make the headline
 * coverage number inflatable by any caller that sends a string.
 *
 * <h2>Authorship is a person, always</h2>
 *
 * <p>{@link #createdBy} is the token's <em>owner</em> on an agent-authenticated call, never the
 * token — §6j's guarantee, and the reason {@link #createdTokenId} is a separate column rather than
 * an alternative to it. <em>"An agent did it"</em> is never an acceptable line; <em>"Ana's agent,
 * holding Ana's grants, did it"</em> is. On accept, the author of the resulting case is the
 * <em>accepting</em> person, which is a third name and deliberately so: the person who takes
 * responsibility for a case is the one who let it into the repository.
 *
 * <h2>No {@code @Version}</h2>
 *
 * <p>{@link CaseFlake}'s argument, and it is stronger here: a proposal is authored once and decided
 * once. There is no form for two screens to contend over, and the one race that matters — two
 * reviewers deciding the same proposal — is refused by the status transition itself rather than by
 * a lock version the second reviewer would have no way to refresh.
 */
@Entity
@Table(name = "ai_proposal")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class AiProposal {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    /**
     * Where the accepted case would be filed, or null.
     *
     * <p>Not validated here: the suite is checked at <em>accept</em> time, through the same
     * {@code CaseEdit} validation every create runs, because a suite that was live when the
     * proposal was filed may have been deleted by the time somebody reads it. Validating at file
     * time as well would be a second answer to the same question, given a week apart.
     */
    @Column(name = "suite_id")
    private UUID suiteId;

    /**
     * The proposed case, as JSON, in the {@code CaseUpdateRequest} field shape.
     *
     * <p>Held as a {@code String} for {@link TestCaseDraft#getContent()}'s reason — this class must
     * not acquire an opinion about the shape — and {@code @JdbcTypeCode(JSON)} because the column is
     * {@code JSONB} and Hibernate needs telling. Not updatable: there is no {@code PATCH} on a
     * proposal, so {@link #title} below cannot drift from it.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    /**
     * The proposed title, copied out of {@link #payload} so the queue can be listed and sorted
     * without parsing a document per row. Safe to denormalise only because the payload is
     * write-once.
     */
    @Column(name = "title", nullable = false, updatable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "verdict", nullable = false, updatable = false)
    private ProposalVerdict verdict;

    @Enumerated(EnumType.STRING)
    @Column(name = "verdict_source", nullable = false, updatable = false)
    private VerdictSource verdictSource;

    /** Per-step findings, including rejections and unverified claims. Null when none were sent. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "grounding", updatable = false)
    private String grounding;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ProposalStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** The person answerable — a token's owner, never a token. See the class header. */
    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    /** The credential that filed it, or null for a browser session. §132's attribution column. */
    @Column(name = "created_token_id", updatable = false)
    private UUID createdTokenId;

    @Column(name = "created_idempotency_key", updatable = false)
    private String createdIdempotencyKey;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decision_note")
    private String decisionNote;

    @Column(name = "accepted_case_id")
    private UUID acceptedCaseId;

    /** JPA only. */
    protected AiProposal() {
    }

    /**
     * A newly filed proposal, always {@link ProposalStatus#PENDING}.
     *
     * <p>{@code companyId} comes from {@link CompanyContext} rather than from the caller, per ERD
     * §0.1 point 2 — and it matters as much here as on the version tables, because this row holds a
     * full copy of content that came in over the wire.
     *
     * <p>There is no overload taking a {@link VerdictSource}: the caller passes the verdict it was
     * <em>told</em>, and the source is computed from whether it was told anything. That is the one
     * place §6w.1a's rule can be enforced, so it is the only place it is expressible.
     */
    static AiProposal file(
            UUID projectId,
            UUID suiteId,
            String payload,
            String title,
            ProposalVerdict claimed,
            String grounding,
            UUID authorId,
            UUID tokenId,
            String idempotencyKey,
            Instant now) {

        AiProposal proposal = new AiProposal();
        proposal.id = UUID.randomUUID();
        proposal.companyId = CompanyContext.require();
        proposal.projectId = projectId;
        proposal.suiteId = suiteId;
        proposal.payload = payload;
        proposal.title = title;
        proposal.verdict = claimed == null ? ProposalVerdict.UNVERIFIED : claimed;
        // The rule, in the one statement that can hold it. A caller that asserted nothing gets
        // NONE; a caller that asserted anything — including UNVERIFIED, which is an assertion —
        // gets EXTERNAL. TCMS_VERIFIED is unreachable from here and from the CHECK in V43.
        proposal.verdictSource = claimed == null ? VerdictSource.NONE : VerdictSource.EXTERNAL;
        proposal.grounding = grounding;
        proposal.status = ProposalStatus.PENDING;
        proposal.createdAt = now;
        proposal.createdBy = authorId;
        proposal.createdTokenId = tokenId;
        proposal.createdIdempotencyKey = idempotencyKey;
        return proposal;
    }

    /**
     * Records that a person accepted this and names the case it became.
     *
     * <p>Both halves in one call, because {@code ai_proposal_accepted_names_its_case} refuses an
     * {@code ACCEPTED} row without a case id — so an accept written in two statements would have to
     * pass through a state the database does not permit.
     */
    void accepted(UUID caseId, UUID deciderId, Instant now) {
        this.status = ProposalStatus.ACCEPTED;
        this.acceptedCaseId = caseId;
        this.decidedBy = deciderId;
        this.decidedAt = now;
    }

    /** Records that a person rejected this, with the reason they gave. */
    void rejected(UUID deciderId, String note, Instant now) {
        this.status = ProposalStatus.REJECTED;
        this.decisionNote = note;
        this.decidedBy = deciderId;
        this.decidedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getSuiteId() {
        return suiteId;
    }

    public String getPayload() {
        return payload;
    }

    public String getTitle() {
        return title;
    }

    public ProposalVerdict getVerdict() {
        return verdict;
    }

    public VerdictSource getVerdictSource() {
        return verdictSource;
    }

    public String getGrounding() {
        return grounding;
    }

    public ProposalStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public UUID getCreatedTokenId() {
        return createdTokenId;
    }

    public String getCreatedIdempotencyKey() {
        return createdIdempotencyKey;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public UUID getDecidedBy() {
        return decidedBy;
    }

    public String getDecisionNote() {
        return decisionNote;
    }

    public UUID getAcceptedCaseId() {
        return acceptedCaseId;
    }
}
