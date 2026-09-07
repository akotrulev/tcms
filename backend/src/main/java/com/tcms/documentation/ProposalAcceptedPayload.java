package com.tcms.documentation;

import java.util.List;
import java.util.UUID;

/**
 * {@code repository.proposal_accepted}, as this module reads it.
 *
 * <h2>The consumer half of a pair, and the pair is the point</h2>
 *
 * <p>{@code repository.ProposalAcceptedEvent} is the producer's declaration of the same shape.
 * <strong>They are two records on purpose and cannot be one:</strong> plan §3 forbids this module
 * from importing anything of {@code repository}'s, so the payload crosses as a JSON document and the
 * compiler is structurally unable to check that the two agree. {@code RunCompletedPayload} in
 * {@code integration} is the precedent, and what replaces the compiler is the same thing there and
 * here: a test on each side, plus one that drives a real accept through the real outbox into this
 * handler, because two pins updated together would otherwise still hide a drift.
 *
 * <h2>The targets are re-checked here, and carrying them is still right</h2>
 *
 * <p>{@code AiProposalDecisionService} resolved every one of these against the project before the
 * case was created — a claim naming a deleted block is a 400 there, and never reaches this table.
 * What the payload buys is that this module does not have to <em>ask</em>: it may not read
 * {@code ai_proposal}, so a pointer would be a row it cannot fetch. {@link LinkAcceptedProposal}
 * nonetheless resolves each target again through the ordinary link path, because minutes pass
 * between the accept and the delivery and a page can be deleted in them.
 *
 * @param proposalId which proposal was accepted. Nothing here can look it up; it is carried so an
 *                   operator reading a {@code DEAD} row can find the decision it came from.
 * @param projectId  the project both ends belong to, and the bound every target is checked against
 *                   — RLS bounds a statement to the <em>company</em>, and a sibling project's page
 *                   is this company's, which is the leak {@code CatalogueFacts}' header names.
 * @param caseId     the case the proposal became: the link's {@code source_id}, with
 *                   {@code source_type} {@code TEST_CASE}. It did not exist when the claim was
 *                   filed, which is the whole reason the claim could not be a link then.
 * @param acceptedBy the person who accepted, and the link's {@code created_by} — the same rule the
 *                   created case follows, where the author is whoever took responsibility for
 *                   letting it in rather than whoever proposed it.
 * @param verifies   one target per link to write. An empty list is not produced: the accept records
 *                   no event when nothing was claimed.
 */
record ProposalAcceptedPayload(
        UUID proposalId,
        UUID projectId,
        UUID caseId,
        UUID acceptedBy,
        List<Target> verifies) {

    /**
     * One claimed statement.
     *
     * @param relationship {@code VERIFIES}, {@code DERIVED_FROM} or {@code REFERENCES}, or null for
     *                     the default — which {@link LinkRelationship} decides, exactly as it does
     *                     for a link a person makes through {@code BlockLinkInput}.
     */
    record Target(UUID pageId, UUID blockId, String relationship) {
    }
}
