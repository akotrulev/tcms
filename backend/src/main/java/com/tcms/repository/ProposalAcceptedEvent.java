package com.tcms.repository;

import java.util.List;
import java.util.UUID;

/**
 * {@code repository.proposal_accepted}, as this module writes it.
 *
 * <h2>The producer half of a pair, and there is no way to make it one type</h2>
 *
 * <p>{@code com.tcms.documentation.ProposalAcceptedPayload} declares the same shape on the other
 * side of the boundary. Plan §3 forbids that module from importing anything of this one's — with no
 * exemption for an event type, which {@code ArchitectureRulesTest} checks over every import — so the
 * payload crosses as a JSON document and the compiler is structurally unable to compare the two
 * declarations. {@code RunCompletedEvent} is the precedent and states the debt that comes with it.
 *
 * <h2>{@code verifies} is {@link ProposalLink} and not a fourth declaration of that shape</h2>
 *
 * <p>{@code RunCompletedEvent}'s header warns that component names here are a wire format, because
 * rows written before a rename are still in the table when the consumer that expects the new name
 * deploys. That is exactly why the targets travel as the record the <em>contract</em> already pins:
 * {@code ProposalLinkInput}'s three member names are compared to {@link ProposalLink}'s by
 * {@code ContractConventionTest} on every build, so renaming one is already a change nothing can
 * make quietly. A private copy of the same three fields would be a shape with no such gate on it.
 *
 * <h2>No {@code eventId}</h2>
 *
 * <p>Unlike the two notification events, whose consumers key a delivery row on one. This event's
 * consumer writes {@code doc_link} rows keyed on {@code (target_block_id, source_type, source_id)}
 * and the ordinary link path already answers a repeat with the row that is there, so a re-delivery
 * is a handful of reads. An id minted per occurrence would be a field nothing reads, and
 * {@code OutboxHandler}'s idempotency rule asks which write is keyed on what rather than for a
 * token.
 *
 * @param proposalId which proposal was accepted — carried so an operator reading a {@code DEAD} row
 *                   can find the decision it came from, since the consumer cannot look it up
 * @param projectId  the project both ends belong to. The consumer re-checks every target against it
 *                   rather than trusting this list, because a page may have been deleted in the
 *                   minute between the accept and the delivery.
 * @param caseId     the case the proposal became — the link's {@code source_id}, with
 *                   {@code source_type} {@code TEST_CASE}. This is the whole reason the claim could
 *                   not be a link when it was filed: there was no case yet.
 * @param acceptedBy the person who accepted, and therefore the link's {@code created_by}. Not the
 *                   proposal's author: the same rule the created case follows, where authorship is
 *                   the person who took responsibility for letting it in.
 * @param verifies   the targets, already resolved against the project by the accept path. Relative
 *                   order is the order they were filed in, which is the order the reviewer saw.
 */
record ProposalAcceptedEvent(
        UUID proposalId,
        UUID projectId,
        UUID caseId,
        UUID acceptedBy,
        List<ProposalLink> verifies) {

    /** The aggregate noun {@code outbox.aggregate_type} carries for this event. */
    static final String AGGREGATE = "ai_proposal";
}
