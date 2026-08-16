package com.tcms.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

// @Transactional for the reason spelled out on ProjectRepository: without it a query method runs
// outside any transaction, app.current_company is never set, and RLS returns zero rows.
@Transactional(readOnly = true)
public interface SharedStepRepository extends JpaRepository<SharedStep, UUID> {

    /**
     * One project's live step library, filtered by a title fragment.
     *
     * <h2>Ordered by title here, and re-ordered by usage in the service</h2>
     *
     * <p>{@link ConditionRepository#findLibrary} sorts by {@code usageCount DESC} in SQL because
     * the column is on the row. This one cannot: the count is derived by {@link #countUsage}, a
     * second query, so the ranking §6h specifies is applied after the two are zipped. The list is
     * one project's library — hundreds of rows, not the instance — so sorting it in memory is
     * cheaper than the correlated subquery that would let SQL do it, and it keeps the ordering
     * rule in one readable place rather than split across a query string and a comparator.
     *
     * <p>The SQL order is still not arbitrary: {@code title, id} makes the pre-ranking list
     * deterministic, so the final sort is stable and the same keystroke returns the same list
     * rather than merely usually returning it — the property {@code ConditionRepository} keeps its
     * {@code id} tie-breaker for.
     *
     * <h2>{@code LIKE} over the title, not {@code @@} over the search vector</h2>
     *
     * <p>{@code shared_step.search_vector} exists and is populated (V2), and it is the right
     * long-term corpus. HQL cannot express {@code @@} without a custom function, and this is a
     * dynamic query, so the operator is not available to it. ~~A native query would drop the
     * Hibernate company filter~~ → corrected in §235, which shipped the {@code @@} path for
     * documentation with {@code company_id} spelled out beside RLS; see
     * {@code CaseSpecifications#keyOrText}. So: title only, substring rather than stemmed.
     * ~~Both close when the {@code @@} path lands.~~ → it landed and this box stayed where it is,
     * for {@code ConditionRepository}'s reason — a typeahead wants prefixes, and stemming has none.
     *
     * @param needle always bound and never null — {@code '%'} when the caller asked nothing, which
     *               matches every row since {@code title} is {@code NOT NULL}. Resolved once by
     *               {@link SharedStepQuery} rather than re-decided at each call site.
     */
    /**
     * The entry a previous request with this key created, if any (B4, {@code V59}).
     *
     * <p>Project-scoped, naming exactly the columns the partial unique index covers — a narrower
     * lookup lets a key reused across two projects pass here and violate the index one statement
     * later. {@code SuiteRepository.findByIdempotencyKey} is the same method for the same reason,
     * including the absence of a liveness filter: a retry whose entry has since been retired is
     * still a retry.
     */
    @Query("""
            SELECT s FROM SharedStep s
             WHERE s.projectId = :projectId
               AND s.createdIdempotencyKey = :idempotencyKey
            """)
    Optional<SharedStep> findByIdempotencyKey(
            @Param("projectId") UUID projectId,
            @Param("idempotencyKey") String idempotencyKey);

    @Query("""
            SELECT s FROM SharedStep s
             WHERE s.projectId = :projectId
               AND s.deletedAt IS NULL
               AND LOWER(s.title) LIKE :needle ESCAPE '\\'
             ORDER BY s.title ASC, s.id ASC
            """)
    List<SharedStep> findLibrary(
            @Param("projectId") UUID projectId, @Param("needle") String needle);

    /**
     * How many distinct live cases reference each of the given entries.
     *
     * <p><strong>Entries with no references are absent from the result, not zero.</strong> A
     * {@code GROUP BY} over the referencing table cannot emit a row for something nothing
     * references; the caller defaults the missing ones. Stated because the alternative reading —
     * "every id comes back" — produces a lookup that silently drops the never-used entries, which
     * are exactly the rows a freshly seeded library consists of.
     *
     * <p>{@code c.deletedAt IS NULL} is what makes the number mean "in use". Cases are soft-deleted
     * (they are referenced by executions), so without it a library entry keeps the usage of every
     * case ever retired and the picker ranks by history instead of by current practice.
     *
     * @param sharedStepIds must be non-empty — an empty collection binds into SQL {@code IN ()},
     *                      which is a syntax error rather than an empty result. The caller returns
     *                      early instead.
     */
    @Query("""
            SELECT new com.tcms.repository.SharedStepUsage(t.sharedStepId, COUNT(DISTINCT t.caseId))
              FROM TestStep t, TestCase c
             WHERE t.caseId = c.id
               AND c.deletedAt IS NULL
               AND t.sharedStepId IN :sharedStepIds
             GROUP BY t.sharedStepId
            """)
    List<SharedStepUsage> countUsage(@Param("sharedStepIds") Collection<UUID> sharedStepIds);

    /**
     * Which of the given ids are live entries <strong>of this project</strong>.
     *
     * <p>The {@code projectId} predicate is the whole point, on
     * {@link ConditionRepository#findLiveInProject}'s terms: this resolves ids a caller
     * <em>sent</em>, which proves nothing about reachability. Without it, a case save could staple
     * a sibling project's library entry onto a step — the foreign key is satisfied, so nothing
     * fails — and every subsequent read of that case would resolve a library entry through a
     * project the reader holds no grant on.
     *
     * <p>Returns ids rather than entities because the only question asked of it is membership, and
     * a caller holding entities is a caller that will eventually render one it did not fetch for
     * that purpose.
     *
     * @param sharedStepIds must be non-empty — see {@link #countUsage}.
     */
    @Query("""
            SELECT s.id FROM SharedStep s
             WHERE s.projectId = :projectId
               AND s.deletedAt IS NULL
               AND s.id IN :sharedStepIds
            """)
    List<UUID> findLiveIdsInProject(
            @Param("projectId") UUID projectId,
            @Param("sharedStepIds") Collection<UUID> sharedStepIds);
}
