package com.tcms.repository;

/**
 * One suggestion for the step editor's action field — a step the project already uses, and how
 * widely.
 *
 * <h2>The whole triple, not just the action</h2>
 *
 * <p>Picking a suggestion fills {@code action}, {@code testData} <em>and</em> {@code expectedResult}
 * in one go. That is what makes this an autocomplete rather than a text hint: the reason the same
 * step gets retyped twenty times is not that the sentence is hard to remember, it is that the data
 * and the expected result that go with it are. Completing only the action would leave the two fields
 * that actually differ between two similar steps blank, and the author would fill them in from
 * memory — which is the divergence this endpoint exists to stop.
 *
 * <p>It is also why the grouping key is the triple. Two steps reading "Submit the refund form" with
 * different expected results are two different suggestions, and collapsing them on the action alone
 * would offer one of the two expected results arbitrarily.
 *
 * <h2>This is a projection, and there is no entity behind it</h2>
 *
 * <p>Unlike {@link ConditionView} there is no {@code of(entity)} factory,
 * because there is no row to map: the record is the target of the constructor expression in
 * {@link TestStepRepository#findSuggestions} and is built by Hibernate directly from the
 * {@code GROUP BY}. A second identical record to separate "projection" from "view" would be
 * ceremony over one shape with one consumer.
 *
 * <h2>Nothing here links back to a step</h2>
 *
 * <p>Deliberately no id. A suggestion is <strong>text to copy, not an entity to reference</strong> —
 * the step the author ends up with belongs to their case and nothing else, and editing it later
 * changes their case alone. That is the decision that separates steps from conditions in this
 * product: a condition is attached by id and an edit to it propagates everywhere, a step is copied
 * and an edit to it propagates nowhere. Handing out an id here would be the first half of a linkage
 * nobody asked for, and the field would be consumed before anyone reread this paragraph.
 *
 * @param useCount distinct <em>live</em> cases using this exact triple — the ranking signal, and the
 *                 reason the list opens on what the project actually does rather than on what
 *                 somebody wrote yesterday. Distinct cases and not step rows, per §6e: a case that
 *                 repeats a step twice is one case using it, and counting rows would let a single
 *                 loop-heavy case out-rank a step forty cases share.
 */
public record StepSuggestionView(
        String action, String testData, String expectedResult, long useCount) {}
