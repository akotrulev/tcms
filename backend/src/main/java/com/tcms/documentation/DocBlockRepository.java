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

    // A findForPages(Collection<UUID>) sat here until §152, and its header named "exactly one
    // caller: the delete path, which needs every block of one page AND ITS DESCENDANTS". It had
    // none, and the sentence was wrong about the design as well as about the count: delete refuses
    // outright while countLiveChildren > 0, so it never reaches a descendant's blocks and the
    // plural query it was written for cannot arise. Restore it when the page tree carries block
    // counts — that is the batch read it was really for, and DocPageSummary still has no count.

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

    /**
     * How many blocks on this page name this attachment.
     *
     * <p><strong>The guard that keeps a file delete from leaving a hole in a page.</strong> An
     * {@code image} block stores an attachment id and nothing else — no bytes, no URL — so removing
     * the attachment underneath it leaves a block that renders as a broken image on a specification.
     * There is no foreign key to lean on: the reference lives inside a JSONB document, which is
     * precisely why this count has to exist rather than being left to the database.
     *
     * <p>Native for the JSONB operator, and {@code company_id} is spelled out because a Hibernate
     * filter does not apply to a native query — {@link #countLinksTargeting}'s reason, one method up,
     * and the page bound is here as well so a delete cannot be blocked by another page's block.
     *
     * <p>{@code ->>} yields the id as text, so the parameter is bound as text too. Comparing a
     * {@code uuid} to a {@code text} is an operator Postgres does not have, and the failure is a
     * 500 rather than a wrong answer — worth knowing, because the tempting "fix" is a cast that
     * makes a malformed stored value throw instead of simply not matching.
     */
    @Query(nativeQuery = true, value = """
            SELECT COUNT(*)
              FROM doc_block b
             WHERE b.company_id = :companyId
               AND b.page_id = :pageId
               AND b.content ->> 'attachmentId' = :attachmentId
            """)
    long countBlocksReferencing(
            @Param("companyId") UUID companyId,
            @Param("pageId") UUID pageId,
            @Param("attachmentId") String attachmentId);
}
