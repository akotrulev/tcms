/**
 * Reporting — the read models every report is computed from, and later the reports themselves
 * (plan §6e, ERD §8).
 *
 * <p>The module has been named in plan §3, in ERD §0.2 and in {@code CLAUDE.md} since M0 and did not
 * exist until §86. {@code ModuleStructureTest} hard-coded the nine that did and said in its own
 * rationale that adding one "must be a deliberate edit, so that whoever adds one also reads the
 * {@code allowedDependencies} rule they are now subject to". This is that edit.
 *
 * <h2>Why the module had to exist before anything could be written</h2>
 *
 * <p>{@code execution_fact} is in {@code V1} with five purpose-built indexes and has been empty
 * since M0. Filling it means one statement that names {@code test_execution} and {@code test_run}
 * ({@code execution}), {@code test_case} and {@code suite} ({@code repository}) and
 * {@code case_status} ({@code project}) — <strong>three modules, and no existing module may write
 * it</strong>. {@code repository} may not see {@code test_run}; {@code execution} may not see
 * {@code suite}; {@code work} is the board's module and has no business filing a report. That is
 * §81's argument for concentrating a cross-module read where no other module may write the query,
 * arriving at a module that did not exist yet: create it, and give it one seam file.
 *
 * <p>Putting the statement in {@code repository} beside {@link com.tcms.repository.ExecutionFacts}
 * was the tempting shortcut and is worse than it looks: it would make that file the second place
 * {@code repository} writes another module's read model, and it would give the repository
 * dashboard's module ownership of the release report's data.
 *
 * <h2>What stays where it is</h2>
 *
 * <p>{@code repository.DashboardController}'s header already argued that the repository status
 * dashboard did <em>not</em> wait for this module — "a {@code reporting} module would mean that
 * module reading this one's tables — the cross-module edge ArchUnit refuses — to compute numbers
 * this module already knows how to count." That reasoning is still correct and this module does not
 * overturn it. The dashboard stays in {@code repository}, and so do the two health signals
 * {@code repository.ExecutionFacts} owns.
 *
 * <h2>Allowed dependencies</h2>
 *
 * <p><strong>{@code tenancy · project · identity}</strong> — the same three {@code repository},
 * {@code execution} and {@code work} declare — <strong>plus {@code storage} since §125</strong>,
 * which is decision round 3 #51 and is the fourth entry the rule below demands a reason for.
 * {@code ReportJobRunner} writes a rendered report's bytes and {@code ReportReadService} serves
 * them; the port that owns them moved out of this package into a module of its own, together with
 * {@code execution}'s identical one. The edge is inward-only and stays that way because
 * {@code storage} depends on {@code tenancy} alone — it cannot see a job, a person or a project,
 * so nothing about <em>which</em> report a caller may download can drift into it. The bytes-first
 * ordering this module deliberately inverts is argued in {@code ReportJobRunner} and stated in the
 * port's header; the port itself has no opinion, which is what lets two callers disagree.
 *
 * <p>It was {@code tenancy} alone from §86 until §87, and
 * the history is the point rather than a footnote: nothing in R1/R2 was reached through an endpoint,
 * so there was no project gate to ask for and no person to name, and declaring the three this module
 * <em>would eventually want</em> would have made the declaration a forecast rather than a fact — a
 * forecast cannot fail the build when it stops being true. {@code documentation} is the precedent in
 * both directions: it declared {@code tenancy · project} and its package comment said "nothing in it
 * renders a person" until §61's artifact register made that false, at which point the list and the
 * comment were corrected together.
 *
 * <p>§86 named exactly what would widen it and §87's report pipeline is that thing, arriving with
 * both halves at once: {@code project}, because {@code report:view} / {@code report:export} are
 * resolved through {@link com.tcms.project.ProjectService} on all three report endpoints, and
 * {@code identity}, because {@code report_job.requested_by} is a person a rendered report names.
 * The rule survives the widening — the next entry added here must be a fact about code that exists
 * in the same commit.
 *
 * <p>Everything else crosses through {@link com.tcms.reporting.ExecutionSource} — the one class in
 * this package that may name a table another module owns, in the shape
 * {@code execution.CaseFacts} / {@code repository.ExecutionFacts} / {@code work.BoardFacts} /
 * {@code documentation.PlanFacts} established. A second class here naming {@code test_execution}
 * ends that property, and it ends it for the most tempting reason there is — that the new query is
 * short.
 *
 * <h2>What is deliberately not here yet</h2>
 *
 * <p>~~{@code suite_run_summary} and {@code plan_snapshot} are {@code V1} tables with no code.~~
 * <strong>→ superseded 2026-08-06 (§101):</strong> both have writers now
 * ({@link com.tcms.reporting.RollupRefreshService}), and {@code execution} reads them back through
 * its own {@code RollupFacts} seam. They are the last two tables this section named. Note the
 * asymmetry recorded there, because they look like siblings and are not: the suite rollup is
 * <em>history</em> and freezes its membership, the plan snapshot is <em>current state</em> and needs
 * a delete pass. {@code report_definition} has an entity and no writer — saved definitions are R5, so every job in
 * R3 is ad-hoc and {@code definition_id} is null on every row this module writes. §6e's catalogue
 * names ten reports and {@link com.tcms.reporting.RunSummaryReport} is the first;
 * {@code docs/plans/2026-08-04-m2.5-reporting-slice-plan.md} decomposes the rest into R4–R8, of
 * which R4's HTML renderer is the one the format registry is already shaped for.
 *
 * <p><strong>Naming trap, live.</strong> {@code com.tcms.execution.PlanSnapshot} is §74's
 * plan-version value object and has nothing whatever to do with the {@code plan_snapshot} table in
 * this section. A writer for that table must not be called {@code PlanSnapshot} — §101's is
 * {@link com.tcms.reporting.RollupRefreshService}, and the trap was live when it was written.
 *
 * <h2>The tables this module writes are read back by {@code execution}, and that is not a cycle</h2>
 *
 * <p>Since §101 the direction runs both ways at the <em>table</em> level: this module builds
 * {@code execution_fact}, {@code suite_run_summary} and {@code plan_snapshot} by reading
 * {@code execution}'s tables through {@link com.tcms.reporting.ExecutionSource}, and
 * {@code execution.RollupFacts} then reads those three back. At the Java level there is no
 * dependency in either direction — no import, no bean, no call — which is the level ArchUnit and
 * Spring Modulith check and the only level at which a compile can break.
 *
 * <p>The alternative was moving the suite-history and plan-statistics endpoints into this module,
 * which would have made a screen's module depend on which storage strategy it happened to use.
 * <strong>The cost is that nothing checks the two sides still agree about a column</strong>, and it
 * is closed by {@code RollupAgreementTest} rather than by this paragraph.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"api", "tenancy", "project", "identity", "storage"})
package com.tcms.reporting;
