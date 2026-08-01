package com.tcms.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * <strong>The one place in this module that names a {@code repository} table.</strong>
 *
 * <p>Everything the package comment says about the seam applies here and nowhere else. Two facts
 * live on the other side of it and this class is the whole of the crossing:
 *
 * <ol>
 *   <li><strong>What a pinned case version said</strong> — {@link #pinnedCases}, read from
 *       {@code test_case_version.snapshot}, which {@code test_execution.case_version_id} is a
 *       foreign key to. The snapshot is the resolved case as {@code GET /cases/{caseId}} served it
 *       when that version was authored, so this returns <em>history</em>, not the live case. That
 *       is what the execution screen is required to show (plan §6j) and it is also the narrower
 *       coupling — this module never reads {@code test_case}, {@code test_step}, {@code suite} or
 *       {@code condition}.</li>
 *   <li><strong>How many live cases a plan's scope covers</strong> — {@link #caseCountsByPlan}.
 *       {@code plan_scope_item} holds foreign keys to {@code suite} and {@code test_case} (V1), and
 *       a suite item means <em>the folder and everything beneath it</em>, which only the {@code ltree}
 *       path can answer.</li>
 *   <li><strong>Which cases a plan's scope covers</strong> — {@link #caseIdsInPlanScope}, the same
 *       expansion returning ids instead of a count, because creating a run needs the members and
 *       not the size of the set.</li>
 *   <li><strong>…and which cases those are</strong> — {@link #casesInPlanScope}, the same expansion
 *       a third time, returning the rows. The plan statistics endpoint must name the cases nobody
 *       has executed and group every in-scope case under its suite, and neither is answerable from
 *       ids alone. <em>This is the one method here that reads a live case's content</em>;
 *       {@link ScopedCase} says why that is correct rather than a lapse.</li>
 *   <li><strong>Which of a set of ids name live rows in this project</strong> —
 *       {@link #liveSuiteIds} and {@link #liveCaseIds}, so a plan's scope items and a run's case
 *       list are validated rather than trusted. {@code plan_scope_item.suite_id} and
 *       {@code case_id} are foreign keys, so an absent id would be a 500 at insert and an id from
 *       a <em>sibling project in the same company</em> would be accepted by the database and by
 *       RLS — the one case only this predicate refuses.</li>
 *   <li><strong>Which version each case is on right now</strong> — {@link #currentVersionIds},
 *       <em>the write half of the pin</em> and the first thing in this module that reads a
 *       {@code repository} row in order to store its id. Everything the pin is for depends on this
 *       being resolved once, at creation, and never re-resolved.</li>
 * </ol>
 *
 * <p>The last two are new in M2 slice 2 and they are deliberately <strong>here</strong> rather than
 * in the two create services. Adding a second class that names {@code test_case} would end the one
 * property that makes this seam reviewable — that a reader can find every crossing by opening one
 * file — and it would end it for the most tempting reason there is, which is that the new query is
 * short.
 *
 * <h2>Native SQL, and why there is no entity for either table</h2>
 *
 * <p>A second JPA entity mapped over {@code test_case_version} in this package would be the same
 * table owned by two modules, with two sets of column expectations for {@code ddl-auto: validate} to
 * check and two places a mapping can drift. SQL through the {@link EntityManager} states plainly
 * what this is: a read of rows this module holds ids into, not a claim on their mapping.
 *
 * <p>RLS still covers every statement here — it runs inside the caller's transaction, so
 * {@code app.current_company} is set by {@code CompanyAwareTransactionManager} and a company's
 * snapshot is unreachable from another's session regardless of what these queries ask for. The
 * project predicate is layered on top for the second gate, never instead of it.
 *
 * <h2>The snapshot's shape is not compiler-checked, and one test closes that</h2>
 *
 * <p>{@code repository.CaseVersions} serializes a {@code TestCaseDetailView}; this class reads six
 * of its fields by name. Nothing fails to compile if that record is renamed. So
 * {@code ExecutionEndpointTest} authors a case through the real {@code POST /cases} and asserts its
 * steps arrive through {@code GET …/executions/{id}} — the coupling is checked end to end, by the
 * two real writers, rather than by a fixture that hand-writes the JSON this class hopes for.
 *
 * <p>Every field read here is read <em>defensively</em>: an absent or null node yields the empty or
 * null value rather than an exception. A snapshot written by a future {@code repository} that drops
 * a field must degrade to a missing detail, not to an unreadable past run.
 */
@Component
class CaseFacts {

    /**
     * Constructor-injected, not {@code @PersistenceContext}-annotated on a field: the project's
     * rule is constructor injection everywhere, and Spring resolves this to the same shared,
     * transaction-bound proxy the annotation would have produced.
     */
    private final EntityManager entities;

    private final ObjectMapper json;

    CaseFacts(EntityManager entities, ObjectMapper json) {
        this.entities = entities;
        this.json = json;
    }

    /**
     * One pinned case version, as it read when it was authored.
     *
     * @param caseKey    the display key. Not pinned in any meaningful sense — a display key is
     *                   immutable (§6t) — but taken from the snapshot anyway so that every field on
     *                   the response has one source and none of them is a live lookup.
     * @param title      <strong>pinned</strong>. An execution beside "v3" showing today's title is
     *                   two claims that contradict each other on one line.
     * @param keySeq     the numeric half of {@link #caseKey}, carried because a run's checklist is
     *                   ordered by it: {@code TC-10000} sorts before {@code TC-9999} as text, and a
     *                   checklist whose order depends on the string is one a tester loses their
     *                   place in. Same rule the case grid follows.
     * @param versionNo  the number beside the title, which is what a history entry matches.
     */
    record PinnedCase(
            String caseKey,
            long keySeq,
            String title,
            int versionNo,
            StepFormat stepFormat,
            List<ConditionRefView> preconditions,
            List<ConditionRefView> postconditions,
            List<PinnedStep> steps) {
    }

    /** One step of a pinned version — the authored half of the contract's {@code StepResult}. */
    record PinnedStep(
            UUID stepId,
            int position,
            String keyword,
            String action,
            String testData,
            String expectedResult) {
    }

    /**
     * The pinned content behind a batch of {@code case_version_id}s, by version id.
     *
     * <p>A batch rather than one call per execution: a run detail resolves two hundred of these, and
     * a query per row is the N+1 that looks harmless because each one is fast.
     *
     * <p>A version id with no row is simply absent from the map. That cannot happen through the
     * schema — {@code case_version_id} is a {@code NOT NULL} foreign key — but it can happen through
     * RLS, if a caller's company is not the one that owns the version, and the caller then sees an
     * execution with no steps rather than an error naming another client's row.
     */
    Map<UUID, PinnedCase> pinnedCases(Collection<UUID> caseVersionIds) {
        if (caseVersionIds.isEmpty()) {
            // An empty IN list is a SQL syntax error, and a run with no executions is ordinary.
            return Map.of();
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery("""
                        SELECT v.id, v.version_no, CAST(v.snapshot AS text)
                        FROM test_case_version v
                        WHERE v.id IN (:ids)
                        """)
                .setParameter("ids", caseVersionIds)
                .getResultList();

        Map<UUID, PinnedCase> pinned = new HashMap<>();
        for (Object[] row : rows) {
            UUID versionId = (UUID) row[0];
            int versionNo = ((Number) row[1]).intValue();
            pinned.put(versionId, parse(versionNo, (String) row[2]));
        }
        return pinned;
    }

    /**
     * How many live cases each plan's scope covers, by plan id.
     *
     * <p>A scope item names either a case or a suite, never both ({@code ck_plan_scope_target}). A
     * case item counts itself; a <strong>suite item counts the folder and every descendant</strong>,
     * which is what "add this folder to the plan" means to the person who did it — and it is why
     * this cannot be {@code COUNT(*)} over {@code plan_scope_item}. {@code <@} is the {@code ltree}
     * descendant-or-self operator, the same one the suite subtree checks use.
     *
     * <p>{@code COUNT(DISTINCT c.id)} because a case can be in scope twice — named directly and
     * again through its folder — and a plan that says "40 cases" for 27 is the defect §32 found on
     * the suite tree, one entity over.
     *
     * <p>Deleted cases and deleted suites are excluded. A plan whose folder was emptied covers
     * nothing, and reporting a count that includes rows no read can return is worse than reporting
     * zero.
     */
    Map<UUID, Long> caseCountsByPlan(UUID projectId, Collection<UUID> planIds) {
        if (planIds.isEmpty()) {
            return Map.of();
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery("""
                        SELECT i.plan_id, COUNT(DISTINCT c.id)
                        FROM plan_scope_item i
                        LEFT JOIN suite s ON s.id = i.suite_id AND s.deleted_at IS NULL
                        JOIN test_case c
                          ON c.project_id = :projectId
                         AND c.deleted_at IS NULL
                         AND ( (i.case_id IS NOT NULL AND c.id = i.case_id)
                            OR (i.suite_id IS NOT NULL AND c.suite_id IN (
                                    SELECT d.id FROM suite d
                                    WHERE d.project_id = s.project_id
                                      AND d.deleted_at IS NULL
                                      AND d.path <@ s.path)) )
                        WHERE i.plan_id IN (:planIds)
                        GROUP BY i.plan_id
                        """)
                .setParameter("projectId", projectId)
                .setParameter("planIds", planIds)
                .getResultList();

        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : rows) {
            counts.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    /**
     * The live cases a plan's scope covers, as ids.
     *
     * <p><strong>The same expansion {@link #caseCountsByPlan} counts</strong> — deliberately the
     * same predicate, kept adjacent so the two cannot drift. A run created from a plan whose case
     * set differed from the plan's own displayed {@code caseCount} would show a tester a checklist
     * of a different length from the plan they created it from, on the previous screen, and neither
     * number would be identifiable as the wrong one.
     *
     * <p>Ordered by {@code key_seq}, not by id: the run's checklist is read in key order, and
     * {@code TC-10000} sorts before {@code TC-9999} as text. Same rule the case grid follows.
     */
    List<UUID> caseIdsInPlanScope(UUID projectId, UUID planId) {
        @SuppressWarnings("unchecked")
        List<UUID> rows = entities.createNativeQuery("""
                        SELECT DISTINCT c.id, c.key_seq
                        FROM plan_scope_item i
                        LEFT JOIN suite s ON s.id = i.suite_id AND s.deleted_at IS NULL
                        JOIN test_case c
                          ON c.project_id = :projectId
                         AND c.deleted_at IS NULL
                         AND ( (i.case_id IS NOT NULL AND c.id = i.case_id)
                            OR (i.suite_id IS NOT NULL AND c.suite_id IN (
                                    SELECT d.id FROM suite d
                                    WHERE d.project_id = s.project_id
                                      AND d.deleted_at IS NULL
                                      AND d.path <@ s.path)) )
                        WHERE i.plan_id = :planId
                        ORDER BY c.key_seq
                        """)
                .setParameter("projectId", projectId)
                .setParameter("planId", planId)
                .getResultList()
                .stream()
                .map(row -> (UUID) ((Object[]) row)[0])
                .toList();
        return rows;
    }

    /**
     * One in-scope case, with the suite it sits in.
     *
     * <p><strong>The live case, and here that is the right one</strong> — which makes this the only
     * method on this class that reads {@code test_case} for its content rather than its id, and the
     * exception is worth stating. Everything else about an execution is pinned, because an execution
     * is a claim about a fixed text. A <em>not-run</em> case is the opposite: it is a claim about
     * work still outstanding, so it must name the case as it is now and the suite it is in now. A
     * pinned title here would send someone to look for a case under a name it no longer has.
     *
     * @param suiteName the suite's own name, not its path. The panel renders it at the end of a row
     *                  as context for the title, and a six-level path there would be the widest
     *                  column on the screen carrying the least information.
     */
    record ScopedCase(
            UUID id,
            String key,
            long keySeq,
            String title,
            UUID suiteId,
            String suiteKey,
            String suiteName) {
    }

    /**
     * The live cases a plan's scope covers, with their suites.
     *
     * <p><strong>The third caller of the same scope expansion</strong>, and the reason all three are
     * in this file: {@link #caseCountsByPlan} counts it, {@link #caseIdsInPlanScope} lists its ids,
     * and this returns its rows. A plan whose statistics said "312 cases" while its own detail said
     * 307 would have two numbers on two screens with nothing to identify the wrong one, which is
     * exactly the defect §32 found on the suite tree. Kept adjacent so the predicate cannot drift;
     * {@code PlanStatisticsTest} asserts the two agree over the same fixture.
     *
     * <p>Ordered by {@code key_seq} for {@link #caseIdsInPlanScope}'s reason — a not-run list is read
     * as a work list, and {@code TC-10000} sorts before {@code TC-9999} as text.
     */
    List<ScopedCase> casesInPlanScope(UUID projectId, UUID planId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery("""
                        SELECT DISTINCT c.id, c.key, c.key_seq, c.title,
                                        c.suite_id, cs.key, cs.name
                        FROM plan_scope_item i
                        LEFT JOIN suite s ON s.id = i.suite_id AND s.deleted_at IS NULL
                        JOIN test_case c
                          ON c.project_id = :projectId
                         AND c.deleted_at IS NULL
                         AND ( (i.case_id IS NOT NULL AND c.id = i.case_id)
                            OR (i.suite_id IS NOT NULL AND c.suite_id IN (
                                    SELECT d.id FROM suite d
                                    WHERE d.project_id = s.project_id
                                      AND d.deleted_at IS NULL
                                      AND d.path <@ s.path)) )
                        JOIN suite cs ON cs.id = c.suite_id
                        WHERE i.plan_id = :planId
                        ORDER BY c.key_seq
                        """)
                .setParameter("projectId", projectId)
                .setParameter("planId", planId)
                .getResultList();

        List<ScopedCase> scoped = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            scoped.add(new ScopedCase(
                    (UUID) row[0],
                    (String) row[1],
                    ((Number) row[2]).longValue(),
                    (String) row[3],
                    (UUID) row[4],
                    (String) row[5],
                    (String) row[6]));
        }
        return List.copyOf(scoped);
    }

    /**
     * Of {@code ids}, those naming a live suite in this project.
     *
     * <p>The project predicate is the whole point and it is the gate nothing else applies: RLS
     * refuses another <em>company</em>'s suite, and the foreign key refuses an id that names nothing
     * at all — but a suite from a <strong>sibling project in the caller's own company</strong>
     * passes both, and would put a folder from another product into this plan's scope. The caller
     * may well hold a grant on that project too, so no access check catches it either.
     */
    Set<UUID> liveSuiteIds(UUID projectId, Collection<UUID> ids) {
        return liveIds("suite", projectId, ids);
    }

    /** Of {@code ids}, those naming a live case in this project. See {@link #liveSuiteIds}. */
    Set<UUID> liveCaseIds(UUID projectId, Collection<UUID> ids) {
        return liveIds("test_case", projectId, ids);
    }

    /**
     * @param table one of two literals chosen by the two callers above, never a parameter reaching
     *              this class from outside it — the two tables have identical shapes for this
     *              question and two near-identical methods is how one of them loses its
     *              {@code deleted_at} predicate.
     */
    private Set<UUID> liveIds(String table, UUID projectId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        @SuppressWarnings("unchecked")
        List<UUID> rows = entities.createNativeQuery(
                        "SELECT t.id FROM " + table + " t "
                                + "WHERE t.project_id = :projectId AND t.deleted_at IS NULL "
                                + "AND t.id IN (:ids)")
                .setParameter("projectId", projectId)
                .setParameter("ids", ids)
                .getResultList();
        return new LinkedHashSet<>(rows);
    }

    /**
     * <strong>The write half of the pin</strong> — each case's current version row, by case id.
     *
     * <p>{@code test_case.current_version_no} names the version, {@code test_case_version} holds it,
     * and {@code uq_case_version (case_id, version_no)} makes the join single-valued. This is
     * resolved <em>once</em>, when the run is created, and stored on every execution — which is what
     * makes an execution a statement about a fixed text. Re-resolving it later, however convenient,
     * is the one thing {@link TestExecution#getCaseVersionId} exists to prevent.
     *
     * <p>A case with no version row is simply <strong>absent from the map</strong>, and the caller
     * refuses the whole create rather than dropping it. Every case authored since §29 has a v1
     * written with it; a case predating that path could have none, and there are two honest answers
     * — refuse, or materialise a shorter checklist than the tester asked for and say nothing. The
     * second is how a run silently omits cases, so the caller takes the first.
     */
    Map<UUID, UUID> currentVersionIds(UUID projectId, Collection<UUID> caseIds) {
        if (caseIds.isEmpty()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery("""
                        SELECT c.id, v.id
                        FROM test_case c
                        JOIN test_case_version v
                          ON v.case_id = c.id AND v.version_no = c.current_version_no
                        WHERE c.project_id = :projectId
                          AND c.deleted_at IS NULL
                          AND c.id IN (:caseIds)
                        """)
                .setParameter("projectId", projectId)
                .setParameter("caseIds", caseIds)
                .getResultList();

        Map<UUID, UUID> pins = new HashMap<>();
        for (Object[] row : rows) {
            pins.put((UUID) row[0], (UUID) row[1]);
        }
        return pins;
    }

    private PinnedCase parse(int versionNo, String snapshot) {
        JsonNode root = read(snapshot);
        return new PinnedCase(
                text(root, "key"),
                root.path("keySeq").asLong(),
                text(root, "title"),
                versionNo,
                StepFormat.of(text(root, "stepFormat")),
                conditions(root.path("preconditions")),
                conditions(root.path("postconditions")),
                steps(root.path("steps")));
    }

    private JsonNode read(String snapshot) {
        try {
            return json.readTree(snapshot);
        } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
            // The column is JSONB, so Postgres already refused anything unparseable at insert.
            // Reaching here means the value changed shape outside the application, which is a
            // corrupt row rather than a request this endpoint should try to serve.
            throw new IllegalStateException("A pinned case snapshot is not valid JSON", malformed);
        }
    }

    private List<PinnedStep> steps(JsonNode array) {
        List<PinnedStep> steps = new ArrayList<>();
        int fallbackPosition = 1;
        for (JsonNode step : array) {
            steps.add(new PinnedStep(
                    uuid(step, "id"),
                    step.path("position").isInt() ? step.get("position").asInt() : fallbackPosition,
                    text(step, "keyword"),
                    // Never null on the wire: the contract marks `action` required and an empty
                    // string is a step with nothing written in it, which is a real authored state.
                    text(step, "action") == null ? "" : text(step, "action"),
                    text(step, "testData"),
                    text(step, "expectedResult")));
            fallbackPosition++;
        }
        return steps;
    }

    private List<ConditionRefView> conditions(JsonNode array) {
        List<ConditionRefView> conditions = new ArrayList<>();
        for (JsonNode condition : array) {
            List<String> body = new ArrayList<>();
            for (JsonNode line : condition.path("body")) {
                body.add(line.asText());
            }
            conditions.add(new ConditionRefView(
                    uuid(condition, "id"),
                    condition.path("position").asInt(),
                    uuid(condition, "conditionId"),
                    text(condition, "title"),
                    List.copyOf(body),
                    condition.path("inline").asBoolean(false),
                    condition.path("inherited").asBoolean(false),
                    text(condition, "inheritedFrom")));
        }
        return conditions;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static UUID uuid(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? null : UUID.fromString(value);
    }
}
