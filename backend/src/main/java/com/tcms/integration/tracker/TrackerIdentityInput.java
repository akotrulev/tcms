package com.tcms.integration.tracker;

import java.time.Instant;

/**
 * {@code TrackerIdentityInput} — the body of a connect, which is also the body of a rotate.
 *
 * <h2>{@code secret} is required, and that is what makes this different from a channel</h2>
 *
 * <p>{@code NotificationChannelInput} carries the secret-set-separately shape — null means unchanged
 * — because a channel has a name and a recipient list somebody edits without touching the
 * credential, and a form that submitted every field would blank the webhook of every channel
 * anybody renamed: <em>"a defect with no error message"</em>, in that record's own words.
 *
 * <p>There is nothing else on this row a person would edit. So the {@code POST} is an
 * <strong>upsert on {@code (provider, baseUrl)}</strong> — 201 for a first connection, 200 for a
 * rotation — and the credential is always present, because sending this body always means "here is
 * my token for this host".
 *
 * @param accountId required for {@code JIRA_CLOUD}, whose Basic auth pairs the token with the
 *                  person's own email; null for the two Bearer providers, where a value would be
 *                  personal information collected for nothing.
 * @param expiresAt what the tracker showed when the token was minted, if it showed one. Stored so
 *                  the connections page can warn <em>before</em> the token dies rather than
 *                  explaining afterwards why defects stopped being filed.
 */
public record TrackerIdentityInput(
        String provider,
        String baseUrl,
        String authMethod,
        String secret,
        String accountId,
        Instant expiresAt) {
}
