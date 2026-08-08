package com.tcms.repository;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code AiProposalInput} in {@code docs/specs/openapi.yaml} — a generated case being filed for
 * review.
 *
 * <h2>The case travels in the shape the create endpoint already takes</h2>
 *
 * <p>{@link #testCase()} is a {@link CaseUpdateRequest}, unwrapped, so accepting a proposal is
 * literally handing this body to {@link CaseCreateService}. A second shape here — "the proposal
 * form of a case" — would be a second answer to <em>what is a case</em>, and the one that diverges
 * is whichever was edited second. It also means a proposal that would be refused as a case is
 * refused with the same field errors, on the same field names, from the same validator.
 *
 * <h2>There is no {@code verdictSource} field, and there must never be one</h2>
 *
 * <p>§6w.1a: an agent's asserted verdict is recorded and shown and <strong>never counted as
 * verified</strong>, because TCMS cannot see that agent's critic, its isolation or its prompt.
 * {@link VerdictSource} is therefore computed from whether {@link #verdict()} was sent at all —
 * {@code NONE} when it was not, {@code EXTERNAL} when it was — and {@code TCMS_VERIFIED} is
 * unreachable from the wire twice over: no field carries it, and {@code V43}'s {@code CHECK} does
 * not permit the value at all. A field here would be the one place a caller could mint verified
 * coverage by sending a string.
 *
 * @param testCase  the proposed case. Required; a proposal with no case is not a proposal.
 * @param verdict   what the proposer claims about grounding, or absent. Absent is <em>not</em> the
 *                  same as {@code UNVERIFIED}: both store the verdict {@code UNVERIFIED}, and they
 *                  differ in the source column — nobody claimed anything, versus an agent claimed
 *                  it had not checked. The reviewer sees a different sentence for each.
 * @param grounding per-step findings, including rejections and unverified claims (§6w.6). Free-form
 *                  on purpose: it is evidence produced by somebody else's critic, and a schema here
 *                  would refuse the parts this product has not thought of. It is displayed, never
 *                  interpreted — and never as HTML.
 */
public record AiProposalInput(
        CaseUpdateRequest testCase,
        ProposalVerdict verdict,
        JsonNode grounding) {
}
