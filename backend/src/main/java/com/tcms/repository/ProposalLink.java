package com.tcms.repository;

import java.util.UUID;

/**
 * {@code ProposalLinkInput} in {@code docs/specs/openapi.yaml} — one requirement a proposed case
 * claims to verify.
 *
 * <h2>One record for both directions, unlike every other input/view pair here</h2>
 *
 * <p>{@link AiProposalInput#verifies()} sends these and {@link AiProposalView#verifies()} serves
 * them, and the contract points both at the same schema because the claim is served <em>as
 * stored</em>: nothing is resolved, defaulted or derived between the two. A separate
 * {@code ProposalLinkView} would be two declarations of one shape with no member that could ever
 * differ, and the one that drifted would be whichever was edited second — which is the argument
 * {@link AiProposalInput}'s header makes about the case travelling in the shape the create endpoint
 * takes, applied one level down.
 *
 * <p>That is also why {@code relationship} is a {@link String} rather than an enum, exactly as
 * {@code documentation.BlockLinkInput} spells the same field: Jackson binding an unknown enum value
 * produces its own 400 describing a Java type, and this value is stored unvalidated at file time
 * and parsed at accept, where a wrong one becomes a field error naming the three legal claims.
 *
 * @param pageId       the page the block sits on, of this project. Named as well as the block so a
 *                     reviewer's screen resolves the claims with one read per page rather than a
 *                     search for a loose block id, and so a target whose page is wrong is a
 *                     mismatch somebody can see rather than a silent resolution.
 * @param blockId      the statement itself. A block keeps its id however much its text is edited —
 *                     {@code documentation}'s founding decision — which is what makes a claim
 *                     stored days before it is acted on worth storing at all.
 * @param relationship {@code VERIFIES}, {@code DERIVED_FROM} or {@code REFERENCES}; null means
 *                     {@code VERIFIES}, which is the contract's default and the column's. Only
 *                     {@code VERIFIES} is coverage.
 */
public record ProposalLink(UUID pageId, UUID blockId, String relationship) {
}
