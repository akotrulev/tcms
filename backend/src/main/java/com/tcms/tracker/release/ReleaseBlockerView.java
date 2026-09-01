package com.tcms.tracker.release;

import com.tcms.tracker.IssueStatusView;
import com.tcms.tracker.IssueTypeView;
import java.util.List;

/**
 * The contract's {@code ReleaseBlocker} — one row of {@code release.html}'s <em>What stands between
 * here and shipping</em>.
 *
 * <p>A story is here when it is not in a {@code DONE} status, <strong>or</strong> when it is done and
 * one of its linked cases did not pass in the latest result recorded against this version — which is
 * the case this whole module exists for.
 *
 * <p>{@code status} is served rather than a boolean because the screen distinguishes <em>nobody has
 * started</em> from <em>this is finished and its test says otherwise</em>, and only the status can
 * tell it which. {@code failedCaseKeys} is what turns the second into a sentence naming
 * {@code TC-311} rather than a red dot.
 */
public record ReleaseBlockerView(
        String key,
        String title,
        IssueTypeView type,
        IssueStatusView status,
        int casesPassed,
        int casesFailed,
        int casesBlocked,
        int casesUntested,
        List<String> failedCaseKeys) {
}
