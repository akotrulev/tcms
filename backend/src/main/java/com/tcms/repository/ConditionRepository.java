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
public interface ConditionRepository extends JpaRepository<Condition, UUID> {

    /**
     * The library entries behind a set of attachments, live only.
     *
     * <p>{@code deletedAt IS NULL} matters more here than it looks. Retiring a library condition
     * is a soft delete because attachments to it survive, so a retired entry still has rows
     * pointing at it — and serving it would offer a withdrawn precondition as current. An
     * attachment whose condition has been retired therefore resolves to nothing and drops out of
     * the response; {@link SuiteConditionView} records that as the deliberate half of a real gap
     * rather than as a lookup that quietly failed.
     */
    @Query("""
            SELECT c FROM Condition c
             WHERE c.id IN :ids
               AND c.deletedAt IS NULL
            """)
    List<Condition> findLiveByIds(@Param("ids") Collection<UUID> ids);

    /**
     * One project's live condition library, filtered by role and by a title fragment.
     *
     * <h2>Both parameters are always bound, and neither is ever null</h2>
     *
     * <p>{@code types} is the resolved set from {@link ConditionType#matching} — three values when
     * the caller expressed no opinion, never an empty collection, because an empty one binds into
     * SQL {@code IN ()} and is a syntax error. {@code needle} is {@code '%'} when there is no
     * query, which matches every row since {@code title} is {@code NOT NULL}. The alternative —
     * {@code (:needle IS NULL OR …)} — makes the planner carry a branch it can never fold and
     * leaves the null reading to be re-decided at every call site; {@link ConditionQuery} decides
     * it once instead.
     *
     * <h2>{@code LIKE} over the title, not {@code @@} over the search vector</h2>
     *
     * <p>{@code condition.search_vector} exists and is populated ({@code V2}, title weighted A and
     * the body B), and it is the right long-term corpus for §6h's typeahead. HQL cannot express
     * {@code @@} without a custom function, and a native query would drop the Hibernate company
     * filter — the same constraint, and the same choice, as the case list's free-text search
     * (§25). The row set is bounded to one project's library, so the sequential scan is over
     * hundreds of rows rather than the instance.
     *
     * <p>Consequences of that choice, both real and both deliberate: the search is over the
     * <strong>title only</strong> (matching the SPA's mock, which the picker was built against),
     * and it is a substring match rather than a stemmed one — "logging" does not find "log in".
     * Both close when the {@code @@} path lands.
     *
     * <h2>The ranking MOVED to the service in §95</h2>
     *
     * <p>This used to {@code ORDER BY c.usageCount DESC} over the stored column. §95 unmapped that
     * column — it read {@code 0} on every row for ever, and the slice that gives this library an
     * impact warning cannot compute one from a permanent zero (see {@link Condition}) — so the
     * counts are now derived from the referencing tables and the sort happens in
     * {@link ConditionService#rank}, exactly as {@link SharedStepService#rank} does.
     *
     * <p>The order here is therefore the <em>tie-break</em> order only, and it still matters: the
     * service's comparator ends in the same title-then-id fallback, so a stable order coming out of
     * the database keeps the same keystroke returning the same list rather than merely usually
     * doing so.
     */
    @Query("""
            SELECT c FROM Condition c
             WHERE c.projectId = :projectId
               AND c.deletedAt IS NULL
               AND c.type IN :types
               AND LOWER(c.title) LIKE :needle ESCAPE '\\'
             ORDER BY c.title ASC, c.id ASC
            """)
    List<Condition> findLibrary(
            @Param("projectId") UUID projectId,
            @Param("types") Collection<ConditionType> types,
            @Param("needle") String needle);

    /**
     * One live library entry, <strong>proved to be this project's</strong>.
     *
     * <p>The {@code projectId} predicate is the point, and {@link #findLiveByIds} deliberately does
     * not carry it: that method resolves conditions already attached to cases the caller can read,
     * so the attachment itself is the proof of reachability. This one resolves an id the caller
     * <em>sent</em>, which proves nothing — without the predicate, a bulk attach could staple a
     * sibling project's condition onto 500 cases, and every read of them would then resolve a
     * condition through a project the reader was never granted.
     */
    @Query("""
            SELECT c FROM Condition c
             WHERE c.id = :conditionId
               AND c.projectId = :projectId
               AND c.deletedAt IS NULL
            """)
    Optional<Condition> findLiveInProject(
            @Param("projectId") UUID projectId, @Param("conditionId") UUID conditionId);

    /**
     * How many distinct <strong>live cases</strong> attach each of the given entries directly.
     *
     * <p>{@link SharedStepRepository#countUsage}'s shape and its three rules, which transfer intact:
     * entries nothing references are <strong>absent from the result, not zero</strong> (a
     * {@code GROUP BY} over the referencing table cannot emit a row for something nothing
     * references, so the caller defaults them); {@code c.deletedAt IS NULL} is what makes the number
     * mean "in use", since cases are soft-deleted and without it the library ranks by history; and
     * the collection must be non-empty, because an empty one binds into SQL {@code IN ()}, which is
     * a syntax error rather than an empty result.
     *
     * <p><strong>{@code COUNT(DISTINCT cc.caseId)}, not {@code COUNT(*)}.</strong> One case may
     * attach the same entry twice — legitimately, as both a pre- and a postcondition — and counting
     * attachment rows would report two cases where there is one. §94's step corpus records the same
     * trap from the other side, and both numbers look plausible on sight, which is why the fixture
     * makes them differ rather than trusting a reading of the query.
     *
     * @param conditionIds must be non-empty
     */
    @Query("""
            SELECT new com.tcms.repository.ConditionUsage(
                       cc.conditionId, COUNT(DISTINCT cc.caseId))
              FROM CaseCondition cc, TestCase c
             WHERE cc.caseId = c.id
               AND c.deletedAt IS NULL
               AND cc.conditionId IN :conditionIds
             GROUP BY cc.conditionId
            """)
    List<ConditionUsage> countCaseUsage(@Param("conditionIds") Collection<UUID> conditionIds);

    /**
     * How many <strong>live suites</strong> attach each of the given entries.
     *
     * <p>A second query and a second number, never folded into {@link #countCaseUsage}'s. A suite
     * attachment propagates <em>by inheritance</em> to every case beneath the suite — the walk
     * {@link CaseConditions} does over {@code suite.path} — so this figure is not a case count and
     * adding it to one would produce a number that is neither. The contract states the resolution on
     * {@code Condition.suiteUsageCount}: two honest numbers rather than one blended guess, because
     * the client renders this as a pre-edit impact warning and the failure mode of understating it
     * is an author changing three hundred cases believing they changed three.
     *
     * <p>Counting the <em>cases</em> a suite attachment reaches is deliberately not attempted here.
     * It needs the {@code ltree} subtree walk, which HQL cannot express; a native query would drop
     * the Hibernate company filter, which is the constraint every library read in this module
     * records. That is a real limitation and it is named in the contract rather than papered over
     * with a number that happens to be computable.
     *
     * <p>{@code s.deletedAt IS NULL} for {@link #countCaseUsage}'s reason: a soft-deleted folder
     * stops inheriting to the cases still beneath it ({@link CaseConditions} says so at the walk),
     * so counting it would warn about propagation that no longer happens.
     *
     * @param conditionIds must be non-empty
     */
    @Query("""
            SELECT new com.tcms.repository.ConditionUsage(
                       sc.conditionId, COUNT(DISTINCT sc.suiteId))
              FROM SuiteCondition sc, Suite s
             WHERE sc.suiteId = s.id
               AND s.deletedAt IS NULL
               AND sc.conditionId IN :conditionIds
             GROUP BY sc.conditionId
            """)
    List<ConditionUsage> countSuiteUsage(@Param("conditionIds") Collection<UUID> conditionIds);
}
