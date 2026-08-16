package com.tcms.documentation;

import java.util.Collection;
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
     * <p>Direct rather than subtree-wide, and the reason was {@code SuiteRepository}'s until §98
     * flattened suites and took that method with it — so it is spelled out here now: a
     * page with a grandchild necessarily has a child, so this refuses every non-empty subtree one
     * level at a time, and the count it reports is the one visible in the tree.
     */
    @Query("""
            SELECT COUNT(p) FROM DocPage p
             WHERE p.parentId = :pageId
               AND p.deletedAt IS NULL
            """)
    long countLiveChildren(@Param("pageId") UUID pageId);

    /**
     * The live page documenting one artifact — see {@link DocSubject} and {@code V29}.
     *
     * <p>Scoped by project as well as by subject, even though {@code uq_doc_page_subject} already
     * makes at most one row match within the company. The index is the invariant; this predicate is
     * what makes the <em>endpoint</em> honest, because the caller reached here through a project's
     * URL and a subject id from a sibling project must not resolve.
     *
     * <p>{@code deletedAt IS NULL} matches the unique index's own {@code WHERE}, and the two have to
     * agree: an index that permitted a second live row would make this {@code Optional} a lie, and a
     * read that returned deleted rows would find the document the index had already released.
     */
    @Query("""
            SELECT p FROM DocPage p
             WHERE p.projectId = :projectId
               AND p.subjectType = :subjectType
               AND p.subjectId = :subjectId
               AND p.deletedAt IS NULL
            """)
    Optional<DocPage> findLiveForSubject(
            @Param("projectId") UUID projectId,
            @Param("subjectType") DocSubjectType subjectType,
            @Param("subjectId") UUID subjectId);

    /**
     * The pages matching a full-text query, best first, as {@code [pageId, snippet]}.
     *
     * <p><strong>The first and only reader of a {@code search_vector} in this product.</strong> Four
     * tables carry one, {@code V2} maintains all four, and until §235 nothing queried any of them —
     * while this module's own {@code PlanDocumentPanel} justified its design on pages being "found
     * by everybody who searches documentation".
     *
     * <h2>Native, and why that is not the weaker boundary it looks like</h2>
     *
     * <p>{@code CaseSpecifications#keyOrText} declines the same operator and gives two reasons.
     * The first is that Criteria cannot express {@code @@} without a registered function, so this
     * has to be native — "which drops the Hibernate company filter and leaves RLS as the only
     * boundary, the single-point-of-defence trade {@code ProjectRepository}'s header refuses".
     * <strong>That header refuses no such thing.</strong> It writes a native query and spells the
     * predicate out, and says why: RLS is the guarantee, the predicate is the intent, and the two
     * agreeing is what makes a mismatch return nothing rather than something. {@link
     * DocBlockRepository} does the same twice in this module. So does this: {@code company_id} is
     * bound from {@link com.tcms.tenancy.CompanyContext}, never from request input, and the project
     * and the soft-delete bound are spelled out beside it — three application predicates over RLS,
     * which is more boundary than a filtered Criteria query has, not less.
     *
     * <p>A Hibernate {@code FunctionContributor} would have kept this in Criteria and was rejected:
     * it is a dialect-wide seam registered to serve one query with no dynamic predicates, which is
     * the thing Criteria exists for and this does not need.
     *
     * <h2>Lexemes, which is the other half of that refusal and does not transfer</h2>
     *
     * <p>The case list's second reason is that full text matches lexemes, so a half-typed word finds
     * nothing — disqualifying for a type-ahead over titles fired on every keystroke. It is still
     * true and it is not a defect here: this corpus is prose, the interaction is type-then-submit,
     * and stemming is the behaviour a reader looking for a concept wants. It is also the only
     * indexed path that exists — block text lives in JSONB with no trigram index behind it, so the
     * {@code LIKE} alternative is an unindexed scan over the very thing that makes the feature worth
     * having.
     *
     * <h2>Only ids come back</h2>
     *
     * <p>The summary is built from the entity by {@link DocPageSummaryView#of}, the same code the
     * page tree renders, so a row in the search results and a row in the sidebar cannot disagree
     * about a title or a status. Selecting the columns here would be a second assembly of one view.
     * It also puts the entity half of the read back under the Hibernate filter.
     *
     * <p>{@code ts_headline} is given the same text the vector was built from, reassembled from the
     * blocks — so the fragment shown always contains the match that produced it. It returns
     * {@code null} for a page with no blocks, which is the wire's null snippet, and it emits no
     * markup: see the contract's {@code DocPageSearchHit.snippet}.
     *
     * <p>A {@code q} that parses to an empty tsquery matches nothing, so a blank search needs no
     * guard in front of it and answers an empty list by the ordinary path.
     */
    @Query(nativeQuery = true, value = """
            SELECT p.id,
                   ts_headline('english'::regconfig,
                               body.text,
                               websearch_to_tsquery('english'::regconfig, :q),
                               'MaxFragments=2, MaxWords=24, MinWords=10, StartSel="", StopSel=""')
              FROM doc_page p
              LEFT JOIN LATERAL (
                   SELECT string_agg(v.value #>> '{}', ' ' ORDER BY b.position, v.ordinality) AS text
                     FROM doc_block b
                     CROSS JOIN LATERAL jsonb_path_query(b.content, '$.**')
                          WITH ORDINALITY AS v(value, ordinality)
                    WHERE b.page_id = p.id
                      AND jsonb_typeof(v.value) = 'string'
              ) body ON TRUE
             WHERE p.company_id = :companyId
               AND p.project_id = :projectId
               AND p.deleted_at IS NULL
               AND p.search_vector @@ websearch_to_tsquery('english'::regconfig, :q)
             ORDER BY ts_rank_cd(p.search_vector,
                                 websearch_to_tsquery('english'::regconfig, :q)) DESC,
                      p.updated_at DESC,
                      p.id
             LIMIT :limit
            """)
    List<Object[]> search(
            @Param("companyId") UUID companyId,
            @Param("projectId") UUID projectId,
            @Param("q") String q,
            @Param("limit") int limit);

    /**
     * The live pages of one project named by id — the entity half of {@link #search}.
     *
     * <p>Unordered on purpose: the ranking is the native query's and re-stating it here in a
     * language that cannot express {@code ts_rank_cd} would be a second, silently different answer.
     * The caller re-imposes the order it was given.
     */
    @Query("""
            SELECT p FROM DocPage p
             WHERE p.projectId = :projectId
               AND p.id IN :pageIds
               AND p.deletedAt IS NULL
            """)
    List<DocPage> findLiveInProjectByIds(
            @Param("projectId") UUID projectId, @Param("pageIds") Collection<UUID> pageIds);

    /**
     * The page a previous request with this {@code Idempotency-Key} already created, if any.
     *
     * <p>Scoped to the project because {@code doc_page_created_idempotency_key_idx} is, and
     * unfiltered by {@code deletedAt} because a retry whose page has since been deleted is still a
     * retry — {@code SuiteRepository#findByIdempotencyKey} states both at length and the reasons
     * are the same ones.
     *
     * <p>It cannot match a page created on a plan's behalf: that path passes no key, which is why
     * {@code uq_doc_page_subject} rather than this index is what dedupes it.
     */
    @Query("""
            SELECT p FROM DocPage p
             WHERE p.projectId = :projectId
               AND p.createdIdempotencyKey = :idempotencyKey
            """)
    Optional<DocPage> findByIdempotencyKey(
            @Param("projectId") UUID projectId, @Param("idempotencyKey") String idempotencyKey);
}
