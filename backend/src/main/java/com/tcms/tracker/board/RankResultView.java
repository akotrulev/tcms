package com.tcms.tracker.board;

/**
 * The contract's {@code RankResult}.
 *
 * <p>{@link #rank} is a decimal <strong>as a string</strong>, not a number. {@code NUMERIC} is
 * arbitrary precision and IEEE-754 doubles are not, so {@code JSON.parse} would silently round a
 * rank the server distinguishes and two cards would collide after a few hundred midpoint inserts.
 * The client sorts on it and never does arithmetic with it.
 *
 * <p>{@link #rebalanced} true is <strong>not a failure</strong>: the placement happened, and the
 * project was respaced on the way. It is an instruction to refetch, because after a respace the
 * client's own copy of every other row's rank is stale.
 */
public record RankResultView(String key, String rank, boolean rebalanced) {
}
