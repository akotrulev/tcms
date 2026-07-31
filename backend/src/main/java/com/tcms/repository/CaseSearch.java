package com.tcms.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The three shapes of read this endpoint needs — a page, its total, and the facet tallies — all
 * over one shared predicate.
 *
 * <h2>Why this is Criteria against the {@code EntityManager} and not a Spring Data repository</h2>
 *
 * <p>Every other repository in this codebase is a Spring Data interface, and that is still the
 * default. This one is not, for reasons the interface cannot express:
 *
 * <ul>
 *   <li><strong>The predicate is assembled from up to eleven optional filters</strong>, three of
 *       them {@code EXISTS} subqueries. A {@code @Query} would have to be one string with a
 *       null-guard per filter, which produces a plan the planner cannot specialize and a query
 *       nobody can read.</li>
 *   <li><strong>{@code ORDER BY} includes a scalar subquery</strong> (the suite name) and CASE
 *       expressions over enum declaration order. {@code Pageable}'s {@code Sort} can name
 *       properties and nothing else, so neither is reachable through
 *       {@code findAll(spec, pageable)}.</li>
 *   <li><strong>The facets are grouped aggregates over the same predicate.</strong> Spring Data has
 *       no GROUP BY surface at all.</li>
 * </ul>
 *
 * <p>The skill's rule is "roll your own only when the named-query surface deserves a type"; this is
 * that case. What it does <em>not</em> get to skip is the transaction: Criteria through the
 * {@code EntityManager} needs one exactly as much as a repository method does, because
 * {@code app.current_company} is set in {@code doBegin} and without it RLS returns zero rows
 * (handoff §15). {@link CaseService} owns that boundary, and every method here is called from
 * inside it.
 *
 * <p>The Hibernate company filter is enabled on the same session, and it <em>does</em> apply to
 * Criteria queries — which is the reason this is Criteria rather than the native SQL that would
 * have been shorter. A native query keeps RLS and loses the filter, and one layer where two were
 * available is how a layered defence quietly becomes a single point.
 */
@Component
public class CaseSearch {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * One page of cases, ordered and filtered.
     *
     * <p>Entities rather than a DTO projection, deliberately. The alternative — selecting the
     * fourteen scalar columns into a constructor expression — would save loading three unmapped
     * ones and would then need a second hand-maintained list of fields in step with
     * {@link TestCaseSummaryView}. At a page size capped at 200 the saving is not measurable and
     * the drift is permanent.
     */
    List<TestCase> page(CaseQuery request, CaseScope scope) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<TestCase> query = cb.createQuery(TestCase.class);
        Root<TestCase> root = query.from(TestCase.class);

        query.select(root)
                .where(CaseSpecifications.where(request, scope, root, query, cb))
                .orderBy(CaseSort.orderBy(request.sort(), root, query, cb));

        return entityManager.createQuery(query)
                .setFirstResult(request.page() * request.size())
                .setMaxResults(request.size())
                .getResultList();
    }

    /**
     * How many cases match, ignoring paging.
     *
     * <p>A separate query rather than a count over the fetched list, because the fetched list is
     * one page: a total derived from it would say 50 whatever the truth was, and the pager would
     * offer exactly one page of any result set. The two queries share
     * {@link CaseSpecifications#where} so they cannot disagree about what "matching" means.
     */
    long total(CaseQuery request, CaseScope scope) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<TestCase> root = query.from(TestCase.class);

        query.select(cb.count(root))
                .where(CaseSpecifications.where(request, scope, root, query, cb));

        return entityManager.createQuery(query).getSingleResult();
    }

    /**
     * The count of matching cases per value of one enum column — one grouped query.
     *
     * <h2>Facets count the filtered set, including this filter's own contribution</h2>
     *
     * <p>Filtering by {@code lifecycleStatus=DRAFT} leaves the lifecycle facet showing DRAFT alone.
     * The other convention — each facet computed with its own filter dropped, so the counts show
     * what selecting a different value <em>would</em> give — is more useful and is four more
     * queries, one per facet. This matches the SPA's mock, which is the shape the UI was built and
     * driven against; changing the semantics is a product decision with a UI consequence, not a
     * detail to slip into the first implementation.
     *
     * <p>Values with no matching case are absent rather than present with zero — a {@code GROUP BY}
     * cannot invent rows for groups that do not exist. The SPA renders a facet value's count beside
     * a chip it already knows about, so absent reads as zero there; it is not treated as "unknown".
     */
    <E extends Enum<E>> Map<E, Long> tally(
            CaseQuery request, CaseScope scope, String attribute, Class<E> type) {

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<TestCase> root = query.from(TestCase.class);
        Path<E> column = root.get(attribute);

        query.multiselect(column, cb.count(root))
                .where(CaseSpecifications.where(request, scope, root, query, cb))
                .groupBy(column);

        Map<E, Long> counts = new LinkedHashMap<>();
        for (Tuple row : entityManager.createQuery(query).getResultList()) {
            counts.put(type.cast(row.get(0)), (Long) row.get(1));
        }
        return counts;
    }

    /**
     * The count of matching cases raising each health signal — one query for all nine.
     *
     * <h2>Nine aggregates rather than nine queries, and a subquery rather than a join</h2>
     *
     * <p>{@code case_health} is one row per case with a boolean per signal, so a single pass over
     * the matching rows can tally every signal at once:
     * {@code sum(case when stale_approval then 1 else 0)} nine times over. The alternative — one
     * counted query per signal — is nine round trips for one column of the grid.
     *
     * <p>The matching set arrives as a subquery over {@code TestCase} carrying the same shared
     * predicate, rather than as a join: {@link TestCase} maps no association to this table (see its
     * header), and materializing the matching ids in Java to pass them back down would mean binding
     * every id in the project into an {@code IN} list to render one column.
     *
     * <p><strong>Every count is zero today</strong> and the query still runs. Nothing writes
     * {@code case_health} yet, so the honest tally is nine zeros — which the caller turns into an
     * empty facet value list rather than nine zero-valued chips, because a chip promising "0" is a
     * filter the user can select and get nothing from.
     */
    Map<HealthSignal, Long> tallyHealth(CaseQuery request, CaseScope scope) {

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<CaseHealth> health = query.from(CaseHealth.class);

        Subquery<UUID> matching = query.subquery(UUID.class);
        Root<TestCase> cases = matching.from(TestCase.class);
        matching.select(cases.get("id"))
                .where(CaseSpecifications.where(request, scope, cases, matching, cb));

        List<Selection<?>> tallies = new ArrayList<>();
        for (HealthSignal signal : HealthSignal.values()) {
            tallies.add(cb.sum(cb.<Long>selectCase()
                    .when(cb.isTrue(health.get(signal.attribute())), 1L)
                    .otherwise(0L)));
        }

        query.multiselect(tallies).where(health.get("caseId").in(matching));

        Tuple row = entityManager.createQuery(query).getSingleResult();
        Map<HealthSignal, Long> counts = new LinkedHashMap<>();
        HealthSignal[] signals = HealthSignal.values();
        for (int i = 0; i < signals.length; i++) {
            Long tally = (Long) row.get(i);
            // sum() over no rows is NULL, which is the state of every request until something
            // computes health. Read as zero rather than propagated, so the caller never has to
            // distinguish "no matching cases" from "no signal raised" — the facet is empty either
            // way and for the same reason.
            counts.put(signals[i], tally == null ? 0L : tally);
        }
        return counts;
    }
}
