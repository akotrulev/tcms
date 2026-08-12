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
 * <h2>All three target kinds are mapped now, and until §169 only one was</h2>
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
 * <p>Three factories, no setters, and no field that two kinds share except the source. {@code V1}'s
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

    @Enumerated(EnumType.STRING)
    @Column(name = "relationship", nullable = false, updatable = false)
    private LinkRelationship relationship;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    /** JPA only. */
    protected DocLink() {
    }

    /**
     * A new link from a case or suite to a block.
     *
     * <p>Every field is {@code updatable = false} and there is no mutator on this class. A link is
     * an assertion, and changing what it points at is two acts — withdrawing one claim and making
     * another — which the API spells as a delete and a create. An editable link would let the record
     * of "who said this verifies that" be rewritten in place with no trace.
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
}
