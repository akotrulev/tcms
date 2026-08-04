package com.tcms.documentation;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The ONE assembler of a page's links — {@code doc_link} rows plus the keys and titles that make
 * them readable.
 *
 * <p>Two callers need this identically: {@link DocPageService} rendering a page and
 * {@link PlanDocumentService} rendering a plan's document, which is the same page reached by a
 * different route. A private copy in each is exactly the shape §58's E1 keeps finding — two
 * byte-identical methods under a comment saying they must not disagree — so it is one component from
 * the start.
 *
 * <h2>Three queries for a whole page, never one per link</h2>
 *
 * <p>The links in one statement, then the cases in one, then the suites in one. The natural loop —
 * resolve each link as it is rendered — is one query per bullet, and a plan's scenario outline is
 * made of bullets.
 *
 * <h2>A link whose source cannot be named is DROPPED</h2>
 *
 * <p>{@link CatalogueFacts} excludes deleted rows, so a link to a case somebody deleted resolves to
 * nothing. It is omitted rather than served with nulls, and the choice is deliberate in both
 * directions: the contract marks {@code sourceKey} and {@code sourceTitle} required, so there is no
 * honest half-object to send, and a bullet reading "linked to (unknown)" is worse than one reading
 * as unlinked — it names a problem the reader cannot act on and cannot clear.
 *
 * <p>The consequence is stated rather than hidden: <strong>the row survives and stops being
 * shown.</strong> That is the correct call for a soft delete, which is reversible — restoring the
 * case brings its links back with it, where deleting the link rows would not.
 */
@Component
class BlockLinks {

    private final DocLinkRepository links;
    private final CatalogueFacts catalogue;

    BlockLinks(DocLinkRepository links, CatalogueFacts catalogue) {
        this.links = links;
        this.catalogue = catalogue;
    }

    /**
     * Every block's links on one page, keyed by block id.
     *
     * <p>Blocks with no links are <em>absent</em> from the map rather than mapped to an empty list.
     * The view supplies {@code []} for a miss, so an empty page costs no map entries at all — which
     * is nearly every page in the product.
     */
    Map<UUID, List<BlockLinkView>> forPage(UUID projectId, UUID pageId) {
        List<DocLink> rows = links.findForPage(pageId);
        if (rows.isEmpty()) {
            return Map.of();
        }

        // Ids grouped by kind, because the two resolve against different tables. LinkedHashSet so
        // the batch is deduped — one case linked from six scenarios is one id in the query — while
        // keeping a stable order for anyone reading the generated SQL.
        Map<LinkSourceType, Set<UUID>> wanted = new EnumMap<>(LinkSourceType.class);
        for (DocLink link : rows) {
            wanted.computeIfAbsent(link.getSourceType(), key -> new LinkedHashSet<>())
                    .add(link.getSourceId());
        }

        Map<LinkSourceType, Map<UUID, CatalogueFacts.CatalogueRef>> resolved =
                new EnumMap<>(LinkSourceType.class);
        for (Map.Entry<LinkSourceType, Set<UUID>> each : wanted.entrySet()) {
            resolved.put(each.getKey(),
                    catalogue.resolve(projectId, each.getKey(), each.getValue()));
        }

        Map<UUID, List<BlockLinkView>> byBlock = new HashMap<>();
        for (DocLink link : rows) {
            BlockLinkView view = render(link, resolved.get(link.getSourceType()));
            if (view != null) {
                byBlock.computeIfAbsent(link.getTargetBlockId(), key -> new ArrayList<>()).add(view);
            }
        }
        return byBlock;
    }

    /** One link, with the source it names — or null when that source can no longer be named. */
    private BlockLinkView render(
            DocLink link, Map<UUID, CatalogueFacts.CatalogueRef> resolved) {

        CatalogueFacts.CatalogueRef source =
                resolved == null ? null : resolved.get(link.getSourceId());
        if (source == null) {
            return null;
        }
        return new BlockLinkView(
                link.getId(),
                link.getSourceType().name(),
                source.id(),
                source.key(),
                source.title(),
                link.getRelationship().name());
    }
}
