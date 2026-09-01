package com.tcms.tracker.release;

import com.tcms.api.UserRef;
import java.time.Instant;
import java.util.UUID;

/**
 * The contract's {@code ReleaseTests} — the QA half, and <strong>the latest result per case, not the
 * best one</strong>. A case that failed and was not re-run is a failure; taking the best result would
 * let a release pass on a run somebody re-ran until it went green.
 *
 * <p>Every count is a boxed {@link Integer} and that is the type choice's whole point: when
 * {@link #hasRun} is false they are <strong>null</strong>, because an untested version and a clean
 * version are not the same claim and a zero drawn as a meter looks like the second.
 * {@link #untested} is null there too even though its value would be knowable — it would be every
 * linked case — because serving it as a number invites a client to draw it beside a zero.
 */
public record ReleaseTestsView(
        boolean hasRun,
        int linkedCases,
        Integer withResult,
        Integer passed,
        Integer failed,
        Integer blocked,
        Integer untested,
        Instant latestExecutedAt,
        UUID latestRunId,
        String latestRunName,
        UserRef executedBy) {

    /** Nothing has run against this version: one boolean, one total, and seven deliberate nulls. */
    static ReleaseTestsView noRun(int linkedCases) {
        return new ReleaseTestsView(
                false, linkedCases, null, null, null, null, null, null, null, null, null);
    }
}
