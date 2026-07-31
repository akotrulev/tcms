package com.tcms.repository;

import java.util.Collection;
import java.util.Locale;

/**
 * One condition-library request, parsed — the two query parameters the contract defines for
 * {@code GET /projects/{projectId}/conditions}, normalized once at the edge.
 *
 * <p>A record between the controller and the service for the reason {@link CaseQuery} is one: the
 * service never sees a raw {@code String} it has to decide how to trim, and the two awkward
 * readings below — what a blank {@code q} means, and what an absent {@code type} means — have
 * exactly one home each.
 *
 * @param q    a title fragment for the typeahead, or null for "everything". Blank is normalized to
 *             null: {@code ?q=} arrives when the SPA's search box is cleared, and reading it as a
 *             literal empty search would be reading "show me all of them" as "show me the ones
 *             whose title contains nothing", which is the same set by luck rather than by
 *             agreement
 * @param type the role the caller intends to attach the condition in, or null for no opinion —
 *             resolved through {@link ConditionType#matching}, which is where {@code BOTH} is
 *             folded in
 */
public record ConditionQuery(String q, ConditionType type) {

    public ConditionQuery {
        q = blankToNull(q);
    }

    /** The stored types this request matches. Never empty — see {@link ConditionType#matching}. */
    Collection<ConditionType> types() {
        return ConditionType.matching(type);
    }

    /**
     * The bound {@code LIKE} pattern — {@code '%'} when there is no query.
     *
     * <p>Escaped so a user typing a literal {@code %} or {@code _} searches for that character
     * rather than silently matching everything. On a keystroke that is the difference between "no
     * results" and "all results", which reads as the search being broken rather than as a
     * character being special — the identical treatment {@link CaseSpecifications} gives the case
     * list's free text, and the one its {@code q=100%} check pins.
     *
     * <p>Lower-cased here and compared against {@code LOWER(title)}, so the match is
     * case-insensitive: a typeahead that refuses "log" for "Log in" is a typeahead nobody uses.
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
