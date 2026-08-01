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
 * <h2>What is deliberately not built</h2>
 *
 * <p>§6l lists five derived-item generators. Two exist here. {@code REVIEW} needs the review
 * workflow (§6g), {@code DOC_UPDATE} needs the documentation space (§6i), and {@code DEFECT} needs
 * the local defect record — none of those modules exist. They are <em>absent</em> rather than
 * returning empty: a generator that always yields nothing is indistinguishable from a broken one,
 * and the contract's {@code kind} enum keeps all six so the SPA's labels stay complete for the day
 * they land.
 *
 * <p>Also not built: WIP limits, configurable columns, swimlanes, team boards and the cross-project
 * personal board. The columns are the four §6l names as a fixed enum
 * ({@code WorkItemStatus} in {@code openapi.yaml}), which is what makes per-board configuration a
 * later change to <em>configuration</em> rather than to the contract.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"tenancy", "project", "identity"})
package com.tcms.work;
