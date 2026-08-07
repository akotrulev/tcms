package com.tcms.documentation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;

/**
 * What {@code doc_block.content} holds — the shape the JSONB column takes, as a type.
 *
 * <p>A record rather than a {@code Map<String, Object>}: the column is the only place a block's text
 * lives, so a typo'd key would be a block that renders empty with nothing failing anywhere.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} is <strong>required rather than
 * defensive</strong>. The column is schemaless and this record has grown twice — a {@code table}
 * block carries rows and an {@code image} an attachment id, both of which the original release
 * predicted here and did not model. Rows written by a newer version are read back by this release's
 * code during the deploy window and by any older instance on a rollback. Without this, a block
 * written by a newer version makes the whole page 500 on read, which turns an additive content
 * change into an outage. <strong>It is the reason {@code rows} and {@code attachmentId} needed no
 * migration and no backfill</strong>: every block written before they existed reads back with them
 * null, which is exactly what a heading, a paragraph, a list and a code block mean.
 *
 * <p>{@code V2}'s search trigger indexes this column with
 * {@code jsonb_to_tsvector(…, '["string"]')} — <em>every</em> string value, at any depth. So a
 * table's cells became searchable the moment they were stored, with no change to the trigger, and
 * {@code level} stays out because it is a number. {@code attachmentId} is a string and therefore
 * <em>is</em> indexed, which is noise rather than a defect: a uuid tokenises to one term nobody
 * types, and keeping it out would mean either a second filter in the trigger or a shape that hides
 * the id from the column that owns it.
 *
 * @param text  the block's words. Empty is legal on most types — an empty paragraph is a spacer
 *              somebody typed — but null is not, and {@link PageContent} refuses it before this
 *              record is built. On a {@code table} it is the caption; on an {@code image} it is the
 *              alt text, where empty is <em>not</em> legal.
 * @param level heading depth 1–6, indent depth on a list bullet, null on every other type. Null
 *              rather than 0 because 0 would be a depth, and a paragraph does not have one.
 * @param rows  a {@code table}'s cells, row-major, first row the header. Null on every other type.
 * @param attachmentId an {@code image}'s file — an {@code attachment} owned by this page. Null on
 *              every other type. The id and not a URL: the URL is composed by whoever renders the
 *              page, from the route it already knows, so a route change cannot leave stored content
 *              pointing at a path that no longer exists.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BlockContent(String text, Integer level, List<List<String>> rows, UUID attachmentId) {

    /**
     * The submitted block, as content — <strong>the one mapping, so the two writers cannot
     * disagree.</strong>
     *
     * <p>{@code DocPageWriteService} builds content on a create and {@link PageContent#reconcile}
     * builds it on an edit, and until this factory existed both spelled the constructor out. That
     * was two copies of one mapping and the drift it invites is silent in the worst way: a field
     * wired into the edit path and forgotten on the create path produces a page whose table renders
     * on save and comes back empty the first time it is created, with nothing failing at either end.
     *
     * @param input already validated by {@link PageContent}, so the arms are consistent with the
     *              block's type — this method does not re-check them, and nulling a field here to
     *              "be safe" would silently discard content the validator accepted
     */
    static BlockContent of(DocBlockInput input) {
        return new BlockContent(input.text(), input.level(), input.rows(), input.attachmentId());
    }
}
