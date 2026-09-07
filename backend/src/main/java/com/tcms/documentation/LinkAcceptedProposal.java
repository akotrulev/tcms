package com.tcms.documentation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.tenancy.OutboxEvents;
import com.tcms.tenancy.OutboxHandler;
import com.tcms.tenancy.OutboxMessage;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the traceability links a reviewer consented to when they accepted a generated proposal
 * (022 §5).
 *
 * <h2>Why the link is written here and not where the decision was made</h2>
 *
 * <p>A proposal is {@code repository}'s row and a link is this module's, and neither module may
 * import the other (plan §3). So a claim an agent files cannot be a {@code doc_link} while it is a
 * claim — it names no case, because there is no case until a person accepts — and it cannot become
 * one inside the accepting transaction either. The accept resolves every target against the project
 * and answers 400 for a miss; what crosses is a list already checked, and this handler turns each
 * entry into the row a person's link would have been.
 *
 * <p>The reviewer's consent is the whole justification for the write, which is why
 * {@link BlockLinkService#linkAccepted} is ungated rather than re-asking for {@code doc:edit} on a
 * thread that has no person on it — see that method's header for the argument and for the half of it
 * that is merely corroborating.
 *
 * <h2>Idempotent by leaning on the link path, not by a key of its own</h2>
 *
 * <p>Delivery is at least once, so this runs again for links it already wrote. Its one durable write
 * is a {@code doc_link} row, and {@link BlockLinkService}'s founding rule — <em>"re-asserting a
 * recorded fact is not an error"</em> — answers a repeat with the row that is there and writes
 * nothing, no audit line and no {@code links_changed} notice. That is the same guarantee two people
 * recording the same coverage already get, so this handler adds no second mechanism to it. An
 * {@code eventId} keyed delivery row, which the notification handlers need, would be exactly that
 * second mechanism.
 *
 * <p><strong>A partial write is covered by the same property.</strong> Twelve targets where the
 * seventh throws: this method is the transaction boundary, so the six that had been written roll
 * back with it and the retry writes all twelve — an outcome that is right rather than merely
 * survivable, because none of the twelve is keyed on this delivery having happened.
 *
 * <p>The boundary is <em>here</em> and not on {@link BlockLinkService#linkAccepted}, which is
 * package-private: Spring's proxy applies {@code @Transactional} to public methods only, so an
 * annotation there would be silently ignored and every link would commit on its own connection
 * with nothing saying so.
 *
 * <h2>A target that has gone is a delivery, not a failure</h2>
 *
 * <p>{@code TrackerDefectLinkBackstop}'s rule: a page deleted in the minute between the accept and
 * this delivery is not something a retry fixes, so {@link BlockLinkService#linkAccepted} answers
 * false and this handler carries on to the next target. Throwing would retry the whole list eight
 * times and then leave a {@code DEAD} row meaning "one of these twelve blocks is gone", which is
 * neither actionable nor the same statement.
 *
 * <p>What is <em>not</em> tolerated is a payload that cannot be read: that is a shape drift across a
 * boundary the compiler cannot see, no retry helps, and settling the row would be the claim with no
 * evidence behind it that {@link OutboxHandler} names.
 */
@Component
class LinkAcceptedProposal implements OutboxHandler {

    private final BlockLinkService links;
    private final ObjectMapper json;

    LinkAcceptedProposal(BlockLinkService links, ObjectMapper json) {
        this.links = links;
        this.json = json;
    }

    @Override
    public String eventType() {
        return OutboxEvents.PROPOSAL_ACCEPTED;
    }

    @Override
    @Transactional
    public void handle(OutboxMessage message) {
        ProposalAcceptedPayload payload = parse(message.payload());

        for (ProposalAcceptedPayload.Target target : payload.verifies()) {
            LinkRelationship relationship = target.relationship() == null
                    ? LinkRelationship.VERIFIES
                    : LinkRelationship.parse(target.relationship());
            if (relationship == null) {
                // Unreachable: the filing endpoint refuses a relationship outside the three, so a
                // value that gets here came from a row written before that check or from a hand
                // edit. Skipped rather than defaulted — inventing VERIFIES is the product minting a
                // coverage claim nobody made, which is what BlockLinkService.relabel refuses in the
                // one place a claim can legitimately change.
                continue;
            }
            links.linkAccepted(
                    payload.projectId(),
                    target.pageId(),
                    target.blockId(),
                    payload.caseId(),
                    relationship,
                    payload.acceptedBy());
        }
    }

    private ProposalAcceptedPayload parse(String payload) {
        try {
            return json.readValue(payload, ProposalAcceptedPayload.class);
        } catch (JsonProcessingException unreadable) {
            throw new IllegalStateException(
                    "A " + OutboxEvents.PROPOSAL_ACCEPTED + " payload could not be read as the "
                            + "shape this module declares — see ProposalAcceptedPayload",
                    unreadable);
        }
    }
}
