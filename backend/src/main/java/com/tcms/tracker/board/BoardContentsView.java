package com.tcms.tracker.board;

import java.util.List;
import java.util.UUID;

/**
 * The contract's {@code BoardContents} — the cards, and only the cards.
 *
 * <p>Configuration is {@code GET /boards/{boardId}} and not this (D-h): columns change when somebody
 * edits them and cards change on every transition anywhere in the project, so one combined read
 * would relayout the board on every card move.
 *
 * @param breaches the columns over their WIP limit <strong>right now</strong>, counted across every
 *                 lane and over the <em>whole</em> project rather than the filtered set: a WIP limit
 *                 is a fact about how much is in flight, and narrowing the view does not take work
 *                 out of a column. Counted once per issue, so a card in three label lanes is one
 *                 card in flight. An empty array is the normal case
 */
public record BoardContentsView(
        UUID boardId,
        SwimlaneBy swimlaneBy,
        List<BoardLaneView> lanes,
        List<WipBreachView> breaches) {
}
