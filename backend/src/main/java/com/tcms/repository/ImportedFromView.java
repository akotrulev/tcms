package com.tcms.repository;

/**
 * {@code ImportedFrom} in {@code docs/specs/openapi.yaml} — where an imported case came from.
 *
 * <p><strong>Provenance, not a locator.</strong> The file lives in somebody's automation
 * repository and this instance never held it, so nothing resolves this pair to content. It exists
 * so a reader can answer <em>"why does this case say that"</em> by opening the right file at the
 * right line, and it is stale the moment that file is edited — which is why the import records
 * {@code imported_source} beside it rather than trusting a line number on its own.
 *
 * @param line 1-based, matching every editor a person will open the file in. A 0-based line here
 *             would send every reader one step off, in the one field whose whole job is to point.
 */
public record ImportedFromView(String path, int line) {

    /**
     * The pair, or null when this case was not imported.
     *
     * <p>Both columns are written together by the import and by nothing else, so a case with one
     * and not the other is not a state the product produces. This answers null unless <em>both</em>
     * are present anyway: a half-populated row would otherwise render as a chip pointing at line 0
     * of an unnamed file, which reads as a fact rather than as missing data.
     */
    static ImportedFromView of(TestCase row) {
        return row.getImportedPath() == null || row.getImportedLine() == null
                ? null
                : new ImportedFromView(row.getImportedPath(), row.getImportedLine());
    }
}
