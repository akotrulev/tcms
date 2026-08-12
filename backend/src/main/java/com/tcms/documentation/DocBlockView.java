package com.tcms.documentation;

import java.util.List;
import java.util.UUID;

/**
 * {@code DocBlock} in {@code docs/specs/openapi.yaml} — one block, as the API serves it.
 *
 * <h2>Both {@code links} and {@code coverage} are served, and the difference between an empty one
 * and an absent one is still the point</h2>
 *
 * <p>~~The inline coverage marker is sourced from {@code doc_link} … and <strong>nothing can create a
 * {@code doc_link}</strong>~~ → <strong>superseded (§93)</strong>. {@code BlockLinkService} creates
 * them, so {@link #links} is real: it says <em>which cases claim to verify this statement</em>, which
 * this module knows exactly, and an empty list truthfully means nobody has claimed it.
 *
 * <p>~~{@code coverage} is still absent … {@code passed} / {@code failed} / {@code notRun} are
 * {@code test_execution} joined to {@code test_case}, two modules {@code documentation} may not
 * name~~ → <strong>superseded (§168)</strong>. §166 mirrored each case's newest recorded result onto
 * {@code case_health.last_status_kind}, so the join is now one table in {@code repository} and
 * {@link CatalogueFacts} was already the file allowed to name one. The argument was true when it was
 * written and stopped being true two sections later, which is the third time on this record — it is
 * the reason the {@code MEMBER_DIVERGENCE_BY_DECISION} entry it justified was pinned in <em>both</em>
 * directions, and deleting that entry is what made this build pass.
 *
 * <p>The original argument survives intact and is why a zeroed object is still not the fallback: a
 * zeroed marker does not read as "unknown", it reads as <em>"no test case verifies this
 * statement"</em> — which is what the SPA renders it as, in the words "Not tested". So
 * {@link #coverage} is <strong>null on a block nothing claims to verify</strong> rather than a row of
 * zeros, and the absence is the honest answer where a zero would be a specific and alarming claim on
 * every paragraph of every specification in the product.
 *
 * @param level null on every type but {@code heading} and {@code list}. On a heading it is the
 *              heading depth; on a list it is the bullet's indent depth, which is what makes a
 *              nested test-scenario outline renderable — see {@link PageContent}.
 * @param rows  a {@code table}'s cells, first row the header; null on every other type. Served
 *              <strong>as stored</strong> and never normalised on the way out: the validator
 *              refuses a ragged table on the way in, so a renderer that met one here would be
 *              looking at a row this build did not write, and padding it would hide that.
 * @param attachmentId an {@code image}'s file; null on every other type. The renderer composes the
 *              download URL from it and the route it is already on — see {@code BlockContent}.
 * @param links never null. Empty is the ordinary state and is a real statement, unlike an absent
 *              {@code coverage}, which is the absence of one.
 * @param coverage null on any block no live case of this project claims to <em>verify</em> — which
 *              includes a block carrying only {@code REFERENCES} or {@code DERIVED_FROM} links, so
 *              {@code links} may be non-empty while this is null. See {@link BlockCoverageView}.
 */
public record DocBlockView(
        UUID id,
        int position,
        DocBlockType blockType,
        Integer level,
        String text,
        List<List<String>> rows,
        UUID attachmentId,
        List<BlockLinkView> links,
        BlockCoverageView coverage) {

    static DocBlockView of(DocBlock block, List<BlockLinkView> links, BlockCoverageView coverage) {
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
                // Null rather than empty, and the asymmetry with `text` above is deliberate: the
                // contract makes both nullable and absent means "this block type has none", which
                // an empty array does not — an empty array is a table with no rows, a state the
                // validator refuses precisely because it renders as nothing.
                content == null ? null : content.rows(),
                content == null ? null : content.attachmentId(),
                links,
                coverage);
    }
}
