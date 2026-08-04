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
 * <h2>Only the columns this product writes are mapped</h2>
 *
 * <p>{@code target_kind} has three legal values and only {@code DOC_BLOCK} has a writer; the
 * {@code external_*} columns belong to the other two and are left unmapped rather than mapped as
 * permanently-null fields. An unmapped column is invisible to {@code ddl-auto: validate} and to
 * every reader of this class, which is the honest representation of "designed, not built" — a mapped
 * null suggests a value somebody could set.
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
     * Always {@code DOC_BLOCK} for now — see the class header.
     *
     * <p>Mapped as a String rather than an enum, deliberately: an enum here would have to enumerate
     * {@code EXTERNAL_GIT} and {@code EXTERNAL_URL} to read rows this application does not write,
     * and every switch over it would need arms for two cases that cannot occur.
     */
    @Column(name = "target_kind", nullable = false, updatable = false)
    private String targetKind;

    @Column(name = "target_block_id", updatable = false)
    private UUID targetBlockId;

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
}
