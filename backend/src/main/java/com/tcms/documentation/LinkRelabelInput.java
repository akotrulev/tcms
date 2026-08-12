package com.tcms.documentation;

/**
 * {@code LinkRelabelInput} in {@code docs/specs/openapi.yaml} — the body of a link correction (§174).
 *
 * <p><strong>One record for both ends of the act</strong>, and one schema in the contract. A block
 * link is corrected on the documentation page under {@code doc:edit} and an external link on the
 * case under {@code case:edit}, but what is being said is identical — <em>this link claims
 * something different from what it says</em> — and §173 is the section that paid for splitting one
 * act into two spellings. Two records would also be two places for the field to acquire a default.
 *
 * <p>A String rather than {@link LinkRelationship}, for {@link BlockLinkInput}'s reason: Jackson
 * refusing an unknown enum value produces a 400 describing a Java type, where the services here
 * produce a field error naming the three that are valid.
 *
 * @param relationship <strong>required, with no default</strong> — and this is the one place it
 *                     differs from the create. An absent relationship on a {@code POST} means "the
 *                     ordinary one" and resolves to {@code VERIFIES}; on a correction it means the
 *                     caller did not say what to correct it <em>to</em>, and defaulting there would
 *                     have the product invent a claim on somebody's behalf — on the field whose
 *                     whole purpose is to keep "proves it" and "mentions it" apart.
 */
public record LinkRelabelInput(String relationship) {
}
