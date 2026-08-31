package com.tcms.tracker.board;

/**
 * The contract's {@code CardMoveResult}.
 *
 * <p>{@link #breach} is present when the move put the target column over its limit. <strong>The move
 * still happened</strong> — this is a 200 carrying a fact, never a 4xx (story B3).
 */
public record CardMoveResultView(BoardIssueCardView card, WipBreachView breach) {
}
