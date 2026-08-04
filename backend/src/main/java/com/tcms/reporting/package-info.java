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
 * <p><strong>{@code tenancy} alone.</strong> Narrower than {@code repository}, {@code execution} and
 * {@code work}, which all declare {@code tenancy · project · identity}, and narrower than this
 * module will stay: nothing here is reached through an endpoint yet, so there is no project gate to
 * ask for and no person to name. {@code documentation} is the precedent for declaring the true set
 * and widening it deliberately — it declared {@code tenancy · project} and its package comment said
 * "nothing in it renders a person" until §61's artifact register made that false, at which point the
 * list and the comment were corrected together. The alternative — declaring the three this module
 * will eventually want — makes the declaration a forecast rather than a fact, and a forecast cannot
 * fail the build when it stops being true.
 *
 * <p>So the widening is named rather than pre-approved: <strong>the first endpoint here needs
 * {@code project}</strong>, because {@code report:view} / {@code report:export} are resolved through
 * {@link com.tcms.project.ProjectService}, and rendering who generated a report needs
 * {@code identity} through {@code project.PeopleDirectory}. Whoever lands that widens this line and
 * this paragraph in the same commit.
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
 * <p>{@code suite_run_summary}, {@code plan_snapshot}, {@code report_definition} and
 * {@code report_artifact} are all {@code V1} tables with no code. So is the whole {@code ReportModel}
 * pipeline — the job record, the renderers, the storage port — which
 * {@code docs/plans/2026-08-04-m2.5-reporting-slice-plan.md} decomposes into R3–R8.
 *
 * <p><strong>Naming trap, live.</strong> {@code com.tcms.execution.PlanSnapshot} is §74's
 * plan-version value object and has nothing whatever to do with the {@code plan_snapshot} table in
 * this section. A writer for that table must not be called {@code PlanSnapshot}.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"tenancy"})
package com.tcms.reporting;
