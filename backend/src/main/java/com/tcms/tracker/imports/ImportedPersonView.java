package com.tcms.tracker.imports;

import java.util.UUID;

/**
 * A source author and what became of them — {@code import-report.html}'s <em>People matched</em>
 * panel.
 *
 * <p>{@code matched} false means their comments carry the source's own text on
 * {@code issue_comment.external_author} rather than being reassigned to somebody who did not write
 * them (D-j). Both halves are reported: a report that listed only the matches would leave a reader
 * unable to tell "everybody was found" from "we did not look".
 *
 * @param userName captured at run time for {@link AppliedStatusMappingView}'s reason — a user can
 *                 be deactivated, and the name is what the report has to be able to say
 */
public record ImportedPersonView(
        String sourceName, boolean matched, UUID userId, String userName) {

    /** Nobody here answers to that address. */
    static ImportedPersonView unmatched(String sourceName) {
        return new ImportedPersonView(sourceName, false, null, null);
    }
}
