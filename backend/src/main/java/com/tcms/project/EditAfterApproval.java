package com.tcms.project;

/**
 * Plan §6g's two answers to <em>"what does an edit to an APPROVED artifact do"</em>.
 *
 * <p>§6g states both and recommends the first: <em>"Retain + stale marker as the default, and
 * Re-review as the opt-in for teams that need it. Forcing full re-approval on a typo fix is how
 * review processes get abandoned."</em>
 */
public enum EditAfterApproval {

    /**
     * Edits are allowed in place and the approval sticks, marked <em>stale</em> with the diff since
     * approval visible.
     *
     * <p>This has been the product's behaviour all along, without being a choice: {@code ReviewPanel}
     * has rendered the stale marker since §76 — the review names the version somebody was asked to
     * read, and the page knows which version it is showing — and nothing anywhere implemented the
     * alternative it is implicitly the alternative to. So {@code RETAIN} is not new behaviour; it is
     * the existing behaviour finally having a name and something to contrast with.
     */
    RETAIN,

    /**
     * An edit returns the artifact to {@code DRAFT} and approval must be re-earned.
     *
     * <p><strong>The demotion is not gated on {@code *:approve}, and that is the decision worth
     * reading.</strong> §75 established that leaving an approval is an approval act — a Test
     * Engineer must not be able to rescind a lead's sign-off with the key they use every day — and
     * this looks like exactly that hole reopened. It is not, for three reasons that have to hold
     * together:
     *
     * <ol>
     *   <li><strong>Nobody requested the transition.</strong> §75's defect was a caller <em>asking
     *       for</em> {@code DRAFT} on an approved artifact and being charged the edit key for it.
     *       Here the caller asked to edit, which §6g says they may, and the project has declared in
     *       advance that editing invalidates approval. Charging the approve key would not make the
     *       act safer; it would make an approved artifact <strong>uneditable by the people §6g
     *       expects to edit it</strong>, which inverts the setting into a lock.</li>
     *   <li><strong>It is opt-in and off by default.</strong> A project running the default
     *       ({@link #RETAIN}) is in exactly §75's world and keeps all of §75's protection. Turning
     *       this on is a project saying, deliberately, that its approvals are version-specific — the
     *       "strict; right for regulated work" reading §6g gives it.</li>
     *   <li><strong>It is recorded.</strong> The demotion writes its own audit event naming the
     *       actor and the edit that caused it, so the trail shows an approval ended by a named
     *       person's edit rather than an approval that quietly evaporated.</li>
     * </ol>
     *
     * <p>What it genuinely costs: under this mode a Test Engineer can end an approval by editing.
     * That is the setting's <em>purpose</em> and not a side effect of it, and a project that does
     * not want it has it off.
     */
    RE_REVIEW
}
