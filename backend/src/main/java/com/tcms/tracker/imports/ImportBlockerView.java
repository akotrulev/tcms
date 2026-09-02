package com.tcms.tracker.imports;

import java.util.List;

/**
 * A reason the import cannot proceed — the contract's {@code ImportBlocker}.
 *
 * <p>{@code blockers} being non-empty is what disables the wizard's Continue, and the server does
 * not trust that: {@code ImportRunService} recomputes every one of these before it writes (T4).
 * This is the early, readable half of a refusal the database would make anyway.
 *
 * @param detail the sentence the screen shows, composed where the blocker is found so it can carry
 *               the count or the number that makes it actionable
 * @param keys   for {@link ImportBlockerKind#KEY_COLLISION} — <strong>up to ten</strong>, because a
 *               project colliding on four hundred keys does not need four hundred rows to make the
 *               point, and empty for every other kind
 */
public record ImportBlockerView(ImportBlockerKind kind, String detail, List<String> keys) {

    /** The two-thirds of the kinds that name nothing. */
    static ImportBlockerView of(ImportBlockerKind kind, String detail) {
        return new ImportBlockerView(kind, detail, List.of());
    }
}
