package com.tcms.repository;

import com.tcms.project.StatusKind;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The dashboard's own aggregates — the four {@link CaseSearch} cannot express.
 *
 * <h2>Why this is a second class rather than four more methods on {@link CaseSearch}</h2>
 *
 * <p>{@link CaseSearch} is the case <em>list</em>'s three shapes of read, and its header is an
 * argument about paging, sorting and facets over one filtered request. These are whole-project
 * rollups grouped by things the list never groups by. Both use {@link CaseSpecifications}, and that
 * shared predicate — not shared residence — is what keeps a tile's count equal to the list it opens.
 *
 * <p>Everything here obeys the same two constraints the rest of the module does: <strong>Criteria
 * rather than native SQL</strong>, so the Hibernate company filter applies on top of RLS rather than
 * RLS becoming the only boundary; and <strong>no query per row</strong> — each method below is one
 * round trip whatever the project's size.
 */
@Component
class DashboardTallies {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Case counts grouped by owner and authoring status — the by-owner stacked bars.
     *
     * <p>One query for the whole grid rather than one per person, and it returns ids: resolving
     * names is {@link CaseOwners}' job and happens once for the whole page.
     *
     * <p><strong>Cases with no owner are excluded</strong>, and the {@code IS NOT NULL} is doing
     * that deliberately rather than as a null-guard. Grouping by a null owner produces a bar for a
     * person who does not exist, and the segment's own filter — {@code ownerId=<something>} — has no
     * value that could reproduce it, so the bar would be the one row on the panel whose link cannot
     * work. The count is not lost, it is simply not a row here: the lifecycle tiles above already
     * count every case in the project, owned or not.
     */
    Map<UUID, Map<LifecycleStatus, Long>> byOwner(CaseQuery request, CaseScope scope) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<TestCase> root = query.from(TestCase.class);

        Path<UUID> owner = root.get("ownerId");
        Path<LifecycleStatus> status = root.get("lifecycleStatus");

        query.multiselect(owner, status, cb.count(root))
                .where(cb.and(
                        CaseSpecifications.where(request, scope, root, query, cb),
                        cb.isNotNull(owner)))
                .groupBy(owner, status);

        Map<UUID, Map<LifecycleStatus, Long>> counts = new LinkedHashMap<>();
        for (Tuple row : entityManager.createQuery(query).getResultList()) {
            counts.computeIfAbsent((UUID) row.get(0), id -> new LinkedHashMap<>())
                    .put((LifecycleStatus) row.get(1), (Long) row.get(2));
        }
        return counts;
    }

    /**
     * Case counts grouped by classification value and authoring status — the by-feature bars.
     *
     * <p>Joined through {@code case_classification} rather than read off the case, because Feature is
     * the one MULTI-cardinality dimension (plan §6d): a case carrying three features contributes to
     * three bars, which is what the panel is for. That means <strong>the bars deliberately sum to
     * more than the project's case count</strong>, and the panel is a breakdown by feature rather
     * than a partition of the repository.
     *
     * <p>Restricted to the value ids the caller passes — the live Feature vocabulary, resolved once
     * by {@link DashboardService}. Without that bound this would also group by Test Type and Test
     * Level, since one join table holds every dimension, and the panel would silently mix three
     * vocabularies into one list of bars.
     */
    Map<UUID, Map<LifecycleStatus, Long>> byClassification(
            CaseQuery request, CaseScope scope, List<UUID> valueIds) {

        if (valueIds.isEmpty()) {
            // An empty collection bound into SQL `IN ()` is a syntax error, and a project whose
            // Feature vocabulary has been emptied is an ordinary state rather than an edge one.
            return Map.of();
        }

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<CaseClassification> classification = query.from(CaseClassification.class);

        Subquery<UUID> matching = query.subquery(UUID.class);
        Root<TestCase> cases = matching.from(TestCase.class);
        matching.select(cases.get("id"))
                .where(CaseSpecifications.where(request, scope, cases, matching, cb));

        // The status has to come from the case, and the row being grouped is the join row — so the
        // case is joined back in explicitly. A correlated scalar subquery per row would be the same
        // information at one query per classification row.
        Root<TestCase> owning = query.from(TestCase.class);
        Path<LifecycleStatus> status = owning.get("lifecycleStatus");

        query.multiselect(classification.get("valueId"), status, cb.count(classification))
                .where(cb.and(
                        cb.equal(owning.get("id"), classification.get("caseId")),
                        classification.get("caseId").in(matching),
                        classification.get("valueId").in(valueIds)))
                .groupBy(classification.get("valueId"), status);

        Map<UUID, Map<LifecycleStatus, Long>> counts = new LinkedHashMap<>();
        for (Tuple row : entityManager.createQuery(query).getResultList()) {
            counts.computeIfAbsent((UUID) row.get(0), id -> new LinkedHashMap<>())
                    .put((LifecycleStatus) row.get(1), (Long) row.get(2));
        }
        return counts;
    }

    /**
     * Case counts grouped by classification value and <strong>the outcome of the case's last
     * execution</strong> — the by-last-run bars (005 N3).
     *
     * <h2>A different segmentation, not a different panel</h2>
     *
     * <p>{@link #byClassification} splits a Feature's cases by where they are in <em>authoring</em>;
     * this splits the same cases by what happened when they were last <em>run</em>. Both are needed
     * and neither substitutes: a feature can be fully approved and entirely failing.
     *
     * <p>Read from {@code case_health}, which is the same row {@code TestCaseSummary.lastResult}
     * is served from, so a bar and the grid it opens cannot disagree about a case's last result. It
     * is also why this is a stored-column read rather than a join into {@code execution}: the module
     * boundary that made {@code V45} a read model has not moved.
     *
     * <h2>Two queries, because the absent row is the interesting segment</h2>
     *
     * <p>A case that has never been executed has {@code last_status_kind} null — or no
     * {@code case_health} row at all, which is the state of every case in a project the recompute
     * has not reached. Both are the same fact and both must land in the {@code NEVER_RUN} segment,
     * and neither is reachable by grouping on a column that is not there. So the kinds are counted
     * by an inner join and the <em>total</em> per value is counted separately; the caller subtracts.
     *
     * <p>Deriving it the other way — an outer join with a {@code COALESCE} — is one query and it
     * needs {@code case_health} joined as an unrelated entity through Criteria, which builds an
     * inner join and silently drops exactly the cases this segment is about. The subtraction is the
     * arrangement in which the absent row cannot go missing.
     *
     * @return value id → kind → count, holding <strong>only</strong> the cases with a recorded last
     *         result. {@link #totalsByClassification} answers the denominator.
     */
    Map<UUID, Map<String, Long>> byClassificationAndLastRun(
            CaseQuery request, CaseScope scope, List<UUID> valueIds) {

        if (valueIds.isEmpty()) {
            return Map.of();
        }

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<CaseClassification> classification = query.from(CaseClassification.class);

        Subquery<UUID> matching = query.subquery(UUID.class);
        Root<TestCase> cases = matching.from(TestCase.class);
        matching.select(cases.get("id"))
                .where(CaseSpecifications.where(request, scope, cases, matching, cb));

        Root<CaseHealth> health = query.from(CaseHealth.class);
        Path<StatusKind> kind = health.get("lastStatusKind");

        query.multiselect(classification.get("valueId"), kind, cb.count(classification))
                .where(cb.and(
                        cb.equal(health.get("caseId"), classification.get("caseId")),
                        classification.get("caseId").in(matching),
                        classification.get("valueId").in(valueIds),
                        // The predicate that makes this an inner join over RECORDED results only. A
                        // row exists for every case the recompute has seen, executed or not.
                        cb.isNotNull(kind)))
                .groupBy(classification.get("valueId"), kind);

        Map<UUID, Map<String, Long>> counts = new LinkedHashMap<>();
        for (Tuple row : entityManager.createQuery(query).getResultList()) {
            counts.computeIfAbsent((UUID) row.get(0), id -> new LinkedHashMap<>())
                    .put(((StatusKind) row.get(1)).name(), (Long) row.get(2));
        }
        return counts;
    }

    /**
     * How many cases each classification value holds, over the same predicate — the denominator
     * {@link #byClassificationAndLastRun} needs and deliberately does not compute itself.
     *
     * <p>Kept separate rather than derived by summing {@link #byClassification}, which would give
     * the same number today and stop doing so the first time a case has a lifecycle status the
     * other query filters. One predicate, two groupings, no arithmetic between them.
     */
    Map<UUID, Long> totalsByClassification(
            CaseQuery request, CaseScope scope, List<UUID> valueIds) {

        if (valueIds.isEmpty()) {
            return Map.of();
        }

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<CaseClassification> classification = query.from(CaseClassification.class);

        Subquery<UUID> matching = query.subquery(UUID.class);
        Root<TestCase> cases = matching.from(TestCase.class);
        matching.select(cases.get("id"))
                .where(CaseSpecifications.where(request, scope, cases, matching, cb));

        query.multiselect(classification.get("valueId"), cb.count(classification))
                .where(cb.and(
                        classification.get("caseId").in(matching),
                        classification.get("valueId").in(valueIds)))
                .groupBy(classification.get("valueId"));

        Map<UUID, Long> totals = new LinkedHashMap<>();
        for (Tuple row : entityManager.createQuery(query).getResultList()) {
            totals.put((UUID) row.get(0), (Long) row.get(1));
        }
        return totals;
    }

    /**
     * When each matching case was created, for cases created since {@code since}.
     *
     * <h2>Bucketed into days in Java, not by {@code date_trunc} in the database</h2>
     *
     * <p>The obvious implementation groups by {@code date_trunc('day', created_at)} and returns
     * thirty rows. Reaching it through Criteria means {@code cb.function} with a hand-declared return
     * type, and getting the day boundaries right there means pushing the time zone into SQL too —
     * where a mistake is a chart silently shifted by a day and nothing that fails. Returning the
     * instants and bucketing them where the calendar rules already live is the same answer with the
     * arithmetic in one readable place.
     *
     * <p>The cost is bounded and stated: this returns one row per case <em>created in the last
     * thirty days</em> in one project, not one per case. A project authoring a hundred cases a day
     * for a month returns three thousand timestamps. If that ever stops being cheap, the fix is the
     * {@code date_trunc} grouping — with this method as the definition of the answer it must produce.
     *
     * <p><strong>Deleting the {@code since} predicate is a mutation that SURVIVES the suite, and it
     * survives structurally rather than for want of a test.</strong>
     * {@link DashboardService#activity} builds its buckets by <em>looking up</em> each of the thirty
     * days it intends to plot, so a timestamp outside the window lands on a key nothing ever reads.
     * The bound is therefore a cost control and not a correctness one — it stops a five-year-old
     * project shipping every case's creation date over the wire to have it discarded in Java. Stated
     * here rather than left as a predicate a reader would assume was load-bearing;
     * {@link CaseSpecifications#where}'s {@code projectId} line documents the same family.
     */
    List<Instant> creationTimes(CaseQuery request, CaseScope scope, Instant since) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Instant> query = cb.createQuery(Instant.class);
        Root<TestCase> root = query.from(TestCase.class);

        query.select(root.get("createdAt"))
                .where(cb.and(
                        CaseSpecifications.where(request, scope, root, query, cb),
                        cb.greaterThanOrEqualTo(root.get("createdAt"), since)));

        return entityManager.createQuery(query).getResultList();
    }

    /**
     * When each edit landed, for edits to matching cases since {@code since}.
     *
     * <p><strong>Version 1 is excluded, and that is the whole correctness of the series.</strong>
     * Since §29 a created case is written with its v1 in the same transaction, so counting every
     * version row would report every creation as a modification too — the "modified" line would
     * shadow the "created" line exactly, on every day nobody edited anything, and it would look like
     * data rather than like double counting.
     *
     * <p>Cases the caller cannot see are excluded through the same shared predicate as everything
     * else, so a soft-deleted case's edits leave the chart with it.
     */
    List<Instant> editTimes(CaseQuery request, CaseScope scope, Instant since) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Instant> query = cb.createQuery(Instant.class);
        Root<TestCaseVersion> root = query.from(TestCaseVersion.class);

        Subquery<UUID> matching = query.subquery(UUID.class);
        Root<TestCase> cases = matching.from(TestCase.class);
        matching.select(cases.get("id"))
                .where(CaseSpecifications.where(request, scope, cases, matching, cb));

        List<Predicate> all = new ArrayList<>();
        all.add(root.get("caseId").in(matching));
        all.add(cb.greaterThanOrEqualTo(root.get("createdAt"), since));
        all.add(cb.greaterThan(root.get("versionNo"), 1));

        query.select(root.get("createdAt")).where(cb.and(all.toArray(Predicate[]::new)));

        return entityManager.createQuery(query).getResultList();
    }
}
