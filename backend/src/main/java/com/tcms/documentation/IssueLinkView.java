package com.tcms.documentation;

import com.tcms.api.UserRef;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code IssueLink} — a tracker issue linked to a case, <strong>as stored</strong>.
 *
 * <h2>No key, no title, no status, and the three absences are two different arguments</h2>
 *
 * <p>The <em>key</em> is absent because it moves: a Jira project move renames it, and a stored key
 * is a label that lies rather than a link that breaks (D17). The <em>title</em> and <em>status</em>
 * are absent because they were fetched with one person's credential, and a shared row carrying them
 * would serve them to a viewer who cannot see the issue at all (D5/D15).
 *
 * <p>So the client renders the live ones through {@code GET …/tracker/issues/{issueId}} and, when it
 * cannot, {@code <provider> issue <issueId> ↗} — never anything parsed out of {@link #url}.
 *
 * @param bindingId resolved <strong>at read time</strong> by matching {@code (provider, host)}
 *                  against the project's current bindings, and never stored (D18). Null when no
 *                  binding matches any more: the chip then keeps its href and stops previewing,
 *                  which is what "a link survives the deletion of the binding it was made through"
 *                  looks like on a screen.
 */
public record IssueLinkView(
        UUID id,
        String provider,
        String host,
        String issueId,
        String url,
        UUID bindingId,
        Instant createdAt,
        UserRef createdBy) {
}
