package com.tcms.tracker.release;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * <strong>The one place in {@code tracker} that names a table this module does not own</strong> —
 * {@code repository.ExecutionFacts} and {@code execution.CaseFacts} are the pattern, and the rule is
 * theirs: each module reads rows it holds ids into through <em>exactly one file</em>, so a reviewer
 * can find every crossing by opening one class per module. A second class here naming any of the
 * four tables below ends that property, and it would end it for the most tempting reason there is —
 * that the new query is short.
 *
 * <p>Four foreign tables, each read for a different question:
 *
 * <ul>
 *   <li>{@code test_case} — does this case exist, in this project, and what is its key and title.
 *       The write path validates against it and both read paths render from it.</li>
 *   <li>{@code case_health} — the latest result a case has <em>anywhere</em>, for a story's own
 *       panel (H7). A read model {@code repository} maintains on a tick, and the same row
 *       {@code TestCaseSummary.lastResult} is served from, so a case cannot show one result on its
 *       own screen and another on the story that links it.</li>
 *   <li>{@code execution_fact} — the latest result per case <em>within one release version</em>,
 *       which is readiness. An append-only fact log {@code reporting} refreshes on a tick.</li>
 *   <li>{@code test_run} — the name of the run a readiness answer came from, for the evidence
 *       panel. One column, joined off {@code execution_fact}'s own {@code run_id}.</li>
 * </ul>
 *
 * <h2>Why a request-path read here is not the coupling {@code ExecutionFacts} refuses</h2>
 *
 * <p>That file's header warns against <em>synchronous request-path coupling</em> to another module,
 * and readiness is exactly a request-path read. The difference is what is on the other side:
 * {@code case_health} and {@code execution_fact} are <strong>read models refreshed on a tick</strong>,
 * not live transactional tables, so nothing read here can block a write anywhere. What
 * {@code ExecutionFacts} actually refuses is a convenience method that moves <em>rows</em> across the
 * seam for the caller to loop over — its own words, "a just fetch the last result for these forty
 * case ids convenience" — and every method below returns an aggregate or a per-case verdict computed
 * in one statement. <strong>Do not add the convenience method.</strong>
 *
 * <h2>Native SQL, and no entity for another module's table</h2>
 *
 * <p>{@code CaseFacts}' argument, verbatim: a second JPA entity mapped over a table another module
 * owns is one table with two sets of column expectations for {@code ddl-auto: validate} to check,
 * and two places a mapping can drift. SQL through the {@link EntityManager} states plainly what this
 * is — a read of rows this module holds ids into, not a claim on their mapping.
 *
 * <p>RLS still covers every statement. Each runs inside the caller's transaction, so
 * {@code app.current_company} is set by {@code CompanyAwareTransactionManager} and one company's
 * executions are unreachable from another's session regardless of what these ask for — which is why
 * none of them carries a company predicate of its own.
 *
 * <h2>Every method short-circuits on an empty id set</h2>
 *
 * <p>An empty collection bound into {@code IN (:ids)} is a syntax error in Postgres, and "this story
 * has no linked cases" is the ordinary state of every story in the product on the day this module
 * ships. The guard is at the top of each method rather than at each call site.
 */
@Component
class ReleaseFacts {

    /**
     * The key and title of cases in this project, and the existence check behind the 400 on
     * {@code PUT /issues/{key}/test-cases}.
     *
     * <p>A case from another project simply does not come back, which is what makes the write
     * path's refusal a set difference rather than a second query.
     */
    private static final String CASES = """
            SELECT c.id, c.key, c.title
              FROM test_case c
             WHERE c.project_id = :projectId
               AND c.id IN (:caseIds)
               AND c.deleted_at IS NULL
            """;

    /**
     * The latest result a case has <strong>anywhere</strong> — {@code case_health}'s four columns,
     * written by one statement so they cannot disagree with each other.
     */
    private static final String LAST_RESULTS = """
            SELECT h.case_id, h.last_status_kind, h.last_executed_at, h.last_release_version
              FROM case_health h
             WHERE h.case_id IN (:caseIds)
            """;

    /**
     * The latest result per case <em>for one version</em>. Readiness is this statement.
     *
     * <p><strong>Matched on {@code release_version} as text, never on {@code test_run.release_id}</strong>
     * (plan 013 D-b). D14 backfills nothing, so every run in every existing instance has a NULL
     * foreign key, and filtering on it would report a version with three hundred executions against
     * it as untested — the exact shape of silent wrong answer this codebase legislates against, and
     * the one the whole three-member verdict exists to keep out.
     *
     * <p><strong>The LATEST result, not the best one.</strong> {@code DISTINCT ON (case_id)} ordered
     * by {@code executed_at DESC} — a case that failed and was not re-run is a failure, and taking
     * the best result would let a release pass on a run somebody re-ran until it went green.
     * {@code execution_fact_case_idx} is {@code (company_id, case_id, executed_at DESC)}, so the
     * ordering is the index's rather than a sort.
     *
     * <p>{@code executed_at IS NOT NULL} because creating a run writes one empty execution per case
     * in scope (§42): a case in a freshly planned run has a fact row and has never been executed,
     * and counting it as a result would make planning a run look like running it.
     */
    private static final String LATEST_PER_CASE = """
            SELECT DISTINCT ON (f.case_id)
                   f.case_id, f.status_kind, f.counts_as_pass, f.executed_at, f.run_id, f.executed_by
              FROM execution_fact f
             WHERE f.project_id = :projectId
               AND f.release_version = :version
               AND f.executed_at IS NOT NULL
               AND f.case_id IN (:caseIds)
             ORDER BY f.case_id, f.executed_at DESC
            """;

    /** The newest execution against this version, and the run it belonged to — the evidence panel. */
    private static final String LATEST_RUN = """
            SELECT f.run_id, r.name, f.executed_at, f.executed_by
              FROM execution_fact f
              JOIN test_run r ON r.id = f.run_id
             WHERE f.project_id = :projectId
               AND f.release_version = :version
               AND f.executed_at IS NOT NULL
               AND f.case_id IN (:caseIds)
             ORDER BY f.executed_at DESC
             LIMIT 1
            """;

    private final EntityManager entities;

    /**
     * Constructor-injected rather than {@code @PersistenceContext} on a field: the project's rule is
     * constructor injection everywhere, and Spring resolves this to the same shared,
     * transaction-bound proxy the annotation would have produced.
     */
    ReleaseFacts(EntityManager entities) {
        this.entities = entities;
    }

    /** A test case, as much of one as a story panel or a blocker row renders. */
    record CaseRef(UUID id, String key, String title) {
    }

    /** What a case last did, anywhere. Every member may be null: a case may never have run. */
    record LastResult(String statusKind, Instant executedAt, String releaseVersion) {
    }

    /** What a case last did <em>against one version</em>. {@code countsAsPass} is the server's. */
    record VersionResult(String statusKind, boolean countsAsPass, Instant executedAt) {
    }

    /** The run a readiness answer came from. */
    record RunRef(UUID runId, String name, Instant executedAt, UUID executedBy) {
    }

    Map<UUID, CaseRef> casesOf(UUID projectId, Collection<UUID> caseIds) {
        Map<UUID, CaseRef> found = new LinkedHashMap<>();
        if (caseIds.isEmpty()) {
            return found;
        }
        for (Object result : entities.createNativeQuery(CASES)
                .setParameter("projectId", projectId)
                .setParameter("caseIds", caseIds)
                .getResultList()) {

            Object[] row = (Object[]) result;
            UUID id = UUID.fromString(row[0].toString());
            found.put(id, new CaseRef(id, (String) row[1], (String) row[2]));
        }
        return found;
    }

    Map<UUID, LastResult> lastResultsOf(Collection<UUID> caseIds) {
        Map<UUID, LastResult> found = new HashMap<>();
        if (caseIds.isEmpty()) {
            return found;
        }
        for (Object result : entities.createNativeQuery(LAST_RESULTS)
                .setParameter("caseIds", caseIds)
                .getResultList()) {

            Object[] row = (Object[]) result;
            found.put(UUID.fromString(row[0].toString()), new LastResult(
                    (String) row[1], toInstant(row[2]), (String) row[3]));
        }
        return found;
    }

    Map<UUID, VersionResult> latestPerCaseForVersion(
            UUID projectId, String version, Collection<UUID> caseIds) {

        Map<UUID, VersionResult> found = new HashMap<>();
        if (caseIds.isEmpty() || version == null || version.isBlank()) {
            return found;
        }
        for (Object result : entities.createNativeQuery(LATEST_PER_CASE)
                .setParameter("projectId", projectId)
                .setParameter("version", version)
                .setParameter("caseIds", caseIds)
                .getResultList()) {

            Object[] row = (Object[]) result;
            found.put(UUID.fromString(row[0].toString()), new VersionResult(
                    (String) row[1], (Boolean) row[2], toInstant(row[3])));
        }
        return found;
    }

    Optional<RunRef> latestRunForVersion(UUID projectId, String version, Collection<UUID> caseIds) {
        if (caseIds.isEmpty() || version == null || version.isBlank()) {
            return Optional.empty();
        }
        List<?> rows = entities.createNativeQuery(LATEST_RUN)
                .setParameter("projectId", projectId)
                .setParameter("version", version)
                .setParameter("caseIds", caseIds)
                .getResultList();

        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = (Object[]) rows.getFirst();
        return Optional.of(new RunRef(
                UUID.fromString(row[0].toString()),
                (String) row[1],
                toInstant(row[2]),
                row[3] == null ? null : UUID.fromString(row[3].toString())));
    }

    /** Ids in the order the caller gave them, minus the ones this project does not have. */
    static List<UUID> known(Collection<UUID> wanted, Map<UUID, CaseRef> found) {
        List<UUID> kept = new ArrayList<>();
        for (UUID id : wanted) {
            if (found.containsKey(id)) {
                kept.add(id);
            }
        }
        return kept;
    }

    /**
     * {@code TIMESTAMPTZ} arrives as {@link java.sql.Timestamp} through a native query, unlike the
     * {@link Instant} an entity mapping would hand back — the one place this class pays for reading
     * rows rather than mapping them. {@code CaseFacts} and {@code CoverageMatrixReport} carry the
     * same three arms for the same reason.
     */
    private static Instant toInstant(Object value) {
        return switch (value) {
            case null -> null;
            case Instant instant -> instant;
            case java.sql.Timestamp timestamp -> timestamp.toInstant();
            case java.time.OffsetDateTime offset -> offset.toInstant();
            default -> throw new IllegalStateException(
                    "Unexpected timestamp type " + value.getClass());
        };
    }
}
