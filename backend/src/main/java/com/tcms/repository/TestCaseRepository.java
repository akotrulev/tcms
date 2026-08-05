package com.tcms.repository;

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
public interface TestCaseRepository extends JpaRepository<TestCase, UUID> {

    /**
     * One live case, <strong>in the project the path named</strong>.
     *
     * <h2>The {@code projectId} predicate is the point of this method</h2>
     *
     * <p>The caller has already passed the gate for {@code projectId} and for nothing else, so a
     * lookup by case id alone would answer with any case in the company — including one in a
     * sibling project the caller holds no grant on. The path would say
     * {@code /projects/{granted}/cases/{ungranted}} and the response would be the ungranted case's
     * full text. That is the whole leak, it needs no cross-company step, and every cross-company
     * test in the suite still passes while it is open: the row genuinely is in the caller's
     * company.
     *
     * <p>So the two ids are checked together and a mismatch is indistinguishable from a case that
     * does not exist — 404, no body, exactly as {@link CaseController} answers an unreachable
     * project. Anything that told them apart would make the endpoint an oracle for case ids in
     * projects the caller cannot open.
     *
     * <h2>{@code deletedAt IS NULL}, and why a deleted case is a 404 rather than a tombstone</h2>
     *
     * <p>A case is soft-deleted because past runs pin the version they executed and must keep
     * resolving what they covered (§3 lesson 1). That is a reason to keep the <em>row</em>, not a
     * reason to keep serving it as current: the detail screen is where someone edits, executes and
     * links, and offering all three for a case that has been removed from the repository is worse
     * than saying it is gone. History reaches it through the run that pinned it, which is a
     * different endpoint with a different shape.
     *
     * <p>Not {@code findById}: that resolves by primary key through the persistence context, and
     * Hibernate filters do <strong>not</strong> apply to that path. RLS would still cover it, but
     * using one layer where both are available is how a layered defence quietly becomes a single
     * point (see {@code ProjectRepository}'s header).
     */
    @Query("""
            SELECT tc FROM TestCase tc
             WHERE tc.id = :caseId
               AND tc.projectId = :projectId
               AND tc.deletedAt IS NULL
            """)
    Optional<TestCase> findLiveInProject(
            @Param("projectId") UUID projectId, @Param("caseId") UUID caseId);

    /**
     * The live cases from a selection that are actually in this project — {@link #findLiveInProject}
     * for a bulk operation, in one query.
     *
     * <p><strong>Returns fewer rows than ids given, and that is the point.</strong> An id that is
     * soft-deleted, that belongs to a sibling project, or that never existed simply is not in the
     * result, and {@link CaseBulkService} turns each absentee into a {@code Skipped} verdict with a
     * reason. Doing this per id would be 500 round trips to render one preview; doing it without the
     * {@code projectId} predicate would edit cases through a project the caller was granted, which
     * is the two-segment-path hole {@code findLiveInProject}'s caller comment describes.
     *
     * <p>The caller must not pass an empty collection — an empty {@code IN ()} is a SQL syntax error
     * — and does not, because the parse step already rejects an empty selection with a 400.
     */
    @Query("""
            SELECT tc FROM TestCase tc
             WHERE tc.projectId = :projectId
               AND tc.id IN :caseIds
               AND tc.deletedAt IS NULL
            """)
    List<TestCase> findLiveInProjectByIds(
            @Param("projectId") UUID projectId, @Param("caseIds") Collection<UUID> caseIds);

    /**
     * Is this automation key already claimed by a <em>different</em> case in this company?
     *
     * <h2>Company-wide, and deliberately not project-scoped</h2>
     *
     * <p>{@code test_case_automation_key_idx} is unique on {@code (company_id, automation_key)}, and
     * §6c explains why the scope is the company: a CI job posts a result under a key without knowing
     * which project owns the case. Scoping this check to the project would pass for a key another
     * project has taken, and the insert would then fail on the constraint as a 500.
     *
     * <h2>Deleted cases still hold their keys, and this query agrees with the index</h2>
     *
     * <p>There is no {@code deletedAt IS NULL} here, and that is not the oversight it looks like:
     * the unique index is partial on {@code automation_key IS NOT NULL} and does
     * <strong>not</strong> exclude soft-deleted rows, so a retired case keeps its CI identifier
     * reserved. Adding the predicate here would make this check disagree with the constraint that
     * actually enforces it — the check would pass and the insert would fail, which is the worst of
     * both: a validated form that 500s on submit.
     *
     * <p>Keeping the key reserved is also correct on its own terms. Results still arrive for a
     * retired case's automated test until someone deletes the CI job, and handing that key to a new
     * case would silently file the old test's results against it.
     *
     * @param caseId excluded, so a case re-saving its own unchanged key is not rejected by itself —
     *               the bug this parameter exists to prevent, and one that would make every second
     *               save of an automated case fail
     */
    @Query("""
            SELECT COUNT(tc) > 0 FROM TestCase tc
             WHERE tc.automationKey = :automationKey
               AND tc.id <> :caseId
            """)
    boolean existsByAutomationKeyOtherThan(
            @Param("automationKey") String automationKey, @Param("caseId") UUID caseId);

    /**
     * The same check for a case that does not exist yet, and therefore with nothing to exclude.
     *
     * <p>Deliberately a second method rather than
     * {@link #existsByAutomationKeyOtherThan}(key, {@code UUID.randomUUID()}). A random id passed as
     * "the case to exclude" reads as if it excludes something, works by accident, and is the line
     * somebody later copies into a path where the exclusion matters. Every qualification the sibling's
     * header gives — company-wide, and soft-deleted rows still hold their keys — applies here
     * unchanged, because it is the same partial unique index doing the enforcing.
     */
    @Query("""
            SELECT COUNT(tc) > 0 FROM TestCase tc
             WHERE tc.automationKey = :automationKey
            """)
    boolean existsByAutomationKey(@Param("automationKey") String automationKey);

    /**
     * The case a previous request with this {@code Idempotency-Key} already created, if any.
     *
     * <h2>Scoped to the project because the constraint is</h2>
     *
     * <p>{@code test_case_created_idempotency_key_idx} is unique on
     * {@code (company_id, project_id, created_idempotency_key)} and this query names exactly those
     * three — company through RLS and the Hibernate filter, the other two explicitly. A lookup wider
     * or narrower than the constraint enforcing it is the failure
     * {@link #existsByAutomationKeyOtherThan}'s header describes from the other direction: the check
     * passes and the insert fails, so the caller gets a 500 out of a request that validated.
     *
     * <h2>No {@code deletedAt IS NULL}, and that is the whole point</h2>
     *
     * <p>A retry whose original case has since been deleted must still be recognised as a retry —
     * otherwise the second attempt creates a duplicate of something somebody deliberately removed, and
     * the index would refuse it anyway. The caller decides what to answer for a replay pointing at a
     * deleted case; this method's job is to say the key was used.
     */
    @Query("""
            SELECT tc FROM TestCase tc
             WHERE tc.projectId = :projectId
               AND tc.createdIdempotencyKey = :idempotencyKey
            """)
    Optional<TestCase> findByIdempotencyKey(
            @Param("projectId") UUID projectId, @Param("idempotencyKey") String idempotencyKey);

    /**
     * Every live case id in a project — the set a suite's individually-added members are validated
     * against (§96).
     *
     * <p>Ids and not entities: the caller asks a membership question, and loading the rows would
     * fetch every mapped column of every case in the project in order to compare primary keys.
     *
     * <p><strong>Live only</strong>, which is a bound rather than a filter: adding a soft-deleted
     * case to a suite would put a permanent invisible member in a collection whose count is on
     * screen. Read by {@code SuiteWriteService} only when the request actually names cases, so an
     * ordinary rename does not pay for it.
     */
    @Query("""
            SELECT tc.id FROM TestCase tc
             WHERE tc.projectId = :projectId
               AND tc.deletedAt IS NULL
            """)
    List<UUID> liveIdsForProject(@Param("projectId") UUID projectId);
}
