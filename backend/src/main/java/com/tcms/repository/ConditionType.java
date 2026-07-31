package com.tcms.repository;

import java.util.List;

/**
 * What role a library condition plays — {@code Condition.type} in {@code docs/specs/openapi.yaml},
 * and the {@code type} check constraint on {@code condition} in {@code V1}.
 *
 * <p>A <em>precondition</em> is setup that must hold before the case runs; a <em>postcondition</em>
 * is the cleanup that must hold after it. {@link #BOTH} is neither a third role nor "unknown" — it
 * is one entry an author declared usable in either position, which "reset the test payment data"
 * genuinely is.
 *
 * <p>Distinct from {@code suite_condition.kind}, which records the role an <em>attachment</em>
 * plays. A BOTH library entry attached as a precondition is a PRE attachment; the library says
 * what it may be used for, the attachment says what it was used as.
 */
public enum ConditionType {

    PRE,
    POST,
    BOTH;

    /**
     * The stored types a request for {@code requested} should match.
     *
     * <h2>{@code PRE} matches {@code BOTH}, and that is the whole reason this method exists</h2>
     *
     * <p>The filter asks "what may I attach <em>as</em> a precondition", not "what is filed under
     * the literal string PRE". Under an exact match a {@code BOTH} entry appears in no picker at
     * all — not the precondition one, not the postcondition one — so marking a condition reusable
     * in both roles would be the one way to make it unreachable in either. A value that hides the
     * row it describes is not a filter, and the SPA sends nothing but {@code type=PRE} today.
     *
     * <p>{@code BOTH} itself resolves to {@code BOTH} alone, on the same reading: it asks for the
     * entries usable in either position, which is exactly the ones declared that way.
     *
     * <p><strong>The SPA's mock matches the type exactly</strong> ({@code handlers.ts}) and its
     * fixtures contain no {@code BOTH} row, so the difference is invisible there. It is recorded
     * here rather than mirrored, because mirroring it would mean copying a defect for symmetry.
     *
     * @param requested the query parameter, or null when the caller expressed no opinion — in
     *                  which case every type matches. Never an empty set: an empty collection
     *                  bound into SQL {@code IN ()} is a syntax error, and "no filter" is the
     *                  reading an absent parameter has to be given anyway.
     */
    static List<ConditionType> matching(ConditionType requested) {
        if (requested == null) {
            return List.of(values());
        }
        return switch (requested) {
            case PRE -> List.of(PRE, BOTH);
            case POST -> List.of(POST, BOTH);
            case BOTH -> List.of(BOTH);
        };
    }
}
