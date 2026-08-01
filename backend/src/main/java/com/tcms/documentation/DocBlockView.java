package com.tcms.documentation;

import java.util.UUID;

/**
 * {@code DocBlock} in {@code docs/specs/openapi.yaml} — one block, as the API serves it.
 *
 * <h2>The field the contract has and this response does not: {@code coverage}</h2>
 *
 * <p>The inline coverage marker is sourced from {@code doc_link} joined to the linked cases' latest
 * results, and <strong>nothing can create a {@code doc_link}</strong>. So no block has one, and the
 * field is absent rather than serialized as {@code {linkedCases: 0, …}}.
 *
 * <p>This is the same call {@link com.tcms.repository.TestCaseDetailView} makes for
 * {@code relatedDocuments}, at the other end of the same edge, and it matters more here than it
 * usually would: a zeroed marker does not read as "unknown", it reads as <em>"no test case verifies
 * this statement"</em> — which is what the SPA renders it as, in the words "Not tested". Serializing
 * it would put a specific and alarming claim on every paragraph of every specification in the
 * product, on the strength of a question nobody has asked yet. The contract does not mark it
 * required and the SPA already renders its absence as a blank margin.
 *
 * @param level null on every type but {@code heading}
 */
public record DocBlockView(UUID id, int position, DocBlockType blockType, Integer level, String text) {

    static DocBlockView of(DocBlock block) {
        BlockContent content = block.getContent();
        return new DocBlockView(
                block.getId(),
                block.getPosition(),
                block.getBlockType(),
                content == null ? null : content.level(),
                // Empty rather than null: `text` is required by the contract, and a block whose
                // content row is somehow unreadable must not make the whole page fail its own
                // schema. An empty paragraph renders as an empty paragraph, which is visible.
                content == null || content.text() == null ? "" : content.text());
    }
}
