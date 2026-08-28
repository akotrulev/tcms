package com.tcms.integration.tracker;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code TrackerIdentity} in {@code docs/specs/openapi.yaml} — one person's connection, served to
 * that person.
 *
 * <h2>Five fields here must never reach {@code GET …/tracker/connected-members}</h2>
 *
 * <p>{@link #secretHint}, {@link #accountId}, {@link #expiresAt}, {@link #lastError} and
 * {@link #lastCheckedAt}. D19 lets a Test Lead see <em>that</em> a colleague has connected and in
 * what state; it does not let them see the colleague's tracker email, when their token dies, or
 * what their tracker said. The natural implementation of that endpoint reuses this record — which
 * is exactly why {@link TrackerConnectedMemberView} is a separate type and why its test closes the
 * response's key set rather than naming the leaks somebody thought of.
 *
 * <p>There is no {@code secret} component and no {@code userId} one. The first is the credential;
 * the second would be a request telling itself who made it.
 */
public record TrackerIdentityView(
        UUID id,
        TrackerProvider provider,
        String baseUrl,
        TrackerAuthMethod authMethod,
        String secretHint,
        String accountId,
        Instant expiresAt,
        String lastError,
        Instant lastCheckedAt,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt) {

    static TrackerIdentityView of(TrackerIdentity identity) {
        return new TrackerIdentityView(
                identity.getId(),
                identity.getProvider(),
                identity.getBaseUrl(),
                identity.getAuthMethod(),
                identity.getSecretHint(),
                identity.getAccountId(),
                identity.getExpiresAt(),
                identity.getLastError(),
                identity.getLastCheckedAt(),
                identity.isEnabled(),
                identity.getCreatedAt(),
                identity.getUpdatedAt());
    }
}
