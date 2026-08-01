package com.tcms.documentation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

// A repository interface must be @Transactional or its QUERY METHODS run with no transaction at
// all — and therefore with no app.current_company, which under RLS means zero rows. See
// ProjectRepository's header and RepositoryTransactionTest, which fails the build on a repository
// missing this.
@Transactional(readOnly = true)
public interface DocPageRepository extends JpaRepository<DocPage, UUID> {

    /**
     * One project's live pages, parents before children.
     *
     * <p>Ordered by {@code path} for {@code SuiteRepository#findLiveForProject}'s reason: an
     * {@code ltree} compares element-wise, so path order is depth-first with every parent ahead of
     * its descendants, which is what a consumer building a tree in one pass needs. The SPA sorts
     * siblings by title afterwards; this ordering is about being constructible.
     *
     * <p>Every status is returned, {@code ARCHIVED} included. Filtering them out here would make an
     * archived page unreachable from the only screen that could un-archive it, and archiving is
     * explicitly not deleting.
     */
    @Query("""
            SELECT p FROM DocPage p
             WHERE p.projectId = :projectId
               AND p.deletedAt IS NULL
             ORDER BY p.path ASC
            """)
    List<DocPage> findLiveForProject(@Param("projectId") UUID projectId);

    /**
     * One live page of one project, by id — the read every write gates on.
     *
     * <p>Both predicates matter. {@code projectId} is what makes
     * {@code /projects/{a}/doc-pages/{id}} refuse a page belonging to project {@code b} of the same
     * company. {@code deletedAt IS NULL} is what stops a second delete answering 204 as though it
     * had done something.
     *
     * <p>Not {@code findById}, which resolves by primary key through the persistence context — a
     * path Hibernate filters do not apply to.
     */
    @Query("""
            SELECT p FROM DocPage p
             WHERE p.id = :pageId
               AND p.projectId = :projectId
               AND p.deletedAt IS NULL
            """)
    Optional<DocPage> findLiveInProject(
            @Param("projectId") UUID projectId, @Param("pageId") UUID pageId);

    /**
     * How many live child pages this page has — direct children only.
     *
     * <p>Direct rather than subtree-wide for {@code SuiteRepository#countLiveChildren}'s reason: a
     * page with a grandchild necessarily has a child, so this refuses every non-empty subtree one
     * level at a time, and the count it reports is the one visible in the tree.
     */
    @Query("""
            SELECT COUNT(p) FROM DocPage p
             WHERE p.parentId = :pageId
               AND p.deletedAt IS NULL
            """)
    long countLiveChildren(@Param("pageId") UUID pageId);
}
