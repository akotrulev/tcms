package com.tcms.execution;

/**
 * {@code UnmatchedTest} in {@code docs/specs/openapi.yaml} — a test the ladder could not place.
 *
 * <p><strong>This is the whole of what makes an unmatched test actionable, and it is why nothing is
 * auto-created.</strong> §6c offers a case stub per unmatched test; declining that (slice plan
 * decision J) is only defensible if the alternative is usable, and the alternative is: a person
 * reads this list, decides which case each name means, and posts it to
 * {@code POST /automation-mappings} — where {@link #normalizedName} is the exact key that endpoint
 * stores, so the binding is not a second guess at what the name was.
 *
 * @param name           the test as the report named it, joined by {@link IngestedResult#fullName()}
 * @param normalizedName the key a mapping would be stored under. Served rather than left for the
 *                       client to recompute: the normalization rule lives in one place on purpose
 *                       ({@link IngestedResult#normalize}), and a client re-deriving it is a second
 *                       implementation of the one thing that must agree exactly or every mapping
 *                       silently stops matching.
 * @param outcome        what the runner said. Present because it changes the urgency — an unmatched
 *                       <em>failure</em> is a red test nobody is being told about, where an
 *                       unmatched pass is only unrecorded coverage.
 */
public record UnmatchedTestView(String name, String normalizedName, IngestedOutcome outcome) {
}
