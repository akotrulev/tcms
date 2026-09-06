package com.tcms.tracker.board;

import com.tcms.api.UserRef;
import com.tcms.tracker.IssueLevel;
import com.tcms.tracker.IssueStatusView;
import com.tcms.tracker.Priority;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The contract's {@code BoardIssueCard} — <strong>not {@code BoardCard}</strong>.
 *
 * <p>That schema is {@code work}'s composite card and carries four kinds, a {@code derived} flag and
 * a synthetic id. Every card here is an issue with a real key, so the fields that exist only to
 * describe a derived card would be constant and misleading.
 *
 * @param status            the issue's stored status at every level — ~~derived for an Epic
 *                          (D31)~~ → superseded (021 R10). The same value
 *                          {@code GET /issues/&#123;key&#125;} shows, which is the part that has
 *                          not changed: a board and a detail page must not spell one issue's
 *                          status two ways
 * @param rank              a decimal as a string, for {@link RankResultView#rank()}'s reason
 * @param allowedTransitions §12.4, verbatim and load-bearing: the legal set must be known
 *                          client-side <em>before the drag starts</em>, so a forbidden drop is
 *                          refused at the drag with the reason rather than accepted and reverted.
 *                          ~~Empty for an Epic (D31)~~ → superseded (021 R10): an epic moves like
 *                          a story. From the {@code blocking} status the remembered origin is in
 *                          the set with no {@code issue_transition} row behind it (D-c), because a
 *                          legal target withheld here is §12.4 the wrong way round. And
 *                          <strong>caller-scoped</strong> under D33's review-to-close gate: {@code DONE}-kind targets are absent for a
 *                          caller who is this issue's assignee on a project that requires review
 *                          to close, so the drag is refused before it starts rather than by the
 *                          endpoint afterwards
 * @param agentEligible     D30's hint, on the card so the board can mark it
 *                          (~~`board.html`~~ → `docs/mockups/work-board.html`, which is the file's
 *                          actual name — the citation was stale on arrival and `citedFiles.test.ts`
 *                          is what said so)
 * @param parentKey         the story a task belongs to, the epic a story belongs to, null for an
 *                          epic. <strong>The immediate parent</strong> and not
 *                          {@link #epicKey}, which is the lane: 021 §2.5 makes a story with tasks
 *                          on this board a pinned row header, and the row a card belongs under is
 *                          its parent's, one level up, not its epic's two
 * @param elsewhere         the descendants this board does <em>not</em> show, grouped by the boards
 *                          that do. Empty when every descendant is shown here, which is the
 *                          ordinary case and the case a row header draws no link for
 * @param hasAcceptanceCriteria 021 §2.6 — the PM board's readiness signal. The array itself stays
 *                          off the card: it is the detail page's, and a board that shipped every
 *                          criterion of every card would ship a paragraph per card to render a dot
 * @param blockedFromStatus set while the issue is in the project's {@code blocking} status
 *                          (021 D-b/D-c). The card reads {@code Blocked · from In test}, so a
 *                          blocked card sitting on somebody else's board says whose problem it was
 * @param allStoriesDone    021 D-d, and a <strong>nudge rather than a transition</strong>: true on
 *                          an EPIC whose every counted story is DONE while the epic itself is not.
 *                          The server never moves an epic on its own
 * @param linkedCases       the roll-up of the cases linked to this card's work (021 D-k), or null
 *                          when nothing under it is linked — ~~null until 021 T4 serves it~~ →
 *                          served since T4, so the null now means "none linked" and nothing else.
 *                          The key is always present. An EPIC rolls up its whole subtree and a TASK
 *                          borrows its parent story's, because linking is done on the story (H7)
 *                          and the QA board is made of tasks
 */
public record BoardIssueCardView(
        String key,
        String title,
        String typeName,
        IssueLevel typeLevel,
        String iconCode,
        boolean countsTowardCompletion,
        UUID columnId,
        IssueStatusView status,
        String rank,
        Priority priority,
        UserRef assignee,
        LocalDate dueDate,
        boolean overdue,
        Double estimateHours,
        String epicKey,
        List<LabelView> labels,
        List<IssueStatusView> allowedTransitions,
        int subtaskTotal,
        int subtaskDone,
        boolean agentEligible,
        String parentKey,
        List<CardElsewhereView> elsewhere,
        boolean hasAcceptanceCriteria,
        IssueStatusView blockedFromStatus,
        boolean allStoriesDone,
        LinkedCasesSummaryView linkedCases) {
}
