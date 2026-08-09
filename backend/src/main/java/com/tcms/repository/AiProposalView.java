package com.tcms.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcms.api.UserRef;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code AiProposal} in {@code docs/specs/openapi.yaml} — one proposal, with everything a reviewer
 * needs to decide it.
 *
 * <p>Includes the proposed case as {@link #testCase()}, deserialized back out of the stored payload
 * rather than re-rendered as a {@code TestCase}: there is no case, so there is no key, no owner, no
 * lifecycle status and no version. Serving the stored input is the honest shape, and it is the same
 * shape the editor takes, so the review surface can render the proposal with the components that
 * render a case being written.
 *
 * @param verdict       what the proposer claimed
 * @param verdictSource who claimed it — {@code NONE} or {@code EXTERNAL}, never anything TCMS has
 *                      checked itself. Served beside the verdict rather than folded into it,
 *                      because a client that shows "grounded" without showing who says so is the
 *                      inflation §6w.1a exists to prevent, one layer up.
 * @param createdBy     the person answerable — a token's owner on an agent-filed proposal
 * @param filedByAgent  whether a credential filed it, rather than <em>which</em> one.
 *                      <p>The token's <em>name</em> would be the richer field and this module may
 *                      not fetch it: {@code repository}'s package rule says {@code CaseOwners} is
 *                      the only class that may import {@code identity}, and it exists to answer
 *                      "what is this person called". Serving the raw {@code created_token_id}
 *                      instead was refused on §100's rule — §121 shipped the API's only bare-uuid
 *                      actor field and had to take it back.
 *                      <p>The boolean is also the better copy. What changes a reviewer's reading is
 *                      <em>"Ana's agent filed this"</em> versus <em>"Ana typed this"</em>; which of
 *                      Ana's three tokens it was belongs to the audit trail, which records it
 *                      (§132) and is where that question is asked.
 * @param decidedBy     who decided, or null while pending
 * @param acceptedCase  the case this became — id and key — or null unless accepted
 */
public record AiProposalView(
        UUID id,
        UUID projectId,
        UUID suiteId,
        String title,
        CaseUpdateRequest testCase,
        ProposalVerdict verdict,
        VerdictSource verdictSource,
        JsonNode grounding,
        ProposalStatus status,
        Instant createdAt,
        UserRef createdBy,
        boolean filedByAgent,
        Instant decidedAt,
        UserRef decidedBy,
        String decisionNote,
        AcceptedCaseView acceptedCase) {

    /**
     * The case an accepted proposal became.
     *
     * @param key the display key, because that is what a person recognises a case by — and the one
     *            thing the id does not tell them ({@code CaseCreateService}'s audit detail says the
     *            same about the same field)
     */
    public record AcceptedCaseView(UUID id, String key) {
    }
}
