package com.tcms.documentation;

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

/**
 * One traceability link: <em>this case verifies that statement</em>.
 *
 * <p>{@code V1} designed this table and shipped it with no writer. {@link DocBlockRepository} said
 * so at its {@code countLinksTargeting} query — <em>"there is no {@code DocLink} entity — nothing
 * reads links yet"</em> — and both {@code TestCase.relatedDocuments} and {@code DocBlock.coverage}
 * were specified against a table that could only ever be empty. §93 is where that stops.
 *
 * <h2>The source is an unresolvable id, and the target is a block</h2>
 *
 * <p>{@code source_id} names a {@code test_case} or a {@code suite}, both of which live in
 * {@code repository} — a module this one may not import. So it is a {@link UUID} and a kind, checked
 * on the way in through {@link CatalogueFacts} and resolved for display through the same seam. There
 * is no association mapping here and there cannot be one.
 *
 * <p>{@code target_block_id} is a real foreign key with {@code ON DELETE CASCADE}, and the cascade
 * is precisely what the write paths refuse to rely on: {@link DocPageWriteService} counts links
 * before removing a block and answers {@code 409}, because a link disappearing because somebody
 * tidied a page is the silent breakage this module is arranged against. The cascade is the backstop
 * for a page genuinely being destroyed, not the ordinary path.
 *
 * <h2>Four target kinds since {@code V75}, and all of them are mapped</h2>
 *
 * <p>~~{@code target_kind} has three legal values and only {@code DOC_BLOCK} has a writer; the
 * {@code external_*} columns belong to the other two and are left unmapped rather than mapped as
 * permanently-null fields.~~ → <strong>superseded 2026-08-12</strong>: open question #26 resolved
 * <em>build it</em> on 2026-08-02 and nothing had, so the six columns are mapped and
 * {@link ExternalLinkService} writes them.
 *
 * <p>The argument for leaving them unmapped is kept because it was right for as long as it held —
 * "an unmapped column is invisible to {@code ddl-auto: validate} and to every reader of this class,
 * which is the honest representation of designed-not-built; a mapped null suggests a value somebody
 * could set" — and because it names the condition of its own expiry. That is what
 * {@code MEMBER_DIVERGENCE_BY_DECISION}'s header asks of an argument after §168 found one that had
 * outlived its obstacle by five sections.
 *
 * <h2>A row is one kind of link and there is no arm for the other</h2>
 *
 * <p>Four factories since {@code V75}, no setters, and no field that two kinds share except the source. {@code V1}'s
 * {@code ck_doc_link_target} is the backstop and the factories are the gate: a caller cannot
 * assemble a row with a block id <em>and</em> a URL, because no method here takes both. The
 * alternative — one factory taking everything nullable — is a constructor whose valid combinations
 * live in the database rather than in the type.
 */
@Entity
@Table(name = "doc_link")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class DocLink {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, updatable = false)
    private LinkSourceType sourceType;

    @Column(name = "source_id", nullable = false, updatable = false)
    private UUID sourceId;

    /**
     * ~~Always {@code DOC_BLOCK} for now~~ → one of the three, since §169.
     *
     * <p>Still a String rather than an enum, and the reason survived the change of facts: the value
     * set is {@code V1}'s {@code CHECK}, which admits exactly what the write paths here produce, and
     * an enum would be a second declaration of it that a migration could move out from under. The
     * kind is decided by which factory ran, so nothing in this module switches on the stored value —
     * it is carried for the readers that do, which is what {@code V46} says at the mirrored column.
     */
    @Column(name = "target_kind", nullable = false, updatable = false)
    private String targetKind;

    @Column(name = "target_block_id", updatable = false)
    private UUID targetBlockId;

    /**
     * The repository an {@code EXTERNAL_GIT} target lives in, e.g. {@code acme/checkout-service}.
     *
     * <p>Required by {@link ExternalLinkService} and <em>not</em> by {@code ck_doc_link_target},
     * which asks only for a path and a sha. The application is narrower than the column for
     * {@link LinkSourceType}'s reason: a path with no repository names a file in an unnamed project,
     * which is a link nobody can follow, and widening later is a decision where narrowing later is a
     * migration over rows that already exist.
     */
    @Column(name = "external_repo", updatable = false)
    private String externalRepo;

    @Column(name = "external_path", updatable = false)
    private String externalPath;

    /** Null when the link names a whole file rather than a range — both are legitimate. */
    @Column(name = "external_line_start", updatable = false)
    private Integer externalLineStart;

    /** Equal to {@link #externalLineStart} for a single line; never less than it. */
    @Column(name = "external_line_end", updatable = false)
    private Integer externalLineEnd;

    /**
     * Mandatory for {@code EXTERNAL_GIT} — the constraint says so and open question #26 says why:
     * an unpinned line range silently points at different code after the next commit, which is a
     * link that lies rather than a link that breaks (plan §6m).
     */
    @Column(name = "external_commit_sha", updatable = false)
    private String externalCommitSha;

    /** An {@code EXTERNAL_URL} target. {@code http} or {@code https} only — see the service. */
    @Column(name = "external_url", updatable = false)
    private String externalUrl;

    /**
     * Which tracker an {@code EXTERNAL_ISSUE} target lives in ({@code V75}, 004).
     *
     * <p>Free text and no enum, for {@link #targetKind}'s reason doubled: the value set is the
     * database's, and a fourth tracker must be an adapter rather than a migration (D9/D20).
     */
    @Column(name = "external_provider", updatable = false)
    private String externalProvider;

    /**
     * The tracker's <strong>stable id</strong> — Jira's numeric issue id, ADO's work-item id.
     *
     * <p>There is no {@code externalIssueKey} beside it and there must not be (D17): a key moves
     * when an issue moves between projects, and the whole point of storing the id is that the row
     * survives that. The consequence is a rule the readers obey rather than a column: <strong>no
     * display label is ever derived from {@link #externalUrl}</strong>.
     */
    @Column(name = "external_issue_id", updatable = false)
    private String externalIssueId;

    /**
     * The tracker site the id belongs to — stored, never parsed out of {@link #externalUrl}.
     *
     * <p>An id is only unique within a host, so identity is {@code (provider, host, id)}. This
     * column is also what replaces a {@code binding_id}: a foreign key from this table into
     * {@code integration}'s would be a cross-module edge the architecture forbids <em>and</em> would
     * cascade a binding's deletion into somebody's traceability record (D18).
     */
    @Column(name = "external_host", updatable = false)
    private String externalHost;

    /**
     * What the source claims about the target — and <strong>the one mutable column on this
     * row</strong> since §174. See {@link #relabel}: the immutability that made this
     * {@code updatable = false} was a policy about the <em>target coordinates</em>, applied here by
     * being applied to everything.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "relationship", nullable = false)
    private LinkRelationship relationship;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    /**
     * When the relationship was last corrected, or {@code null} for <em>never</em> (§174).
     *
     * <p>Null is an answer and not a gap: it says the row still claims what it claimed when it was
     * made. {@code V48} declines to backfill it from {@code created_at} for exactly that reason —
     * that would state "corrected at creation" about every row that has ever existed, and no later
     * reader could separate the fabricated values from the real ones.
     */
    @Column(name = "updated_at")
    private Instant updatedAt;

    /** Who last corrected it. Nullable for {@link #createdBy}'s reason — an actor may be a token. */
    @Column(name = "updated_by")
    private UUID updatedBy;

    /** JPA only. */
    protected DocLink() {
    }

    /**
     * A new link from a case or suite to a block.
     *
     * <p>~~Every field is {@code updatable = false} and there is no mutator on this class. A link is
     * an assertion, and changing what it points at is two acts — withdrawing one claim and making
     * another — which the API spells as a delete and a create. An editable link would let the record
     * of "who said this verifies that" be rewritten in place with no trace.~~ →
     * <strong>superseded in part (§174)</strong>: see {@link #relabel}. The argument holds
     * completely for the <em>target coordinates</em> and it is kept in place for them; it never
     * held for {@code relationship}, and the trace it was protecting is now actually written
     * ({@code updated_at} / {@code updated_by}) instead of being protected by refusal.
     */
    static DocLink toBlock(
            LinkSourceType sourceType,
            UUID sourceId,
            UUID blockId,
            LinkRelationship relationship,
            UUID creatorId,
            Instant now) {

        DocLink created = new DocLink();
        created.id = UUID.randomUUID();
        created.companyId = CompanyContext.require();
        created.sourceType = sourceType;
        created.sourceId = sourceId;
        created.targetKind = "DOC_BLOCK";
        created.targetBlockId = blockId;
        created.relationship = relationship;
        created.createdAt = now;
        created.createdBy = creatorId;
        return created;
    }

    /**
     * A new link from a case or suite to a range of lines in a repository (plan §6m, #26).
     *
     * <p>{@code lineStart} and {@code lineEnd} may both be null — a link to a whole file — and
     * {@code commitSha} may not. The service validates that before reaching here; this method does
     * not re-check, for the reason {@code DocPage.create} gives: two validators for one rule is two
     * error messages for one mistake, and the one nobody sees is the one that drifts.
     */
    static DocLink toGit(
            LinkSourceType sourceType,
            UUID sourceId,
            String repo,
            String path,
            Integer lineStart,
            Integer lineEnd,
            String commitSha,
            LinkRelationship relationship,
            UUID creatorId,
            Instant now) {

        DocLink created = external(sourceType, sourceId, "EXTERNAL_GIT", relationship, creatorId, now);
        created.externalRepo = repo;
        created.externalPath = path;
        created.externalLineStart = lineStart;
        created.externalLineEnd = lineEnd;
        created.externalCommitSha = commitSha;
        return created;
    }

    /**
     * A new link from a case or suite to an issue in somebody else's tracker ({@code V75}, 004 D7).
     *
     * <p><strong>It carries no title and no status</strong>, and the absence is a security property
     * rather than a shape decision: both would have been fetched with one person's credential, and a
     * shared row rendering them would serve them to somebody who cannot see the issue at all
     * (D5/D15). The client fetches them live per viewer, and renders
     * {@code <provider> issue <id> ↗} when it cannot.
     *
     * <p>It also carries no key ({@link #externalIssueId}) and no binding id ({@link #externalHost}),
     * each for its own reason, both stated at the field.
     *
     * @param url what the caller pasted or the search returned. A <em>pointer</em>, allowed to go
     *            historical — Jira redirects a moved key, and where it does not the href is dead,
     *            which is a visible failure rather than a quiet wrong answer.
     */
    static DocLink toIssue(
            LinkSourceType sourceType,
            UUID sourceId,
            String provider,
            String host,
            String issueId,
            String url,
            LinkRelationship relationship,
            UUID creatorId,
            Instant now) {

        DocLink created = external(sourceType, sourceId, "EXTERNAL_ISSUE", relationship, creatorId, now);
        created.externalProvider = provider;
        created.externalHost = host;
        created.externalIssueId = issueId;
        created.externalUrl = url;
        return created;
    }

    /** A new link from a case or suite to a URL — a published spec, an RFC, a design doc. */
    static DocLink toUrl(
            LinkSourceType sourceType,
            UUID sourceId,
            String url,
            LinkRelationship relationship,
            UUID creatorId,
            Instant now) {

        DocLink created = external(sourceType, sourceId, "EXTERNAL_URL", relationship, creatorId, now);
        created.externalUrl = url;
        return created;
    }

    private static DocLink external(
            LinkSourceType sourceType,
            UUID sourceId,
            String targetKind,
            LinkRelationship relationship,
            UUID creatorId,
            Instant now) {

        DocLink created = new DocLink();
        created.id = UUID.randomUUID();
        created.companyId = CompanyContext.require();
        created.sourceType = sourceType;
        created.sourceId = sourceId;
        created.targetKind = targetKind;
        // targetBlockId stays null, and ck_doc_link_target is what makes that binding rather than
        // conventional: an external row carrying a block id would be a link claiming two targets.
        created.relationship = relationship;
        created.createdAt = now;
        created.createdBy = creatorId;
        return created;
    }

    /**
     * Corrects what this link claims — the only mutation this class permits (§174).
     *
     * <h2>A named act, not a setter</h2>
     *
     * <p>The class header's rule is <em>"three factories, no setters"</em>, and this does not break
     * it: {@code setRelationship} would be a bean accessor that says nothing about what changing the
     * value means, and the point of this one is that it is <strong>one intention</strong> with a
     * required actor and clock. There is no way to move the field without also stamping who moved
     * it, which is what makes the audit columns a property of the type rather than a convention two
     * services have to remember.
     *
     * <h2>Why this field and no other</h2>
     *
     * <p>The immutability of the target coordinates is load-bearing and stays: a link that silently
     * changes which file or which block it points at is worse than one that is withdrawn and remade,
     * because every count drawn off this table keeps agreeing with itself while pointing somewhere
     * new. {@code relationship} is not a coordinate — it is the <em>claim</em>, it is the one field
     * a person realistically gets wrong, and getting it wrong is not a small mistake: the coverage
     * matrix counts {@code VERIFIES} and ignores the other two, so a mislabelled link is a false
     * coverage number rather than a cosmetic error.
     *
     * <h2>Re-asserting the same value writes nothing</h2>
     *
     * <p>Returns whether anything moved, so the caller can skip the event and the audit stamp for a
     * request that changes nothing. This is the {@code POST}'s idempotency rule seen from the update
     * side: re-stating a fact that is already recorded is not an error, and stamping
     * {@code updated_by} for it would report a correction nobody made.
     */
    boolean relabel(LinkRelationship next, UUID actorId, Instant now) {
        if (relationship == next) {
            return false;
        }
        relationship = next;
        updatedAt = now;
        updatedBy = actorId;
        return true;
    }

    public UUID getId() {
        return id;
    }

    LinkSourceType getSourceType() {
        return sourceType;
    }

    UUID getSourceId() {
        return sourceId;
    }

    String getTargetKind() {
        return targetKind;
    }

    UUID getTargetBlockId() {
        return targetBlockId;
    }

    LinkRelationship getRelationship() {
        return relationship;
    }

    String getExternalRepo() {
        return externalRepo;
    }

    String getExternalPath() {
        return externalPath;
    }

    Integer getExternalLineStart() {
        return externalLineStart;
    }

    Integer getExternalLineEnd() {
        return externalLineEnd;
    }

    String getExternalCommitSha() {
        return externalCommitSha;
    }

    String getExternalUrl() {
        return externalUrl;
    }

    String getExternalProvider() {
        return externalProvider;
    }

    String getExternalIssueId() {
        return externalIssueId;
    }

    String getExternalHost() {
        return externalHost;
    }

    UUID getCreatedBy() {
        return createdBy;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
