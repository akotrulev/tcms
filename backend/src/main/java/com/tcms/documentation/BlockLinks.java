package com.tcms.documentation;

import com.tcms.project.StatusKind;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The ONE assembler of a page's links — {@code doc_link} rows plus the keys and titles that make
 * them readable, <strong>and, since §168, the coverage marker derived from the same rows</strong>.
 *
 * <p>Two callers need this identically: {@link DocPageService} rendering a page and
 * {@link PlanDocumentService} rendering a plan's document, which is the same page reached by a
 * different route. A private copy in each is exactly the shape §58's E1 keeps finding — two
 * byte-identical methods under a comment saying they must not disagree — so it is one component from
 * the start.
 *
 * <h2>Four queries for a whole page, never one per link</h2>
 *
 * <p>The links in one statement, then the cases in one, then the suites in one, then every linked
 * case's newest result in one. The natural loop — resolve each link as it is rendered — is one query
 * per bullet, and a plan's scenario outline is made of bullets.
 *
 * <p>The fourth is skipped entirely on a page whose links name no case, which is not a
 * micro-optimisation but the shape the other three already have: {@link CatalogueFacts} answers an
 * empty id set without a statement, so a page linked only to suites costs exactly what it did before
 * §168.
 *
 * <h2>The coverage marker is assembled HERE and not in a second component</h2>
 *
 * <p>It is derived from rows this class has already read and already resolved — the same
 * {@code doc_link} set, filtered to the case links that claim verification. A {@code BlockCoverage}
 * component beside this one would re-read {@code doc_link} for a whole page to compute a number
 * about the links this class is rendering in the same response, and the two reads would sit one
 * transaction apart with nothing making them agree about which links exist.
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
     * Every block's links and coverage marker on one page, keyed by block id.
     *
     * <p>Blocks with no links are <em>absent</em> from both maps rather than mapped to an empty list.
     * The view supplies {@code []} for a links miss and {@code null} for a coverage miss, so an empty
     * page costs no map entries at all — which is nearly every page in the product — and the two
     * misses stay different answers, which is {@link DocBlockView}'s whole point.
     */
    PageLinks forPage(UUID projectId, UUID pageId) {
        List<DocLink> rows = links.findForPage(pageId);
        if (rows.isEmpty()) {
            return PageLinks.NONE;
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
        // Case id to display key, per block, insertion-ordered and therefore deduped: `caseKeys` is
        // served in the order `links` renders, and one case linked to one block twice — two
        // relationships, or the double-submit DocLinkRepository#findForSource states it cannot
        // fully prevent — is ONE case that claims to verify it. A count that said two would be a
        // coverage number nobody could reconcile against the bullets it sits above.
        //
        // The KEY is carried from here rather than looked up again later, and that is not a
        // shortcut: it is taken off the view that was just rendered, so a case in the marker and a
        // case in the bullets are the same resolution by construction rather than by an invariant
        // somebody has to re-derive across two loops.
        Map<UUID, LinkedHashMap<UUID, String>> verifiersByBlock = new HashMap<>();
        for (DocLink link : rows) {
            BlockLinkView view = render(link, resolved.get(link.getSourceType()));
            if (view == null) {
                continue;
            }
            byBlock.computeIfAbsent(link.getTargetBlockId(), key -> new ArrayList<>()).add(view);
            if (link.getSourceType() == LinkSourceType.TEST_CASE
                    && link.getRelationship() == LinkRelationship.VERIFIES) {
                verifiersByBlock
                        .computeIfAbsent(link.getTargetBlockId(), key -> new LinkedHashMap<>())
                        .putIfAbsent(link.getSourceId(), view.sourceKey());
            }
        }

        return new PageLinks(byBlock, coverage(projectId, verifiersByBlock));
    }

    /**
     * A marker per block that has at least one verifying case, and no entry for any other.
     *
     * <p>Every case here was resolved on the way in, so a case dropped there — deleted, or another
     * project's — is absent from the bullets and from the marker alike. A marker counting a case
     * whose bullet was not shown is the defect this shares its input to prevent.
     */
    private Map<UUID, BlockCoverageView> coverage(
            UUID projectId, Map<UUID, LinkedHashMap<UUID, String>> verifiersByBlock) {

        if (verifiersByBlock.isEmpty()) {
            return Map.of();
        }

        Set<UUID> everyCase = new LinkedHashSet<>();
        verifiersByBlock.values().forEach(each -> everyCase.addAll(each.keySet()));
        Map<UUID, String> kinds = catalogue.lastResultKinds(projectId, everyCase);

        Map<UUID, BlockCoverageView> byBlock = new HashMap<>();
        for (Map.Entry<UUID, LinkedHashMap<UUID, String>> each : verifiersByBlock.entrySet()) {
            LinkedHashMap<UUID, String> verifiers = each.getValue();
            int passed = 0;
            int failed = 0;
            for (UUID caseId : verifiers.keySet()) {
                // The kind is compared by name rather than parsed into StatusKind: a project that
                // seeds a seventh kind this build has no constant for would throw on read here —
                // an exception rendering a specification, over a number — where an unrecognised
                // name falls through to notRun, which is what "no verdict this reader can act on"
                // already means. §167's DocLinkView refuses to re-declare another module's value
                // set for the same reason, one layer down.
                String kind = kinds.get(caseId);
                if (StatusKind.PASSED.name().equals(kind)) {
                    passed++;
                } else if (StatusKind.FAILED.name().equals(kind)) {
                    failed++;
                }
            }
            int linked = verifiers.size();
            // notRun is the REMAINDER and never its own count: the three parts must sum to
            // linkedCases, and a fourth kind arriving in the domain must land in notRun rather
            // than in none of them. A marker whose parts do not sum is one a reader cannot
            // subtract with.
            byBlock.put(each.getKey(), new BlockCoverageView(
                    linked, passed, failed, linked - passed - failed,
                    List.copyOf(verifiers.values())));
        }
        return byBlock;
    }

    /**
     * One page's rendered link data — the two maps together, because they are read together.
     *
     * <p>A record rather than two return values, and rather than two calls: the coverage marker is
     * derived from the links, so a caller able to fetch one without the other is a caller able to
     * render a block whose marker counts links the page did not show.
     *
     * @param links    block id to its links, in {@code doc_link}'s stable order. Absent means none.
     * @param coverage block id to its marker. Absent means nothing claims to verify that block,
     *                 which is a different statement from a marker reading zero — see
     *                 {@link BlockCoverageView}.
     */
    record PageLinks(
            Map<UUID, List<BlockLinkView>> links, Map<UUID, BlockCoverageView> coverage) {

        /** A page with no links at all — the ordinary case, and it costs no map entries. */
        static final PageLinks NONE = new PageLinks(Map.of(), Map.of());

        List<BlockLinkView> of(UUID blockId) {
            return links.getOrDefault(blockId, List.of());
        }

        BlockCoverageView markerOf(UUID blockId) {
            return coverage.get(blockId);
        }
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
