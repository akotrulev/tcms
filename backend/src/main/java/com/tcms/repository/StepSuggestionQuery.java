package com.tcms.repository;

import java.util.Locale;

/**
 * One step-suggestion request, parsed — the single query parameter the contract defines for
 * {@code GET /projects/{projectId}/step-suggestions}, normalized once at the edge.
 *
 * <p>A record between the controller and the service on {@link ConditionQuery}'s terms: the service
 * never sees a raw {@code String} it has to decide how to trim, and the awkward reading below —
 * what a blank {@code q} means — has exactly one home.
 *
 * <p><strong>This is the fourth copy of {@code escapeLike} in this module</strong>
 * ({@link ConditionQuery}, {@code CaseSpecifications}, here). It is
 * duplicated rather than extracted because the convention this module already follows is one query
 * record per endpoint, and a shared helper is a change to three shipped call sites that this slice
 * has no reason to touch. Recorded so the fifth copy is a deliberate decision to extract rather than
 * another shrug.
 *
 * @param q a fragment of the step's action for the typeahead, or null for "everything". Blank is
 *          normalized to null: {@code ?q=} arrives when the editor's field is cleared, and reading
 *          it as a literal empty search would be reading "show me the most-used steps" as "show me
 *          the steps whose action contains nothing" — the same set by luck rather than by agreement
 */
public record StepSuggestionQuery(String q) {

    public StepSuggestionQuery {
        q = blankToNull(q);
    }

    /**
     * The bound {@code LIKE} pattern — {@code '%'} when there is no query.
     *
     * <p>Escaped so an author typing a literal {@code %} or {@code _} searches for that character
     * rather than silently matching everything, and lower-cased to be compared against
     * {@code LOWER(action)}. Both are {@link ConditionQuery#needle()}'s reasoning verbatim, and the
     * lower-casing is what {@code V30}'s index is built on the {@code lower(action)} expression to
     * serve — spelling one without the other leaves a maintained index the query never consults.
     */
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
