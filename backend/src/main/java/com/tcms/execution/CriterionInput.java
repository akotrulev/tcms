package com.tcms.execution;

/**
 * {@code CriterionInput} on the wire — one entry or exit gate, as authored.
 *
 * <h2>A separate schema from {@link CriterionView}, and the two fields it drops are the reason</h2>
 *
 * <p>{@code Criterion} carries {@code met} and {@code actual}. Both are <strong>derived</strong>:
 * {@link PlanCriteria#evaluate} computes them from the statistics endpoint's own numbers, on the
 * response, every time. Accepting them here would let a client <em>assert</em> that a release gate
 * was met — which is the one claim on this object nobody should be able to make by typing it — and
 * the value would then be overwritten by the next read anyway, so it is a field that is both
 * dangerous and pointless to store. §64's {@code usageCount} refusing to be a column, on a smaller
 * scale and with a sharper edge.
 *
 * <p>They are <em>ignored</em> rather than refused when a client echoes back the object it was
 * served, which is the one place {@code repository.CaseUpdateRequest}'s "refuse loudly rather than
 * accept-and-drop" rule does not apply: that rule protects <strong>authored</strong> content a user
 * typed and watched vanish. {@code met} is a number this server computed and sent; dropping the
 * client's copy of the server's own answer discards nobody's work, and refusing it would break the
 * natural GET-edit-PATCH round trip for no gain.
 *
 * @param metric what is being gated — validated against {@link PlanCriteria#METRICS}, so a typo
 *               cannot become a criterion that is silently never evaluated
 * @param op     the comparison — validated against the six {@link PlanCriteria} can actually apply
 * @param value  the threshold. Boxed, so an omitted value is a field error rather than
 *               <strong>{@code 0}</strong> — "pass_rate >= 0" is a gate that is always met, authored
 *               by nobody, and indistinguishable on screen from one somebody meant
 * @param label  optional prose. Absent is normal and {@link PlanCriteria#parse} already derives one
 *               ("pass_rate >= 95"), so this exists for the plans whose gate has a name the
 *               predicate does not carry — "Sign-off from Support"
 */
public record CriterionInput(String metric, String op, Double value, String label) {
}
