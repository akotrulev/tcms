package com.tcms.repository;

/**
 * One health signal raised on one case, with how loudly it reads — {@code RaisedSignal} in
 * {@code docs/specs/openapi.yaml}, and the item type of {@code TestCaseSummary.healthSignals}.
 *
 * <h2>Why the severity travels with the signal</h2>
 *
 * <p>Because the alternative was a second copy of it, and the copy drifted. {@link HealthSignal}
 * carries {@code severity()} beside the label and the attribute, and its own header argues that
 * putting it there means "adding a tenth signal is one line here and nothing anywhere else". That
 * claim was false for as long as the chip existed: {@code StatusChip.tsx} held a hand-written
 * {@code SEVERE_SIGNALS} list, its comment said out loud that it was a second copy of this enum and
 * that "if a signal ever renders here with a severity beside it, this list should go rather than be
 * kept in step" — and by §178 <strong>the two disagreed about two of the ten members</strong>. The
 * list had {@code FAILING_REPEATEDLY} (WARNING here) and lacked {@link HealthSignal#CONDITION_DRIFT}
 * (ERROR here), so one case read red on a dashboard tile and amber on its own chip, and no gate on
 * either side could see it. A signal is now never on the wire without its severity, both read from
 * one enum in one expression, so the two cannot be assembled inconsistently.
 *
 * <p><strong>Per case rather than once as a catalogue</strong>, which is a real cost — a page of two
 * hundred cases repeats a value that is a property of the enum. It is paid on purpose: a chip
 * renders where its row is, and a catalogue fetched separately makes a colour depend on a second
 * request completing, with a loading state for a border colour. The wire already works this way one
 * screen over ({@code DashboardTile} carries its own severity per tile) and the bytes are a short
 * enum name.
 *
 * <h2>What it deliberately does not carry</h2>
 *
 * <p><strong>No label.</strong> The SPA labels enum values from its own catalogue, and that is a
 * decision this codebase has already made and written down — {@code CaseService.facets} sends every
 * facet value's enum constant as both id and label precisely so that a second set of words does not
 * exist here to go stale. Severity is not words: it is a judgement about how wrong a case is, made
 * where the derivations are. The two properties therefore go in opposite directions, and each has an
 * argument in this repository already.
 *
 * <p>(That leaves {@link HealthSignal#label} on the wire through {@code DashboardTile.label}, which
 * <em>is</em> the second set of words {@code CaseService.facets} refuses — and six of the ten
 * disagree with {@code lib/labels.ts}. §179 measured it and did not take it; the dashboard's tiles
 * are not all signals, so it is a change to how a tile is built rather than a member being dropped.)
 *
 * @param signal which signal — the same closed set the {@code healthSignal} query parameter takes,
 *              so a chip's identity is the value that filters the grid to it
 * @param severity how loudly it reads. Never computed here: {@link HealthSignal#severity()} is the
 *              one declaration, and this record only carries it outward.
 */
public record RaisedSignalView(HealthSignal signal, HealthSignal.Severity severity) {

    /** The signal and its severity, read off the enum so the pair cannot disagree. */
    static RaisedSignalView of(HealthSignal signal) {
        return new RaisedSignalView(signal, signal.severity());
    }
}
