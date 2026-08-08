package com.tcms.repository;

/**
 * Who produced the verdict, and therefore what it is worth — {@code ai_proposal.verdict_source},
 * §6w.1a.
 *
 * <h2>This is derived by the server and has no request field</h2>
 *
 * <p>The whole point of the column is that a caller cannot set it. {@code AiProposalService}
 * computes it from whether a verdict was asserted at all, and the {@code CHECK} in {@code V43}
 * makes the third value unreachable besides. Two barriers rather than one, because the difference
 * between them is a coverage number that means something and one any MCP client can mint by sending
 * a string.
 *
 * <h2>Why {@code TCMS_VERIFIED} is not here yet</h2>
 *
 * <p>§6w.1a defines it as <em>"TCMS ran the isolated critic itself, under a known model and prompt
 * version"</em>, and TCMS calls no model in this milestone — the plan defers that behind BYO-agent
 * generation on purpose (<em>"before TCMS ever calls a model itself"</em>). {@code V39} established
 * the rule this follows (#50): an enum value with no writer is a claim waiting for whoever first
 * guesses the string. It arrives in the migration that gives it one.
 */
public enum VerdictSource {

    /**
     * Nobody checked but the reviewer will — rung 0, and what a proposal filed with no verdict
     * claim carries.
     */
    NONE,

    /**
     * An agent asserted a verdict over MCP or the CLI. <strong>Recorded, shown to the reviewer,
     * attributed to the token that said so, and never counted as verified.</strong> TCMS cannot see
     * that agent's critic, its isolation or its prompt, so treating its claim as equivalent to its
     * own would let any client mint verified coverage by sending {@code verdict: GROUNDED}. The
     * review surface displays it as <em>"the connecting agent reports …"</em>, which is a different
     * sentence from <em>"this is grounded"</em> and is meant to be.
     */
    EXTERNAL
}
