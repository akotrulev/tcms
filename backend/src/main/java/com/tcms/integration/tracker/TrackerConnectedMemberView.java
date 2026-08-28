package com.tcms.integration.tracker;

import com.tcms.api.UserRef;
import java.util.List;

/**
 * {@code TrackerConnectedMember} — one project member, and their state per bound host (004 D19).
 *
 * <h2>This type exists to be small, and its smallness is the decision</h2>
 *
 * <p>D19 was taken <strong>over a stated counter-argument</strong>: a Test Lead learns which of
 * their team hold a Jira or ADO account, which is personal information those people gave TCMS for
 * their own use and not to their lead. It was overridden knowingly, because <em>"why can nobody on
 * this team file defects?"</em> is unanswerable without it — and the override is bounded by two
 * rules that are part of the decision rather than implementation detail:
 *
 * <ul>
 *   <li><strong>Scoped to the hosts this project's bindings name.</strong> A lead learns nothing
 *       about a colleague's connection to a tracker this project is not bound to.</li>
 *   <li><strong>A state per host and nothing else.</strong> Not the hint, not the account id, not
 *       the scopes, not the expiry, not the text of the last error, and nothing about what that
 *       person can <em>see</em> in the tracker.</li>
 * </ul>
 *
 * <p>Reusing {@link TrackerIdentityView} here is the natural implementation and it carries all five
 * of those. That is why this is its own record and why the endpoint's test asserts a
 * <strong>closed set of response keys</strong> rather than listing the leaks somebody thought of:
 * the failure mode is a field arriving later, not a field somebody chose today.
 *
 * @param hosts one entry per host this project's bindings name, <strong>whether or not the person
 *              has connected</strong> — an absent host would read as "not applicable" where the
 *              honest answer is {@code NOT_CONNECTED}, which is the whole question being asked.
 */
public record TrackerConnectedMemberView(UserRef user, List<HostState> hosts) {

    /**
     * One host, and the whole of what may be said about this person's relationship to it.
     *
     * @param state {@code NEEDS_ATTENTION} means "the last call failed or the credential has
     *              expired" and nothing finer; the sentence behind it belongs on the affected
     *              person's own account page.
     */
    public record HostState(TrackerProvider provider, String baseUrl, TrackerConnectionState state) {
    }
}
