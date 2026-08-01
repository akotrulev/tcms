package com.tcms.documentation;

import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One block of one page — a heading, a paragraph, a code sample. <strong>The unit a requirement is,
 * and the unit a link points at.</strong>
 *
 * <h2>{@link #id} is the whole design, and it is never re-issued</h2>
 *
 * <p>{@code V1}'s comment on this column is unusually emphatic and it is the reason: the id is
 * immutable, generated at creation, and never derived from content, because {@code doc_link} targets
 * {@code (page_id, block_id)}. Editing a page therefore may not delete-and-recreate its blocks —
 * see {@link PageContent}, which exists to make that impossible rather than merely discouraged.
 *
 * <p>{@link #anchorSlug} is the trap the schema names in the same breath. It is a human-readable URL
 * convenience and <strong>never the stored link target</strong>: an anchor derived from heading text
 * breaks the moment the text changes, and breaks <em>quietly</em> — the link still resolves to the
 * page, so nobody discovers it until an audit. It is left null here; nothing generates one yet, and
 * generating one would create the tempting target.
 */
@Entity
@Table(name = "doc_block")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class DocBlock {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "page_id", nullable = false, updatable = false)
    private UUID pageId;

    /** Display order. Separate from identity, which is the point — reordering re-issues nothing. */
    @Column(name = "position", nullable = false)
    private int position;

    @Convert(converter = DocBlockTypeConverter.class)
    @Column(name = "block_type", nullable = false)
    private DocBlockType blockType;

    /**
     * The block's content.
     *
     * <p>{@code @JdbcTypeCode(JSON)} rather than {@code columnDefinition = "jsonb"}, for the reason
     * {@code Condition.body} records: {@code columnDefinition} satisfies {@code ddl-auto: validate}
     * while the parameter is still bound as {@code varchar}, and Postgres does not implicitly cast
     * {@code varchar} to {@code jsonb}.
     *
     * <p>Read <em>into</em> a type rather than held as a {@code String} — unlike
     * {@link com.tcms.repository.TestCaseVersion#getSnapshot()}, because this is live content the
     * API renders on every read, not a snapshot served whole to a diff view.
     *
     * <p>V2's search trigger indexes this column with {@code jsonb_to_tsvector(…, '["string"]')},
     * which picks up every string value and ignores the numbers. So {@code text} is searchable and
     * {@code level} is not, which is correct: nobody searches for a heading depth.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content", nullable = false)
    private BlockContent content;

    /** Human-readable URL convenience only — never a link target. Nothing sets it yet. */
    @Column(name = "anchor_slug")
    private String anchorSlug;

    /** JPA only. */
    protected DocBlock() {
    }

    /**
     * A new block, with a new id.
     *
     * <p>The only path that issues an id. {@link PageContent} calls it exactly when a submitted
     * block carried none, which is what makes "the client sent an id" and "this is a new block" the
     * same question rather than two that can disagree.
     */
    static DocBlock create(UUID pageId, int position, DocBlockType type, BlockContent content) {
        DocBlock created = new DocBlock();
        created.id = UUID.randomUUID();
        created.companyId = CompanyContext.require();
        created.pageId = pageId;
        created.position = position;
        created.blockType = type;
        created.content = content;
        return created;
    }

    /**
     * Rewrites this block in place, keeping its id.
     *
     * <p>Type is mutable along with text: turning a paragraph into a heading is an edit somebody
     * makes while writing, and forcing it through delete-and-recreate would drop the links for a
     * formatting change. That is the exact trade this class exists to make.
     */
    void rewrite(int position, DocBlockType type, BlockContent content) {
        this.position = position;
        this.blockType = type;
        this.content = content;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPageId() {
        return pageId;
    }

    public int getPosition() {
        return position;
    }

    public DocBlockType getBlockType() {
        return blockType;
    }

    public BlockContent getContent() {
        return content;
    }

    public String getAnchorSlug() {
        return anchorSlug;
    }
}
