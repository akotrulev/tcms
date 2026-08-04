package com.tcms.documentation;

import java.util.List;
import java.util.UUID;

/**
 * {@code DocBlock} in {@code docs/specs/openapi.yaml} — one block, as the API serves it.
 *
 * <h2>{@code links} is served and {@code coverage} still is not, and the difference is the point</h2>
 *
 * <p>~~The inline coverage marker is sourced from {@code doc_link} … and <strong>nothing can create a
 * {@code doc_link}</strong>~~ → <strong>superseded (§92)</strong>. {@code BlockLinkService} creates
 * them, so {@link #links} is real: it says <em>which cases claim to verify this statement</em>, which
 * this module knows exactly, and an empty list truthfully means nobody has claimed it.
 *
 * <p>{@code coverage} is still absent, and its reason has moved rather than gone away. Its five
 * required fields include {@code passed} / {@code failed} / {@code notRun}, which are
 * {@code test_execution} joined to {@code test_case} — two modules {@code documentation} may not
 * name, and §81's rule puts a query spanning three modules' tables in {@code work} rather than here.
 * All five are required by the schema, so serving the two that are answerable is not available
 * either.
 *
 * <p>The original argument survives intact and is why a zeroed object is not the fallback: a zeroed
 * marker does not read as "unknown", it reads as <em>"no test case verifies this statement"</em> —
 * which is what the SPA renders it as, in the words "Not tested". It would put a specific and
 * alarming claim on every paragraph of every specification in the product, and now it would do so on
 * paragraphs that <em>do</em> have links, which is worse than before.
 *
 * @param level null on every type but {@code heading} and {@code list}. On a heading it is the
 *              heading depth; on a list it is the bullet's indent depth, which is what makes a
 *              nested test-scenario outline renderable — see {@link PageContent}.
 * @param links never null. Empty is the ordinary state and is a real statement, unlike an absent
 *              {@code coverage}, which is the absence of one.
 */
public record DocBlockView(
        UUID id,
        int position,
        DocBlockType blockType,
        Integer level,
        String text,
        List<BlockLinkView> links) {

    static DocBlockView of(DocBlock block, List<BlockLinkView> links) {
        BlockContent content = block.getContent();
        return new DocBlockView(
                block.getId(),
                block.getPosition(),
                block.getBlockType(),
                content == null ? null : content.level(),
                // Empty rather than null: `text` is required by the contract, and a block whose
                // content row is somehow unreadable must not make the whole page fail its own
                // schema. An empty paragraph renders as an empty paragraph, which is visible.
                content == null || content.text() == null ? "" : content.text(),
                links);
    }
}
