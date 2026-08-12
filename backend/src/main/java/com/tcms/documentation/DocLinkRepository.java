package com.tcms.documentation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

// @Transactional for DocPageRepository's reason — without it these run outside any transaction,
// app.current_company is never set, and RLS returns zero rows.
@Transactional(readOnly = true)
public interface DocLinkRepository extends JpaRepository<DocLink, UUID> {

    /**
     * Every link targeting a block of this page, in a single statement.
     *
     * <p>The subquery on {@code DocBlock} rather than a join is what keeps this one query for a
     * whole page. The alternative — fetch the blocks, then ask for links block by block — is the
     * shape a rendering loop naturally produces and is fifty queries on a document with fifty linked
     * scenarios.
     *
     * <p>Ordered so a block's links render in a stable sequence. Without it Postgres is free to
     * return them in whatever order a plan happens to produce, and two loads of an unchanged page
     * would show the same links in different orders — which reads as a change.
     */
    @Query("""
            SELECT l FROM DocLink l
             WHERE l.targetBlockId IN (SELECT b.id FROM DocBlock b WHERE b.pageId = :pageId)
             ORDER BY l.targetBlockId ASC, l.sourceType ASC, l.sourceId ASC
            """)
    List<DocLink> findForPage(@Param("pageId") UUID pageId);

    /**
     * The existing link from this source to this block, if there is one.
     *
     * <p>What makes the create idempotent: re-asserting a fact already recorded answers 200 with the
     * link that is already there, rather than writing a second row. There is no unique constraint
     * behind this — {@code V1} did not put one on {@code (target_block_id, source_type, source_id)}
     * and adding one is a migration this slice does not need — so <strong>this read is the whole
     * protection</strong>, and a concurrent double-submit can still produce two rows. That is a
     * duplicate bullet in a list, not a corruption, which is why it is stated here rather than
     * defended against with a constraint whose retrofit would first have to reconcile whatever
     * duplicates already exist.
     */
    @Query("""
            SELECT l FROM DocLink l
             WHERE l.targetBlockId = :blockId
               AND l.sourceType = :sourceType
               AND l.sourceId = :sourceId
            """)
    Optional<DocLink> findForSource(
            @Param("blockId") UUID blockId,
            @Param("sourceType") LinkSourceType sourceType,
            @Param("sourceId") UUID sourceId);

    /**
     * Every external link of one source — the read {@link ExternalLinkService} decides both
     * idempotency and deletion on.
     *
     * <p><strong>Deliberately not a predicate per target.</strong> The identity of an external
     * target is four nullable columns (repo, path and two line numbers) and {@code = :param} is
     * <em>unknown</em> rather than true when both sides are null, so the query spelling of "is this
     * already linked" would fail to match a whole-file link against itself and write a duplicate on
     * every second click. The comparison is Java's, over this list; {@code ExternalLinkService#
     * sameTarget} carries the argument.
     *
     * <p>The list is a case's external links, which is a handful — bounded by the shape of the thing
     * rather than by a limit somebody has to remember. {@code findForPage} above is the batched read
     * and it is batched because a page renders every block's links at once; nothing renders every
     * case's.
     *
     * <p>Ordered oldest-first so that two reads of an unchanged case agree, and by id after that:
     * the timestamp comes from one {@code Clock#instant} per request, so two links created in one
     * transaction would otherwise be free to swap places between reads.
     *
     * <p>The predicate is the kind and not {@code targetBlockId IS NULL}. The two select the same
     * rows — {@code ck_doc_link_target} makes a block id mandatory for {@code DOC_BLOCK} and there
     * is no arm that permits one otherwise — and only one of them says what is being asked for.
     */
    @Query("""
            SELECT l FROM DocLink l
             WHERE l.sourceType = :sourceType
               AND l.sourceId = :sourceId
               AND l.targetKind <> 'DOC_BLOCK'
             ORDER BY l.createdAt ASC, l.id ASC
            """)
    List<DocLink> findExternalForSource(
            @Param("sourceType") LinkSourceType sourceType, @Param("sourceId") UUID sourceId);

    /**
     * One link of one page, by id — the read the delete gates on.
     *
     * <p>Scoped to the page rather than fetched by primary key, for {@code findLiveInProject}'s
     * reason: {@code findById} resolves through the persistence context, a path Hibernate filters do
     * not apply to, and the caller reached here through a page's URL. A link id belonging to another
     * page must 404 rather than be deleted from under it.
     */
    @Query("""
            SELECT l FROM DocLink l
             WHERE l.id = :linkId
               AND l.targetBlockId IN (SELECT b.id FROM DocBlock b WHERE b.pageId = :pageId)
            """)
    Optional<DocLink> findInPage(@Param("pageId") UUID pageId, @Param("linkId") UUID linkId);
}
