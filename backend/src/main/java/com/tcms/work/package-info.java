/**
 * Work — the kanban board: real tasks, and projections of work that already exists (plan §6l).
 *
 * <h2>The one design decision this module is built around</h2>
 *
 * <p>Two kinds of thing appear on the board and <strong>only one of them is a row</strong>.
 *
 * <ul>
 *   <li>A <strong>task</strong> is first-class. Somebody typed it, it owns its own status, and
 *       {@code work_item} is where it lives.</li>
 *   <li>A <strong>derived item</strong> is a <em>view over something else</em> — an assigned
 *       execution, a case the health computation has flagged. It is never stored, and its status is
 *       computed from its source on every single read.</li>
 * </ul>
 *
 * <p>{@code V1}'s comment on {@code work_item} states the rule from the schema side: "there is
 * deliberately no column here that could let a card status diverge from its execution". The failure
 * being prevented is specific and fatal to the feature — materialise executions as rows carrying
 * their own status, and the moment a tester marks a case Passed in the run, the card sits in "In
 * Progress" forever. Within a week nobody trusts the board, and a board nobody trusts is worse than
 * no board, because it is still on everyone's screen.
 *
 * <p>Everything awkward about this module follows from that one decision. A derived card's id is
 * synthetic ({@code execution:{uuid}}), because it has no row to have an id. {@code PATCH} refuses
 * it with a 409 rather than a 404, because the card is real and only its <em>storage</em> is not.
 * {@code allowedTransitions} is computed per card rather than being a property of the column,
 * because what a card may do is a fact about its source.
 *
 * <h2>Allowed dependencies</h2>
 *
 * <p>{@code tenancy}, {@code project} and {@code identity} — the same three {@code repository} and
 * {@code execution} declare, for the same three reasons: the isolation boundary, the gate
 * ({@link com.tcms.project.ProjectService#readVisible} / {@code #writeGranted}), and display names
 * for assignees. As in both of those modules, {@code identity} is asked <em>what is this person
 * called</em> and never <em>may they</em>.
 *
 * <h2>The two edges this module does not have, and the one class that carries both</h2>
 *
 * <p><strong>{@code execution} and {@code repository} are not on the list and must never be.</strong>
 * And yet the entire derived half of the board is made of their rows: an {@code EXECUTION} card is a
 * {@code test_execution}, a {@code CASE_MAINTENANCE} card is a {@code case_health} row. This is the
 * same tension {@code execution}'s package comment resolves, resolved the same way — a shared row is
 * not a shared class, and what plan §3 forbids is two modules becoming deployable only together.
 *
 * <p>So the whole of both edges lives in exactly one class, {@link com.tcms.work.BoardFacts}, which
 * issues SQL naming those tables and returns records declared here. It is the third instance of a
 * pattern the codebase now has a name for — {@code execution.CaseFacts} reads {@code repository},
 * {@code repository.ExecutionFacts} reads {@code execution}, and this reads both. Nothing else in
 * this package may name a foreign table. Concentrating it is what makes the coupling reviewable, and
 * what makes it one file to rewrite on the day these facts arrive as events instead.
 *
 * <p><strong>And since §81 that concentration is the reason a whole feature lives here.</strong> The
 * reviewer's inbox — who has been asked to review what — needs {@code review} and
 * {@code review_assignment} from {@code project}, {@code team_membership} from {@code identity}, and
 * whichever of {@code test_case} / {@code suite} / {@code test_plan} the review's opaque subject
 * triple names. <em>No other module may write that query.</em> {@code project}, which owns the
 * review record, may depend on {@code tenancy} alone; {@code ReviewController} says so in its own
 * header when it refuses to serve the queue. This module may see all three, so the board is not
 * merely a convenient place for the inbox — it is the only one that does not need a fourth port, a
 * new read model or the domain event §76 declined to invent.
 *
 * <h2>What is deliberately not built</h2>
 *
 * <p>§6l lists five derived-item generators. <strong>Three exist here since §81</strong> —
 * {@code REVIEW} landed once §76–§80 had built the workflow and, in {@code review_assignment}, the
 * one table that records who was <em>asked</em>. {@code DOC_UPDATE} needs §6i's stale-case flagging,
 * which the documentation module does not compute, and {@code DEFECT} needs the local defect record,
 * which nothing writes. They are <em>absent</em> rather than returning empty: a generator that
 * always yields nothing is indistinguishable from a broken one, and the contract's {@code kind} enum
 * keeps all six so the SPA's labels stay complete for the day they land — which is exactly what
 * {@code REVIEW} then needed, no contract change at all.
 *
 * <p>Also not built, and named because the inbox makes it matter more: <strong>per-project
 * generator toggles</strong>. §6l says each generator is individually toggleable and that all of
 * them on by default would bury the board; three are on unconditionally. The review one is the first
 * that a project can effectively turn off by other means — a project with review switched off (§77)
 * opens no reviews and therefore has no review cards — which is the argument for leaving the toggles
 * until a generator exists that a project cannot escape.
 *
 * <p>Also not built: WIP limits, configurable columns, swimlanes, team boards and the cross-project
 * personal board. The columns are the four §6l names as a fixed enum
 * ({@code WorkItemStatus} in {@code openapi.yaml}), which is what makes per-board configuration a
 * later change to <em>configuration</em> rather than to the contract.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"tenancy", "project", "identity"})
package com.tcms.work;
