package com.tcms.repository;

import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * <strong>What a suite holds</strong> — the one implementation of the union, and the reason there
 * must be exactly one.
 *
 * <p>Since {@code V31} a suite is a resolvable collection, and its members are three arms:
 *
 * <ol>
 *   <li>the cases <strong>filed</strong> in it — {@code test_case.suite_id}, which used to be all
 *       of it;</li>
 *   <li>the cases <strong>added</strong> to it individually — {@link SuiteCase};</li>
 *   <li>every live case carrying <strong>any</strong> of its tags — {@link SuiteTagRule} joined to
 *       {@code case_tag}.</li>
 * </ol>
 *
 * <p>Unioned and deduped. A case reachable through two arms is one member, which is why the count
 * is {@code COUNT(DISTINCT c.id)} and not {@code COUNT(*)}: a case both filed in the suite and
 * matching its tag rule would otherwise make a folder of 40 report 41, and the number that is wrong
 * is the one nobody can check.
 *
 * <h2>One class, because two implementations of this drift into two different suites</h2>
 *
 * <p>The tree's counts, the case grid's suite filter and a run's scope all have to mean the same
 * thing by "in this suite". Before this class they trivially did — there was one column. Now the
 * union is four lines of SQL that read as though anyone could write them, and two copies would
 * agree until one of them learns about soft-deleted cases and the other does not. §32's defect on
 * the suite tree is the same shape and cost a slice.
 *
 * <h2>Native SQL, and what that costs</h2>
 *
 * <p>HQL cannot express the {@code ltree} descendant operator the subtree resolution needs, and
 * splitting "this suite" from "this subtree" across two mechanisms is exactly the drift above. So
 * both are native, both carry an explicit {@code company_id} predicate <em>and</em> a
 * {@code project_id} one, and RLS remains the boundary underneath rather than the only one:
 * {@code FORCE ROW LEVEL SECURITY} applies to a native statement as much as to a mapped one, and
 * the predicates state the same intent so a future mismatch returns nothing rather than something.
 *
 * <h2>Deleted rows</h2>
 *
 * <p>Deleted <em>cases</em> are excluded from every arm: a soft-deleted case is gone from the grid,
 * so counting it here would make the tree contradict the list — {@code SuiteRepository}'s own note,
 * and it applies to all three arms rather than only to the filed one. Deleted <em>suites</em> are
 * excluded as the caller's tree already is; a folder that no longer exists holds nothing.
 */
@Component
public class SuiteMembership {

    private final EntityManager entities;

    public SuiteMembership(EntityManager entities) {
        this.entities = entities;
    }

    /**
     * How many live cases each of a project's suites holds, by the whole union.
     *
     * <p>The suite's own membership. It said "not its subtree's" until §98, because the SPA's tree
     * summed a node with its children and a subtree count would have been counted again at every
     * ancestor; there is no subtree left for it to be confused with.
     *
     * <p><strong>These counts no longer sum to the project's case count, and that is not a defect
     * to fix by summing differently.</strong> A case can be in three suites — filed in one, added
     * to another, tagged into a third — so the totals genuinely overlap. The client says so rather
     * than letting a reader arrive at it by subtraction.
     *
     * @return suite id → member count. A suite with no members is absent rather than zero; the
     *         caller renders a default, exactly as it did when this was one {@code GROUP BY}.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Long> countsBySuite(UUID companyId, UUID projectId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery("""
                        SELECT s.id, COUNT(DISTINCT c.id)
                          FROM suite s
                          JOIN test_case c
                            ON c.company_id = s.company_id
                           AND c.project_id = s.project_id
                           AND c.deleted_at IS NULL
                           AND ( c.suite_id = s.id
                              OR EXISTS (SELECT 1 FROM suite_case m
                                          WHERE m.suite_id = s.id AND m.case_id = c.id)
                              OR EXISTS (SELECT 1 FROM suite_tag_rule r
                                           JOIN case_tag t ON t.tag = r.tag AND t.case_id = c.id
                                          WHERE r.suite_id = s.id) )
                         WHERE s.company_id = :companyId
                           AND s.project_id = :projectId
                           AND s.deleted_at IS NULL
                         GROUP BY s.id
                        """)
                .setParameter("companyId", companyId)
                .setParameter("projectId", projectId)
                .getResultList();

        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : rows) {
            counts.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    /**
     * Every live case in one suite, by the whole union — the set a run scoped on that suite covers,
     * and the set the case grid's {@code suiteId} filter selects.
     *
     * <p><strong>The suite, not a subtree, since §98.</strong> This walked {@code d.path <@
     * root.path} — {@code ltree}'s descendant-or-self operator — because
     * {@code plan_scope_item.suite_id} had always meant "this folder and everything under it", and
     * a tag rule on a child folder was as much a claim as a case filed in one. With no tree, a
     * suite arm resolves to what that suite itself holds.
     *
     * <p>Ordered by {@code key_seq} rather than by id: a scope is read as a work list, and
     * {@code TC-10000} sorts before {@code TC-9999} as text.
     */
    @Transactional(readOnly = true)
    public List<UUID> caseIdsIn(UUID companyId, UUID projectId, UUID suiteId) {
        @SuppressWarnings("unchecked")
        List<UUID> ids = entities.createNativeQuery("""
                        SELECT DISTINCT c.id, c.key_seq
                          FROM suite s
                          JOIN test_case c
                            ON c.company_id = s.company_id
                           AND c.project_id = s.project_id
                           AND c.deleted_at IS NULL
                           AND ( c.suite_id = s.id
                              OR EXISTS (SELECT 1 FROM suite_case m
                                          WHERE m.suite_id = s.id AND m.case_id = c.id)
                              OR EXISTS (SELECT 1 FROM suite_tag_rule r
                                           JOIN case_tag t ON t.tag = r.tag AND t.case_id = c.id
                                          WHERE r.suite_id = s.id) )
                         WHERE s.company_id = :companyId
                           AND s.project_id = :projectId
                           AND s.deleted_at IS NULL
                           AND s.id = :suiteId
                         ORDER BY c.key_seq
                        """)
                .setParameter("companyId", companyId)
                .setParameter("projectId", projectId)
                .setParameter("suiteId", suiteId)
                .getResultList()
                .stream()
                .map(row -> (UUID) ((Object[]) row)[0])
                .toList();
        return List.copyOf(ids);
    }

    /**
     * Which of a project's suites each case belongs to — the inverse, for the case detail's
     * "in these suites" list.
     *
     * <p>Reads the same three arms in the same order, so a case that appears in a suite's list has
     * that suite in its own. A second query that disagreed would put a case in a folder the case
     * itself denies being in, and both screens would look right.
     *
     * @param caseIds must not be empty — an empty {@code IN ()} is a syntax error
     * @return case id → the suite ids holding it, in suite key order
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<UUID>> suitesByCase(UUID companyId, List<UUID> caseIds) {
        if (caseIds.isEmpty()) {
            return Map.of();
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery("""
                        SELECT c.id, s.id
                          FROM test_case c
                          JOIN suite s
                            ON s.company_id = c.company_id
                           AND s.project_id = c.project_id
                           AND s.deleted_at IS NULL
                           AND ( c.suite_id = s.id
                              OR EXISTS (SELECT 1 FROM suite_case m
                                          WHERE m.suite_id = s.id AND m.case_id = c.id)
                              OR EXISTS (SELECT 1 FROM suite_tag_rule r
                                           JOIN case_tag t ON t.tag = r.tag AND t.case_id = c.id
                                          WHERE r.suite_id = s.id) )
                         WHERE c.company_id = :companyId
                           AND c.id IN (:caseIds)
                         ORDER BY s.key_seq
                        """)
                .setParameter("companyId", companyId)
                .setParameter("caseIds", caseIds)
                .getResultList();

        Map<UUID, List<UUID>> bySuite = new LinkedHashMap<>();
        for (Object[] row : rows) {
            bySuite.computeIfAbsent((UUID) row[0], key -> new ArrayList<>()).add((UUID) row[1]);
        }
        return bySuite;
    }
}
