/**
 * Execution — plans, runs, and the case × run instance a result is recorded against (ERD §5).
 *
 * <p>A <em>plan</em> is a scope and a set of criteria; a <em>run</em> is one pass over that scope
 * in one configuration; an <em>execution</em> is one case inside one run. The last is where the
 * product's only irreversible statement lives — "this was tested, and this is what happened" — and
 * everything below exists to keep that statement true afterwards.
 *
 * <h2>Allowed dependencies</h2>
 *
 * <p>{@code tenancy}, {@code project} and {@code identity}, the same three {@code repository}
 * declares, and for the same three reasons: the isolation boundary, the gate
 * ({@link com.tcms.project.ProjectService#readVisible} / {@code #writeGranted}), and display names
 * for the people on an execution. As in {@code repository}, {@code identity} is asked <em>what is
 * this person called</em> and never <em>may they</em> — a permission check appearing anywhere in
 * this package is a defect regardless of what the dependency list permits.
 *
 * <h2>The edge this module does NOT have, and the one the schema gives it anyway</h2>
 *
 * <p><strong>{@code repository} is not on the list and must never be.</strong> Nothing here imports
 * {@code TestCase}, {@code TestStep}, {@code Suite} or any of their services; ArchUnit fails the
 * build if it does.
 *
 * <p>And yet an execution is meaningless without a case. The reconciliation is in {@code V1}, which
 * gives {@code test_execution} two foreign keys into this module's forbidden neighbour —
 * {@code case_id} and, more importantly, {@code case_version_id}, <em>the pin</em> — and gives
 * {@code plan_scope_item} two more. ERD §5's own diagram draws them
 * ({@code TEST_CASE_VERSION ||--o{ TEST_EXECUTION : "pinned by"}). So the schema deliberately
 * models this module as <em>holding ids into the repository</em>, while §0.2's rule forbids the
 * <em>code</em> edge. Both are true and they are about different things: a shared row is not a
 * shared class, and what §0.2 exists to prevent is two modules becoming deployable only together.
 *
 * <p>The whole of that edge therefore lives in exactly one class, {@link com.tcms.execution.CaseFacts},
 * which issues SQL naming a {@code repository} table and returns records declared here. Nothing
 * else in this package may name one of those tables. Concentrating it is what makes the coupling
 * reviewable — and what makes it a single file to rewrite on the day these facts arrive as events
 * instead.
 *
 * <h2>There are TWO seams since §101, one per foreign module</h2>
 *
 * <p>{@link com.tcms.execution.RollupFacts} is the second, and it names {@code reporting}'s three
 * read models — {@code execution_fact}, {@code suite_run_summary}, {@code plan_snapshot} — so the
 * suite-history and plan-statistics screens can read pre-aggregated counts instead of scanning every
 * execution per request.
 *
 * <p>Two classes rather than one, which departs from {@code work.BoardFacts}' precedent of a single
 * seam naming two foreign modules, and it is deliberate: {@code CaseFacts} is nine hundred lines
 * whose header is an argument about the <em>repository</em> boundary specifically. Adding a rollup
 * reader would put two unrelated boundaries in one file with nothing distinguishing them, and the
 * next person would have to read both arguments to change either. The rule that matters is
 * unchanged and is per-module: <strong>one seam per foreign module, and nothing else in this package
 * may name that module's tables.</strong>
 *
 * <p>The {@code reporting} edge runs both ways at the table level — that module builds those three
 * tables by reading this one's — and in neither direction at the Java level, which is the level
 * ArchUnit and Modulith check. {@code RollupAgreementTest} is what holds the two sides to each
 * other, since no compiler does.
 *
 * <h2>The pin is read through the snapshot, and that is the design rather than a shortcut</h2>
 *
 * <p>{@link com.tcms.execution.CaseFacts} does not read the live case. It reads
 * {@code test_case_version.snapshot} — the resolved case exactly as {@code GET /cases/{caseId}}
 * served it at the moment that version was authored, steps and inherited conditions already walked
 * ({@code repository.CaseVersions} says so in its own header).
 *
 * <p>That is what the execution screen is required to show (plan §6j): <strong>the case as it was,
 * not as it is now</strong>. Reading the live rows would be both a wider cross-module edge and the
 * wrong answer — a shared condition edited today would silently rewrite what a tester was told to
 * do last March, in a run that has already been reported on. So the narrower coupling and the
 * correct semantics are the same choice, which is the only reason this seam is comfortable.
 *
 * <p>The cost, stated: the snapshot's shape is {@code repository}'s to change, and no compiler
 * checks that this module still reads it correctly. {@code ExecutionEndpointTest} closes that by
 * creating a case <em>through the real {@code POST /cases}</em> and asserting its steps arrive
 * through the execution endpoint — so the coupling is machine-checked end to end rather than
 * asserted in this comment.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"tenancy", "project", "identity"})
package com.tcms.execution;
