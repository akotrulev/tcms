package com.tcms.documentation;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

// @Transactional for DocPageRepository's reason — without it these run outside any transaction,
// app.current_company is never set, and RLS returns zero rows.
@Transactional(readOnly = true)
public interface DocBlockRepository extends JpaRepository<DocBlock, UUID> {

    /** One page's blocks in display order. */
    @Query("""
            SELECT b FROM DocBlock b
             WHERE b.pageId = :pageId
             ORDER BY b.position ASC
            """)
    List<DocBlock> findForPage(@Param("pageId") UUID pageId);

    /**
     * The blocks of several pages at once, in page-then-position order.
     *
     * <p>Exists so the page tree could carry block counts without a query per page. Nothing needs
     * that yet — {@code DocPageSummary} has no count — so this has exactly one caller: the delete
     * path, which needs every block of one page and its descendants to check for links before
     * removing any of them.
     */
    @Query("""
            SELECT b FROM DocBlock b
             WHERE b.pageId IN :pageIds
             ORDER BY b.pageId ASC, b.position ASC
            """)
    List<DocBlock> findForPages(@Param("pageIds") Collection<UUID> pageIds);

    /**
     * How many {@code doc_link} rows target any of these blocks.
     *
     * <p><strong>The guard that keeps an edit from silently voiding traceability.</strong>
     * {@code doc_link.target_block_id} is {@code ON DELETE CASCADE}, so removing a block that a case
     * verifies would take the link with it and report success — the silent breakage this module's
     * whole design is arranged against.
     *
     * <p>Nothing can create a {@code doc_link} yet, so this returns 0 for every real request today.
     * It is here rather than in the linking slice on purpose: a rule added after the data exists has
     * to be reconciled with whatever grew without it, and this one is cheap enough that there is no
     * reason to accept that. The test inserts a link row directly to drive the non-zero branch.
     *
     * <p>Native because there is no {@code DocLink} entity — nothing reads links yet, and mapping
     * one to write a single {@code COUNT} would be a second definition of a table this module does
     * not otherwise touch. {@code company_id} is spelled out because a Hibernate filter does not
     * apply to a native query; RLS states the same bound and the two agreeing is what makes a future
     * mismatch return nothing rather than something.
     */
    @Query(nativeQuery = true, value = """
            SELECT COUNT(*)
              FROM doc_link dl
             WHERE dl.company_id = :companyId
               AND dl.target_block_id IN (:blockIds)
            """)
    long countLinksTargeting(
            @Param("companyId") UUID companyId, @Param("blockIds") Collection<UUID> blockIds);
}
