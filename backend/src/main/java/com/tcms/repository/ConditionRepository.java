package com.tcms.repository;

import java.util.Collection;
import java.util.List;
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
     * <h2>Most-used first</h2>
     *
     * <p>The point of a reusable-condition library is to surface the precondition four hundred
     * cases already share, not the one written yesterday — so {@code usageCount} descending, which
     * is what the SPA's mock does and what §6h specifies. {@code usage_count} reads 0 on every row
     * until something maintains it, which makes today's effective order the title; {@code id}
     * breaks the remaining ties so the list is stable between keystrokes rather than merely
     * usually stable.
     *
     * <p><strong>The {@code id} tie-breaker survives mutation, and structurally so</strong> — the
     * remaining tie needs two entries with the same usage count <em>and</em> the same title in one
     * project, and Postgres returns a small table in physical order, so a fixture cannot make the
     * unstable query answer differently on two consecutive reads. It stays because the property it
     * buys — the same list for the same keystroke — is not one the planner owes us once the table
     * is large enough to be scanned in parallel, and by then nothing about the endpoint will have
     * changed to make anyone look.
     */
    @Query("""
            SELECT c FROM Condition c
             WHERE c.projectId = :projectId
               AND c.deletedAt IS NULL
               AND c.type IN :types
               AND LOWER(c.title) LIKE :needle ESCAPE '\\'
             ORDER BY c.usageCount DESC, c.title ASC, c.id ASC
            """)
    List<Condition> findLibrary(
            @Param("projectId") UUID projectId,
            @Param("types") Collection<ConditionType> types,
            @Param("needle") String needle);
}
