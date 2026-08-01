package com.tcms.documentation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * What {@code doc_block.content} holds — the shape the JSONB column takes, as a type.
 *
 * <p>A record rather than a {@code Map<String, Object>}: the column is the only place a block's text
 * lives, so a typo'd key would be a block that renders empty with nothing failing anywhere.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} is <strong>required rather than
 * defensive</strong>. The column is schemaless and the ERD lists block types this release does not
 * model — a {@code table} block will eventually carry rows, an {@code image} an attachment id. Those
 * rows will be read back by this release's code during the deploy window and by any older instance
 * on a rollback. Without this, a block written by a newer version makes the whole page 500 on read,
 * which turns an additive content change into an outage.
 *
 * @param text  the block's words. Empty is legal — an empty paragraph is a spacer somebody typed —
 *              but null is not, and {@link PageContent} refuses it before this record is built.
 * @param level heading depth 1–6, null on every other type. Null rather than 0 because 0 would be a
 *              depth, and a paragraph does not have one.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BlockContent(String text, Integer level) {
}
