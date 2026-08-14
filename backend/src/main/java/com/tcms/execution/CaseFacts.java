package com.tcms.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 *   <li><strong>Which version a single case is on, and whether it is reachable at all</strong> —
 *       {@link #currentVersionNo}. The case timeline's default scope is "the current version", and
 *       <em>current</em> has to be resolved here because it is a {@code repository} fact; the same
 *       lookup doubles as the existence check that turns an unknown case into a 404, so the history
 *       endpoint never needs a second query to ask whether the case is real.</li>
 *   <li><strong>When each version was authored, by whom, and why</strong> —
 *       {@link #versionMarkers}, the metadata beside the snapshot {@link #pinnedCases} already
 *       reads. This is what makes a timeline say <em>"green, green, edited to v5, red, red"</em>
 *       (plan §6p), and reading it here rather than calling {@code repository}'s own
 *       {@code GET /versions} is the point of the seam: this module reads rows it holds ids into,
 *       it does not call the other module.</li>
 *   <li><strong>Which cases sit under one suite</strong> — {@link #caseIdsInSuite}. A suite's
 *       execution history is a query over this module's tables, but <em>which executions belong to
 *       the suite</em> is a {@code repository} fact and only the {@code ltree} path answers it for a
 *       folder's descendants.</li>
 *   <li><strong>When each version of a suite was authored</strong> — {@link #suiteVersionMarkers},
 *       {@link #versionMarkers}' counterpart over {@code suite_version}, so a suite timeline can say
 *       <em>"the membership changed here"</em> beside the pass rate that moved (plan §6p).</li>
 *   <li><strong>Which of a run's cases are not approved</strong> — {@link #unapprovedCases}, §6g's
 *       gating rule (§82). {@code test_case.lifecycle_status} is a {@code repository} fact and the
 *       run that must not materialise the case is this module's, so the predicate can only be asked
 *       here. It returns the offending cases' <em>keys</em> rather than a count or a boolean,
 *       because the refusal has to name them: "three of these are not approved" sends somebody to
 *       compare two lists of two hundred by eye.</li>
 *   <li><strong>Which cases a run's three-armed scope covers</strong> — {@link #caseIdsInRunScope}
 *       (§99). Cases named directly, cases belonging to a named suite, and cases carrying a named
 *       tag, unioned. Every one of the three is a {@code repository} fact, which is why the whole
 *       resolution is one query here rather than three lookups the service unions in Java.</li>
 * </ol>
 *
 * <p>Items 5–6 are new in M2 slice 2, 7–8 in M1 slice 15 and 9–10 in M1 slice 16, and they are
 * deliberately
 * <strong>here</strong> rather than in the services that want them. Adding a second class that
 * names {@code test_case} would end the one property that makes this seam reviewable — that a
 * reader can find every crossing by opening one file — and it would end it for the most tempting
 * reason there is, which is that the new query is short.
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
     * <strong>Membership in a suite's subtree</strong>, as a SQL predicate over an already-joined
     * case alias {@code c} and a suite alias supplied by the caller — the four queries below all
     * need it and it must mean the same thing in all four.
     *
     * <p>Since {@code V31} a suite is a resolvable collection, so this is three arms rather than
     * one column: the cases <strong>filed</strong> in a descendant folder, the cases
     * <strong>added</strong> to one individually ({@code suite_case}), and every case carrying
     * <strong>any</strong> of a descendant's tag rules ({@code suite_tag_rule} ⋈ {@code case_tag}).
     * Before §96 this was {@code c.suite_id IN (…)} written out four times, which could not drift
     * because it was a column comparison. It can now, which is why it is one string.
     *
     * <p>~~<strong>This is what makes "run everything tagged smoke" work with no new
     * mechanism</strong> — such a run is a suite whose tag rule is {@code smoke}, scoped into a plan
     * the way suites already are. There is no tag option on {@code plan_scope_item} and
     * {@code ck_plan_scope_target} is untouched.~~ → <strong>superseded 2026-08-05</strong> by §97's
     * D8: {@code V32} gave {@code plan_scope_item} a {@code tag} arm, so there are now two ways to
     * say "everything tagged smoke". The reversal is recorded rather than the sentence deleted —
     * forcing a plan section to express "the checkout cases" through a whole reusable
     * <em>suite</em>, with a key and a history, was the worse trade. See {@link #scopeMatch()}.
     *
     * <p>The other half of the paragraph still stands and is the reason this predicate exists at
     * all: without it a suite-armed scope silently resolves to whatever happens to be
     * <em>filed</em> under that folder, which for a tag-defined suite is usually nothing — a run of
     * zero cases, created successfully, reported as a plan covering none.
     *
     * <p><strong>It is a second implementation of {@code repository.SuiteMembership}'s union, and
     * that is forced rather than chosen.</strong> ERD §0.2 lets this module read the repository's
     * tables only through this one seam class, and calling into that module instead would trade a
     * duplicated predicate for a dependency the architecture test refuses. So the two are kept
     * honest by a test rather than by sharing code: {@code SuiteScopeAgreementTest} asserts both
     * resolutions answer the same set over one fixture — the same device
     * {@code PlanStatisticsEndpointTest} uses for the three expansions below.
     *
     * <p><strong>§98 removed the subtree and the correction to the plan that came with it.</strong>
     * The predicate used to wrap all three arms in {@code EXISTS (SELECT 1 FROM suite d … d.path <@
     * s.path …)}, because a suite arm meant a folder and everything under it. The plan for this
     * slice expected the whole method to disappear — "with no subtree it becomes membership, which
     * {@code SuiteMembership} already answers, so one of §96's two duplicated predicates
     * disappears". <strong>It does not.</strong> The duplication was never caused by the subtree;
     * it is caused by the module boundary in the paragraph above, which §98 does not touch. What
     * flattening changes is that the two predicates are now supposed to be <em>identical</em>
     * rather than merely equivalent — which makes {@code SuiteScopeAgreementTest} more useful,
     * not less, and it stays.
     *
     * @param suiteAlias the alias of the suite — {@code s} for a plan scope item's suite,
     *                   {@code root} for a suite's own history
     */
    private static String memberOf(String suiteAlias) {
        return """
                ( c.suite_id = %1$s.id
                  OR EXISTS (SELECT 1 FROM suite_case m
                              WHERE m.suite_id = %1$s.id AND m.case_id = c.id)
                  OR EXISTS (SELECT 1 FROM suite_tag_rule r
                               JOIN case_tag t ON t.tag = r.tag AND t.case_id = c.id
                              WHERE r.suite_id = %1$s.id) )
                """.formatted(suiteAlias);
    }

    /**
     * <strong>A plan scope item's three arms</strong>, as a SQL predicate over the item alias
     * {@code i}, the case alias {@code c} and the item's suite alias {@code s} — the three plan
     * expansions below all need it and it must mean the same thing in all three.
     *
     * <p>Written once for §96's reason, which is the reason this file has a standing rule about it:
     * the three queries answer *count*, *ids* and *rows* of one question, and a plan whose
     * statistics said 312 cases while its detail listed 307 would have two numbers on two screens
     * with nothing to identify the wrong one. Before §97 the branch was two arms and was copied
     * three times; the third arm is the point at which copying stops being survivable.
     *
     * <p><strong>The tag arm is an exact match on {@code case_tag}</strong> — the same comparison
     * {@code suite_tag_rule} makes inside {@link #memberOf}, so "add by tag" on a plan section
     * and "add by tag" on a suite genuinely are one act. It is a plain {@code EXISTS} and never a
     * join to {@code case_tag} in the {@code FROM} list: a case carrying three of the plan's tags
     * would otherwise multiply into three rows, which {@code COUNT(DISTINCT …)} survives and
     * {@code SELECT DISTINCT} survives and a future aggregate would not.
     *
     * <p><strong>A targetless row matches nothing, and that is what it is for.</strong> An empty
     * section is stored as a row naming the section with all three targets null, so every arm here
     * is guarded by its own {@code IS NOT NULL} and such a row contributes no case. Without those
     * guards {@code i.tag = t.tag} on a null tag would be null rather than false — which is the same
     * three-valued trap §96 found on the suite side, arriving from the other direction.
     */
    private static String scopeMatch() {
        return """
                ( (i.case_id IS NOT NULL AND c.id = i.case_id)
                  OR (i.suite_id IS NOT NULL AND %s)
                  OR (i.tag IS NOT NULL AND EXISTS (SELECT 1 FROM case_tag pt
                                                     WHERE pt.case_id = c.id
                                                       AND pt.tag = i.tag)) )
                """.formatted(memberOf("s").strip());
    }

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
                         AND %s
                        WHERE i.plan_id IN (:planIds)
                        GROUP BY i.plan_id
                        """.formatted(scopeMatch()))
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
                         AND %s
                        WHERE i.plan_id = :planId
                        ORDER BY c.key_seq
                        """.formatted(scopeMatch()))
                .setParameter("projectId", projectId)
                .setParameter("planId", planId)
                .getResultList()
                .stream()
                .map(row -> (UUID) ((Object[]) row)[0])
                .toList();
        return rows;
    }

    /**
     * <strong>The live cases a run's three-armed scope covers</strong> — §99, and the second
     * resolution in this file that answers <em>which cases</em> for a scope somebody wrote.
     *
     * <h2>One query, not three lookups unioned in Java</h2>
     *
     * <p>All three arms are {@code repository} facts, so a service-side union would need the ids of
     * each arm crossing the seam separately — three round trips, three chances for one of them to
     * apply {@code deleted_at} and the others not, and a set whose order is whatever the last
     * concatenation produced. Here the arms are three {@code OR}s over one {@code test_case} scan,
     * so <em>live in this project</em> is written once and the order is the project's.
     *
     * <h2>The suite arm is membership and never {@code suite_id}</h2>
     *
     * <p>{@link #memberOf} — filed, added individually, or matched by the suite's own tag rules.
     * The tempting spelling is {@code c.suite_id IN (:suiteIds)}, and against a suite defined by a
     * tag rule it resolves to <strong>nothing</strong>: a run of zero cases, created successfully,
     * which every count on renders as finished. That is §98's finding — the count and the grid
     * answering different questions — arriving at the one screen where the wrong answer is not a
     * number but an empty checklist.
     *
     * <h2>Empty arms are omitted from the SQL rather than passed as empty lists</h2>
     *
     * <p>{@code IN ()} is a syntax error and Hibernate will not bind an empty collection, so each
     * arm is appended only when it has members and the caller gets an empty list when none do. The
     * ordinary create names one arm; the two it did not name must cost nothing and must not be
     * spelled as {@code IN (NULL)}, which is never true and would read as an arm that matched.
     *
     * <p>Ordered by {@code key_seq} for {@link #caseIdsInPlanScope}'s reason, and here it is also
     * what makes the three arms produce <em>one</em> checklist: a union whose order depended on
     * which arm found a case first would put the same run's rows in a different order every time
     * the scope was written differently.
     */
    List<UUID> caseIdsInRunScope(
            UUID projectId, Collection<UUID> caseIds, Collection<UUID> suiteIds,
            Collection<String> tags) {

        List<String> arms = new ArrayList<>(3);
        if (!caseIds.isEmpty()) {
            arms.add("c.id IN (:caseIds)");
        }
        if (!suiteIds.isEmpty()) {
            arms.add("""
                    EXISTS (SELECT 1 FROM suite s
                             WHERE s.id IN (:suiteIds)
                               AND s.project_id = c.project_id
                               AND s.deleted_at IS NULL
                               AND %s)
                    """.formatted(memberOf("s").strip()));
        }
        if (!tags.isEmpty()) {
            arms.add("EXISTS (SELECT 1 FROM case_tag rt "
                    + "WHERE rt.case_id = c.id AND rt.tag IN (:tags))");
        }
        if (arms.isEmpty()) {
            return List.of();
        }

        var query = entities.createNativeQuery("""
                SELECT DISTINCT c.id, c.key_seq
                FROM test_case c
                WHERE c.project_id = :projectId
                  AND c.deleted_at IS NULL
                  AND (%s)
                ORDER BY c.key_seq
                """.formatted(String.join(" OR ", arms)))
                .setParameter("projectId", projectId);

        if (!caseIds.isEmpty()) {
            query.setParameter("caseIds", caseIds);
        }
        if (!suiteIds.isEmpty()) {
            query.setParameter("suiteIds", suiteIds);
        }
        if (!tags.isEmpty()) {
            query.setParameter("tags", tags);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows.stream().map(row -> (UUID) row[0]).toList();
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
     *
     *                  <p>All three suite members are <strong>null together</strong> for a case
     *                  filed in no suite (§96). The join is a {@code LEFT JOIN} for that reason and
     *                  it is not cosmetic: an inner join drops the case from this list while
     *                  {@link #caseCountsByPlan} — which never joins the case's own suite — still
     *                  counts it, so a plan naming an unfiled case directly would say "40 cases"
     *                  over a list of 39. That is §32's two-numbers defect, arriving through the
     *                  one query of the three that reads the case's own folder.
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
     * {@code PlanStatisticsEndpointTest} asserts the two agree over the same fixture.
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
                         AND %s
                        LEFT JOIN suite cs ON cs.id = c.suite_id
                        WHERE i.plan_id = :planId
                        ORDER BY c.key_seq
                        """.formatted(scopeMatch()))
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
     * How many live cases each of a plan's <strong>sections</strong> covers, keyed on section name.
     *
     * <p>The fourth caller of {@link #scopeMatch()} and the same expansion as the three above, one
     * level down: a section holds the same three kinds of reference the plan does, so its count is
     * the plan's count with {@code section_name} added to the grouping. Deriving it any other way —
     * counting the references, or resolving each arm in Java and adding — is the arithmetic that
     * produces a second number for one question.
     *
     * <p><strong>These counts do not sum to the plan's own, and that is the design.</strong> A case
     * named directly in one section and reached through a suite or a tag in another is one case in
     * the plan and one case in each section, so the parts exceed the whole. The contract says so on
     * {@code PlanSection} and the UI says so beside the number, because a reader who subtracts will
     * otherwise report a bug in a correct total.
     *
     * <p>An <strong>empty</strong> section is absent from the result rather than present as zero:
     * its only row targets nothing, so it produces no {@code GROUP BY} row at all. The caller reads
     * through {@code getOrDefault(name, 0L)}, which is also the honest answer for a section whose
     * every reference has since been deleted.
     *
     * <p>Batched over plans for {@link #caseCountsByPlan}'s reason and not merely to be tidy: the
     * plan <em>list</em> serves every plan's sections, so a per-plan query here would be one round
     * trip per row of a screen that already has one for its counts.
     */
    Map<UUID, Map<String, Long>> caseCountsBySection(UUID projectId, Collection<UUID> planIds) {
        if (planIds.isEmpty()) {
            return Map.of();
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery("""
                        SELECT i.plan_id, i.section_name, COUNT(DISTINCT c.id)
                        FROM plan_scope_item i
                        LEFT JOIN suite s ON s.id = i.suite_id AND s.deleted_at IS NULL
                        JOIN test_case c
                          ON c.project_id = :projectId
                         AND c.deleted_at IS NULL
                         AND %s
                        WHERE i.plan_id IN (:planIds)
                          AND i.section_name IS NOT NULL
                        GROUP BY i.plan_id, i.section_name
                        """.formatted(scopeMatch()))
                .setParameter("projectId", projectId)
                .setParameter("planIds", planIds)
                .getResultList();

        Map<UUID, Map<String, Long>> counts = new HashMap<>();
        for (Object[] row : rows) {
            counts.computeIfAbsent((UUID) row[0], ignored -> new HashMap<>())
                    .put((String) row[1], ((Number) row[2]).longValue());
        }
        return counts;
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
     * Of {@code caseIds}, the display keys of those that are <strong>not</strong> {@code APPROVED} —
     * §6g's gating rule for runs (§82), asked only on a project that turned it on.
     *
     * <h2>Not approved, rather than in some named status</h2>
     *
     * <p>The predicate is {@code <> 'APPROVED'} and not {@code IN ('DRAFT','IN_REVIEW')}, so the
     * fourth value is covered without being mentioned: a {@code DEPRECATED} case is retired content
     * and putting it into a release run is exactly the mistake this rule exists to catch, but an
     * enumeration of the statuses to refuse would have had to remember it — and a fifth status,
     * should the ladder ever grow one, would default to <em>allowed</em>. Refusing everything that
     * is not the one approved state is the reading that cannot silently widen.
     *
     * <p>Read <strong>live</strong>, which is the opposite of {@link #pinnedCases} and correct for
     * the opposite reason: this is a question about whether work may start, not a record of what was
     * agreed, so it must see the case's status as it is at the moment the run is created. A run
     * created a second after a lead approves the last case must succeed.
     *
     * <p>Ordered by {@code key_seq} — the refusal quotes the first few keys and the caller caps the
     * list, so the order has to be the one the case grid and the run's own checklist use, or two
     * screens name different examples of the same problem.
     *
     * @return keys only, and empty when everything is approved — which is the common case on a
     *         gated project and the cheap one to check. A case id naming nothing is simply absent:
     *         it cannot be materialised either, and {@code RunEdit} has already refused the request
     *         for that separately.
     */
    List<String> unapprovedCases(UUID projectId, Collection<UUID> caseIds) {
        if (caseIds.isEmpty()) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<String> keys = entities.createNativeQuery("""
                        SELECT c.key
                        FROM test_case c
                        WHERE c.project_id = :projectId
                          AND c.deleted_at IS NULL
                          AND c.id IN (:caseIds)
                          AND c.lifecycle_status <> 'APPROVED'
                        ORDER BY c.key_seq
                        """)
                .setParameter("projectId", projectId)
                .setParameter("caseIds", caseIds)
                .getResultList();
        return List.copyOf(keys);
    }

    /**
     * <strong>Ladder step 1</strong> — which cases carry these {@code automation_key}s (§6c).
     *
     * <p>The exact-match arm of ingestion, and the only one a team can control from the case side.
     * Keyed on the key <em>as sent</em>: {@code automation_key} is free text that a human copied out
     * of their test source, so lower-casing it here would match {@code OrderTest#cancels} against a
     * stored {@code ordertest#cancels} and silently make two distinct FQCNs one — which is the
     * opposite of what an <em>exact</em> step is for. The forgiving comparison is step 3's, where a
     * person has vouched for the pair.
     *
     * <p><strong>Bounded to the project though {@code test_case_automation_key_idx} is
     * company-unique.</strong> §6c scopes the key to the company so a CI job need not know which
     * project owns the case; this endpoint is project-scoped, and materialising a sibling project's
     * case into this project's run would put a {@code test_execution} and its {@code test_case} in
     * two different projects — a state every read in this module assumes cannot arise. The company
     * scope still buys what it was for: the key is unambiguous, so the project bound can never
     * silently pick the wrong one of two candidates. There is only ever one.
     *
     * <p>Soft-deleted cases are excluded. A deleted case is not somewhere results may land, and
     * including it would revive one as a run member with no way to see why.
     *
     * @return key → case id, for the keys that matched; absent keys are the caller's step 2
     */
    Map<String, UUID> caseIdsByAutomationKey(UUID projectId, Collection<String> keys) {
        return keyedLookup("c.automation_key", projectId, keys);
    }

    /**
     * <strong>Ladder step 2</strong> — which cases these display keys name (§6c's
     * {@code @TC-1042} annotation, extracted from the test name by {@link AutomationMatcher}).
     *
     * <p>{@code test_case.key} is the display key (§6t) and {@code uq_case_key} makes it unique per
     * project, so a token that is not a key of <em>this</em> project matches nothing here. That is
     * what makes the fixed extraction pattern safe where §6c's "configurable regex per project"
     * would not be: a false extraction costs one entry in the {@code IN} list and can never produce
     * a wrong match, because the wrong project's keys are not in this result at all.
     *
     * <p>Separate from {@link #caseIdsByAutomationKey} rather than one method over a column name
     * the caller passes: the two differ in what a miss <em>means</em> and in what the caller does
     * next, and a shared entry point would be one refactor away from a caller choosing the column.
     */
    Map<String, UUID> caseIdsByDisplayKey(UUID projectId, Collection<String> keys) {
        return keyedLookup("c.key", projectId, keys);
    }

    /**
     * The display keys of these cases — what a list of mappings shows beside each id.
     *
     * <p>Not a fourteenth crossing so much as the read half of {@link #unapprovedCases}, which
     * already returns keys: {@code test_case.key} is a {@code repository} fact and this module holds
     * ids into it, so the key can only be resolved here. A mapping list is read to answer "which
     * case does this test point at", and an id is not an answer a person can check against the case
     * grid they are looking at.
     *
     * <p><strong>No {@code deleted_at} predicate</strong>, unlike every other method here. A mapping
     * whose case has since been deleted must still render — it is a row a person has to see in order
     * to delete it — and dropping the key would render it as a blank, which reads as a bug rather
     * than as a stale binding.
     */
    Map<UUID, String> caseKeys(UUID projectId, Collection<UUID> caseIds) {
        if (caseIds.isEmpty()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery("""
                        SELECT c.id, c.key
                        FROM test_case c
                        WHERE c.project_id = :projectId AND c.id IN (:caseIds)
                        """)
                .setParameter("projectId", projectId)
                .setParameter("caseIds", caseIds)
                .getResultList();

        Map<UUID, String> keys = new HashMap<>();
        for (Object[] row : rows) {
            keys.put((UUID) row[0], (String) row[1]);
        }
        return keys;
    }

    /**
     * @param column one of two literals chosen by the two methods above, never a parameter reaching
     *               this class from outside it — {@link #liveIds}' rule, and here it is the
     *               difference between an exact-match step and a display-key step
     */
    private Map<String, UUID> keyedLookup(
            String column, UUID projectId, Collection<String> keys) {

        if (keys.isEmpty()) {
            // IN () is a syntax error and IN (NULL) is never true — §99's trap, and here the empty
            // case is the COMMON one: a report every test of which matched at step 1 reaches step 2
            // with nothing left to ask about.
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery(
                        "SELECT " + column + ", c.id FROM test_case c "
                                + "WHERE c.project_id = :projectId AND c.deleted_at IS NULL "
                                + "AND " + column + " IN (:keys)")
                .setParameter("projectId", projectId)
                .setParameter("keys", keys)
                .getResultList();

        Map<String, UUID> byKey = new HashMap<>();
        for (Object[] row : rows) {
            byKey.put((String) row[0], (UUID) row[1]);
        }
        return byKey;
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

    /**
     * The version one live case is on right now, or empty if the case is not reachable.
     *
     * <p><strong>Two questions in one query, deliberately.</strong> "Which version is current" and
     * "does this case exist in this project" have the same answer shape and the same predicate, and
     * asking them separately would let the history endpoint answer 200-with-zeros for a case id that
     * names nothing — the failure mode where a typo in a URL reads as a case nobody has ever run.
     *
     * <p>The project predicate is the second gate, exactly as on {@link #liveCaseIds}: RLS refuses
     * another company's case and the caller's project grant has already been checked, but a case id
     * from a <em>sibling project in the same company</em> would otherwise resolve through a path
     * naming a project the caller does hold.
     *
     * <p>A soft-deleted case is unreachable, so its history is too. That is the same line
     * {@code repository} draws on the case detail, and a timeline for a case whose detail answers
     * 404 would be a page with no way back to its own subject.
     */
    Optional<Integer> currentVersionNo(UUID projectId, UUID caseId) {
        @SuppressWarnings("unchecked")
        List<Integer> rows = entities.createNativeQuery("""
                        SELECT c.current_version_no
                        FROM test_case c
                        WHERE c.project_id = :projectId
                          AND c.deleted_at IS NULL
                          AND c.id = :caseId
                        """)
                .setParameter("projectId", projectId)
                .setParameter("caseId", caseId)
                .getResultList()
                .stream()
                .map(value -> value == null ? null : ((Number) value).intValue())
                .toList();

        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    /**
     * When each version of a case was authored, by whom, and with what comment.
     *
     * @param authorId nullable, and it is {@code created_by} rather than a join to a person:
     *                 resolving names is {@link ExecutionPeople}'s job, in one batch, and doing it
     *                 in SQL here would put an {@code identity} table into the one query set whose
     *                 whole discipline is naming as few foreign tables as possible.
     */
    record VersionMark(int versionNo, Instant occurredAt, UUID authorId, String changeComment) {
    }

    /**
     * Every authored version of one case, oldest first.
     *
     * <p>Ordered ascending because the marker list is merged into a timeline the client sorts for
     * itself; ascending is the order the versions happened in, which is the one order that is
     * meaningful without further context.
     *
     * <p><strong>An empty list is an ordinary answer, not a missing case.</strong> A case authored
     * before §29 wrote a v1 alongside its case has no version rows at all, and the timeline then
     * shows executions with no markers between them — which is honest. The caller has already
     * established the case is reachable via {@link #currentVersionNo}, so emptiness here cannot be
     * confused with absence.
     */
    List<VersionMark> versionMarkers(UUID caseId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery("""
                        SELECT v.version_no, v.created_at, v.created_by, v.change_comment
                        FROM test_case_version v
                        WHERE v.case_id = :caseId
                        ORDER BY v.version_no
                        """)
                .setParameter("caseId", caseId)
                .getResultList();

        List<VersionMark> marks = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            marks.add(new VersionMark(
                    ((Number) row[0]).intValue(),
                    toInstant(row[1]),
                    (UUID) row[2],
                    (String) row[3]));
        }
        return List.copyOf(marks);
    }

    /**
     * Every case sitting in one suite <strong>or any suite beneath it</strong>, as ids.
     *
     * <p>The subtree, not the folder's own rows, and the contract's own description says why: a
     * folder's history means the folder's, and §6p's rollup rule is to sum leaf counts. Own-cases-only
     * would make every intermediate folder report an empty history — which reads as "never executed"
     * for a subtree that runs nightly. {@code <@} is the {@code ltree} descendant-or-self operator,
     * the same one {@link #caseCountsByPlan} expands a plan's suite items with.
     *
     * <p><strong>Soft-deleted cases are INCLUDED</strong>, which is the opposite of every other
     * method here and is the same rule as {@code findForSuiteCases}' deleted runs. A history entry
     * says what a run covered at the time; dropping a case somebody deleted last week would silently
     * lower the totals of runs that finished before the deletion, and the row a reader is checking
     * against would change under them. Deleted <em>suites</em> are excluded: a folder that no longer
     * exists cannot be the subject of this page, and its cases are reachable through wherever they
     * live now.
     *
     * <p><strong>The scope is the suite, not a subtree, since §98</strong> — the contract says so,
     * and the argument the old shape rested on ("§6p's rollup rule is to sum leaf counts, so a
     * parent's entry covers every case beneath it; the alternative makes every intermediate folder
     * report an empty history") has no subject left: there are no intermediate folders.
     *
     * <p><strong>Membership is the whole union since §96</strong> — filed, added individually, or
     * matched by its own tag rule ({@link #memberOf}). A suite whose members are entirely
     * tag-resolved used to report an empty history, which reads as "never executed" for a suite that
     * runs nightly. {@code DISTINCT} because a case can qualify through two arms.
     *
     * <p><strong>Membership is read as it is now, not as it was.</strong> A case moved between suites
     * takes its whole execution history with it, because {@code test_execution} pins the case
     * <em>version</em> and not the suite. Retagging a case now moves its history the same way, which
     * is the same trade one step wider. §6p's {@code execution_fact} read model stores
     * {@code suite_path} per row for exactly this reason; until that model has a writer, this is the
     * honest approximation and the limitation is stated rather than hidden.
     *
     * <p>Empty is an ordinary answer — an empty folder — and the caller has already established the
     * suite is reachable, so it cannot be confused with absence.
     */
    List<UUID> caseIdsInSuite(UUID projectId, UUID suiteId) {
        @SuppressWarnings("unchecked")
        List<UUID> ids = entities.createNativeQuery("""
                        SELECT DISTINCT c.id
                        FROM suite root
                        JOIN test_case c
                          ON c.project_id = root.project_id
                         AND %s
                        WHERE root.project_id = :projectId
                          AND root.deleted_at IS NULL
                          AND root.id = :suiteId
                        """.formatted(memberOf("root")))
                .setParameter("projectId", projectId)
                .setParameter("suiteId", suiteId)
                .getResultList();
        return List.copyOf(ids);
    }

    /**
     * When each version of one suite was authored, by whom, and why — {@link #versionMarkers} for a
     * suite instead of a case, reading {@code suite_version} instead of {@code test_case_version}.
     *
     * <p>A second method rather than a table parameter, for {@link #liveIds}' inverse reason: those
     * two tables answer the <em>same</em> question with identical columns, while these two differ in
     * what a version <em>means</em> — a case version is content, a suite version is composition
     * ({@code repository.SuiteVersions} says so). Sharing the query would invite a future filter
     * meant for one to be applied to both.
     *
     * <p><strong>Expect at most one mark per suite today, and that is a property of the writer, not
     * of this query.</strong> {@code SuiteVersions} writes v1 at creation and nothing since — a
     * rename is content, and the moves that change composition are not built. So the timeline's
     * version-marker rail is structurally empty until the case-move slice writes a v2. Reading it
     * anyway is §3 lesson 5: the rows are recorded from M1 precisely so the screen that renders them
     * needs no backfill.
     */
    List<VersionMark> suiteVersionMarkers(UUID suiteId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery("""
                        SELECT v.version_no, v.created_at, v.created_by, v.change_comment
                        FROM suite_version v
                        WHERE v.suite_id = :suiteId
                        ORDER BY v.version_no
                        """)
                .setParameter("suiteId", suiteId)
                .getResultList();

        List<VersionMark> marks = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            marks.add(new VersionMark(
                    ((Number) row[0]).intValue(),
                    toInstant(row[1]),
                    (UUID) row[2],
                    (String) row[3]));
        }
        return List.copyOf(marks);
    }

    /**
     * {@code TIMESTAMPTZ} arrives as {@link java.sql.Timestamp} through a native query, unlike the
     * {@link Instant} an entity mapping would hand back — the one place this class pays for reading
     * rows rather than mapping them.
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
