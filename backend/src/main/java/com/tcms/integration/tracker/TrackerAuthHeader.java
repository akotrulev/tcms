package com.tcms.integration.tracker;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * How each provider wants a personal access token presented.
 *
 * <h2>Three providers, three answers, and one of them is not what plan 004 wrote</h2>
 *
 * <ul>
 *   <li><strong>Jira Cloud</strong> — HTTP Basic, {@code email:apiToken}. The token alone is not
 *       enough, which is why {@code tracker_identity.account_id} exists and why the connect form
 *       asks for an email on this provider and not on the others.</li>
 *   <li><strong>Jira Data Center</strong> — {@code Bearer <pat>}. Self-issued, no account id.</li>
 *   <li><strong>Azure DevOps</strong> — HTTP Basic with an <strong>empty username</strong>,
 *       {@code :pat}. The plan's table says "Bearer" for this provider and that is the one line of
 *       it this code does not follow: {@code Authorization: Bearer} on ADO carries an <em>Entra</em>
 *       access token, and a PAT sent that way is refused. Microsoft's own documented form for a PAT
 *       is Basic with a blank user. The plan's *Tests* section names this exact surface as the
 *       untested one — <em>"PAT header forms … asserted against our fixtures, which tests our
 *       parsing and not their API"</em> — so the divergence is recorded here rather than left to be
 *       discovered by the first person who connects.</li>
 * </ul>
 *
 * <p>Nothing here logs, and nothing here returns the token. The value it builds goes straight into
 * one header on one call.
 */
final class TrackerAuthHeader {

    private TrackerAuthHeader() {
    }

    static String of(TrackerCredential credential) {
        return switch (credential.provider()) {
            case JIRA_CLOUD -> basic(
                    credential.accountId() == null ? "" : credential.accountId(),
                    credential.secret());
            case JIRA_DC -> "Bearer " + credential.secret();
            case AZURE_DEVOPS -> basic("", credential.secret());
        };
    }

    private static String basic(String user, String secret) {
        String pair = user + ":" + secret;
        return "Basic " + Base64.getEncoder()
                .encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }
}
