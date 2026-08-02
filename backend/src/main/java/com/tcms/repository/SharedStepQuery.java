package com.tcms.repository;

import java.util.Locale;

/**
 * One step-library request, parsed — the single query parameter the contract defines for
 * {@code GET /projects/{projectId}/shared-steps}, normalized once at the edge.
 *
 * <p>The {@code q} half of {@link ConditionQuery}, and deliberately a second record rather than a
 * shared one: they are two endpoints' inputs that happen to agree on one field today. Folding them
 * together would mean the step library carries a {@code type} it has no notion of, and the first
 * parameter either one gains would have to be explained as absent from the other.
 *
 * <p>The escaping and blank-handling rules are the ones {@code ConditionQuery} states at length —
 * the same reasoning applies unchanged, so it is not restated here.
 *
 * @param q a title fragment for the typeahead, or null for "everything". Blank normalizes to null:
 *          {@code ?q=} is what arrives when the search box is cleared
 */
public record SharedStepQuery(String q) {

    public SharedStepQuery {
        q = blankToNull(q);
    }

    /** The bound {@code LIKE} pattern — {@code '%'} when there is no query, escaped and lowered. */
    String needle() {
        if (q == null) {
            return "%";
        }
        return "%" + escapeLike(q.toLowerCase(Locale.ROOT)) + "%";
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
