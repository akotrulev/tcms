package com.tcms.tracker.imports;

/**
 * The preview's headline counts — the contract's {@code ImportPreviewTotals}.
 *
 * @param issues         what will be created
 * @param labels         distinct label names across the project
 * @param comments       across every issue
 * @param historyEntries <strong>counted and not imported.</strong> There is no table here for
 *                       somebody else's audit trail, and a number said out loud is better than an
 *                       absence discovered later.
 * @param fixVersions    each becomes a release (D6)
 * @param sprintValues   dropped, or kept as one label per sprint (D-h)
 */
public record ImportPreviewTotalsView(
        int issues,
        int labels,
        int comments,
        int historyEntries,
        int fixVersions,
        int sprintValues) {
}
