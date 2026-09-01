package com.tcms.tracker.release;

/**
 * The one answer to <em>"can we ship 2.4?"</em> — story R4.
 *
 * <h2>{@link #NOT_TESTED} is not {@link #NOT_READY} with zero failures</h2>
 *
 * <p>It means no execution has been recorded against this version <strong>at all</strong>, while
 * every counted story is done: nothing is outstanding except proof. The distinction is the reason
 * this enum has three members rather than two, and the reason {@code ReleaseTests.hasRun} travels
 * beside it with <strong>null</strong> counts under it — an untested version and a clean version are
 * not the same claim, and a meter drawn from a zero looks like the second.
 *
 * <p>{@link #NOT_READY} covers everything outstanding: a counted story not in a {@code DONE} status,
 * a linked case whose latest result <em>for this version</em> is not a pass, or a linked case with
 * no result for this version at all while others have one. An unrun case is unproven, and R4 asks
 * for cases passing <em>of total</em>, not of executed.
 *
 * <p>Counted stories are D8's set — story-level issues whose type counts toward completion — so a
 * <strong>Spike never blocks a release</strong>, exactly as it never moves an epic's percentage. The
 * two numbers come from one implementation and cannot disagree.
 */
public enum ReleaseVerdict {
    READY,
    NOT_READY,
    NOT_TESTED
}
