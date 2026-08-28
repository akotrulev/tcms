package com.tcms.integration.tracker;

import com.tcms.integration.OutboundUrlGuard;
import com.tcms.integration.SecretCipher;
import com.tcms.integration.SecretHint;
import com.tcms.integration.SecretRedaction;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A person's own tracker connections — {@code /me/tracker-identities}.
 *
 * <h2>NO PERMISSION KEY READS OR WRITES HERE, AND THAT IS THE DECISION</h2>
 *
 * <p>A tracker credential is a personal thing somebody gave TCMS for their own use. The gate is
 * <em>being the caller</em>: no Company Admin, no {@code integration:manage} holder, no Instance
 * Admin can list, read, test, rotate or delete somebody else's. Every method here takes the caller's
 * id from the token and every query names it.
 *
 * <p><strong>RLS does not do this half.</strong> The policy on {@code tracker_identity} keeps Acme
 * out of Globex's rows; within one company it says nothing about people. So the service layer is the
 * whole of the per-person boundary, which is why it is stated at the entity, at the repository and
 * here, and why {@code TrackerConnectionTest} asserts it from the endpoint rather than from
 * the query.
 *
 * <h2>The {@code POST} is an upsert, which is what makes rotation possible without a {@code PUT}</h2>
 *
 * <p>A second {@code POST} for a host the caller has already connected replaces the credential and
 * answers 200; a first one creates and answers 201. There is nothing else on the row a person would
 * edit, so a {@code PUT} would need {@code NotificationChannelInput}'s secret-set-separately shape
 * for a form that here never has a row to send back.
 *
 * <h2>Nothing is called on the tracker by a connect</h2>
 *
 * <p>{@code POST …/{id}/test} does that, separately, so a connection can be recorded while the
 * tracker is down — which is exactly when somebody is most likely to be reconnecting.
 */
@Service
public class TrackerIdentityService {

    private final TrackerIdentityRepository identities;
    private final TrackerAdapters adapters;
    private final TrackerCache cache;
    private final SecretCipher cipher;
    private final OutboundUrlGuard guard;
    private final Clock clock;

    TrackerIdentityService(
            TrackerIdentityRepository identities,
            TrackerAdapters adapters,
            TrackerCache cache,
            SecretCipher cipher,
            OutboundUrlGuard guard,
            Clock clock) {

        this.identities = identities;
        this.adapters = adapters;
        this.cache = cache;
        this.cipher = cipher;
        this.guard = guard;
        this.clock = clock;
    }

    /** What a write did. There is no {@code Forbidden}: the only gate here is being the caller. */
    public sealed interface Outcome {

        /**
         * No such row <strong>of the caller's</strong>.
         *
         * <p>Somebody else's identity id answers this, indistinguishably from an id that names
         * nothing at all — §6t applied to a credential, where the disclosure would be that a
         * colleague has connected to something.
         */
        record NotFound() implements Outcome {
        }

        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** Connected ({@code created}) or rotated. */
        record Connected(TrackerIdentityView view, boolean created) implements Outcome {
        }

        record Disconnected() implements Outcome {
        }

        /** What the tracker said — 200 either way, per {@link TrackerConnectionCheckView}. */
        record Checked(TrackerConnectionCheckView view) implements Outcome {
        }
    }

    @Transactional(readOnly = true)
    List<TrackerIdentityView> list(UUID userId) {
        return identities.findByUserIdOrderByBaseUrlAsc(userId).stream()
                .map(TrackerIdentityView::of)
                .toList();
    }

    @Transactional
    public Outcome connect(UUID userId, TrackerIdentityInput input) {
        Map<String, List<String>> errors = validate(input);
        if (!errors.isEmpty()) {
            return new Outcome.Invalid(errors);
        }
        TrackerProvider provider = TrackerProvider.valueOf(input.provider().trim());
        String baseUrl = normalise(input.baseUrl());
        String secret = input.secret().trim();
        Instant now = clock.instant();

        Optional<TrackerIdentity> existing =
                identities.findByUserIdAndProviderAndBaseUrl(userId, provider, baseUrl);
        if (existing.isPresent()) {
            TrackerIdentity identity = existing.get();
            identity.rotate(
                    cipher.encrypt(secret),
                    SecretHint.of(secret),
                    trimToNull(input.accountId()),
                    input.expiresAt(),
                    now);
            identities.saveAndFlush(identity);
            // The previous credential's answers are this person's and are now wrong for them.
            cache.forget(userId);
            return new Outcome.Connected(TrackerIdentityView.of(identity), false);
        }

        TrackerIdentity created = TrackerIdentity.connect(
                userId,
                provider,
                baseUrl,
                cipher.encrypt(secret),
                SecretHint.of(secret),
                trimToNull(input.accountId()),
                input.expiresAt(),
                now);
        identities.saveAndFlush(created);
        cache.forget(userId);
        return new Outcome.Connected(TrackerIdentityView.of(created), true);
    }

    /**
     * Disconnects.
     *
     * <p><strong>Issue links made through this connection survive</strong> (D18) — they are the
     * project's traceability record, not the caller's — and degrade to a plain URL on the case they
     * sit on. The cache is cleared for this person alone, because everybody else's entries are their
     * own answers and are still true.
     */
    @Transactional
    public Outcome disconnect(UUID userId, UUID identityId) {
        Optional<TrackerIdentity> found = identities.findByIdAndUserId(identityId, userId);
        if (found.isEmpty()) {
            return new Outcome.NotFound();
        }
        identities.delete(found.get());
        cache.forget(userId);
        return new Outcome.Disconnected();
    }

    /**
     * Calls the tracker once and records what it said.
     *
     * <p>This is the one place the connections page's "Needs attention" comes from, and the ERD's
     * sentence is why it exists at all, now per person: <em>an expired Jira token that silently
     * stops creating defects, while runs keep passing and failing, is the realistic failure and is
     * invisible otherwise.</em>
     */
    @Transactional
    public Outcome test(UUID userId, UUID identityId) {
        Optional<TrackerIdentity> found = identities.findByIdAndUserId(identityId, userId);
        if (found.isEmpty()) {
            return new Outcome.NotFound();
        }
        TrackerIdentity identity = found.get();
        Instant now = clock.instant();
        String error = null;
        try {
            adapters.forProvider(identity.getProvider())
                    .verify(credentialFor(identity));
        } catch (TrackerUnavailableException refused) {
            error = SecretRedaction.apply(refused.getMessage(), null);
        }
        identity.recordCheck(error, now);
        identities.saveAndFlush(identity);
        // A credential whose state just changed must not keep serving what it answered before.
        cache.forget(userId);
        return new Outcome.Checked(new TrackerConnectionCheckView(error == null, now, error));
    }

    /**
     * The caller's credential for a host, decrypted — the seam every outbound call goes through.
     *
     * <p>Package-private and returning {@link TrackerCredential} rather than a string, so the
     * plaintext has one named type with one short life and no {@code toString} that prints it.
     * Empty means <em>this person has not connected to that host</em>, which the callers render as
     * {@link TrackerIssueAvailability#NOT_CONNECTED} and never as a 401.
     */
    @Transactional(readOnly = true)
    Optional<TrackerCredential> credentialFor(UUID userId, TrackerProvider provider, String baseUrl) {
        return identities.findByUserIdAndProviderAndBaseUrl(userId, provider, normalise(baseUrl))
                .filter(TrackerIdentity::isEnabled)
                .map(this::credentialFor);
    }

    /** Records the outcome of a real call, so a token that died is visible without a manual test. */
    @Transactional
    void recordFailure(UUID userId, TrackerProvider provider, String baseUrl, String message) {
        identities.findByUserIdAndProviderAndBaseUrl(userId, provider, normalise(baseUrl))
                .ifPresent(identity -> identity.recordCheck(
                        SecretRedaction.apply(message, null), clock.instant()));
    }

    private TrackerCredential credentialFor(TrackerIdentity identity) {
        return new TrackerCredential(
                identity.getProvider(),
                identity.getBaseUrl(),
                identity.getAccountId(),
                cipher.decrypt(identity.getSecret()));
    }

    private Map<String, List<String>> validate(TrackerIdentityInput input) {
        Map<String, List<String>> errors = new LinkedHashMap<>();
        TrackerProvider provider = null;
        if (input.provider() == null) {
            errors.put("provider", List.of("Choose Jira Cloud, Jira Data Center or Azure DevOps."));
        } else {
            try {
                provider = TrackerProvider.valueOf(input.provider().trim());
            } catch (IllegalArgumentException notAProvider) {
                errors.put("provider", List.of(
                        "Choose Jira Cloud, Jira Data Center or Azure DevOps."));
            }
        }
        if (input.authMethod() != null && !"PAT".equals(input.authMethod().trim())) {
            // Refused rather than ignored. OAuth is a second value here later, and a request that
            // asked for it today must not be recorded as a PAT connection that happens to work.
            errors.put("authMethod", List.of("Only a personal access token is supported yet."));
        }
        if (input.secret() == null || input.secret().isBlank()) {
            errors.put("secret", List.of("Paste the token you created in the tracker."));
        }
        if (provider == TrackerProvider.JIRA_CLOUD
                && (input.accountId() == null || input.accountId().isBlank())) {
            // Jira Cloud's Basic auth pairs the token with the person's own email, so a token
            // without one cannot authenticate at all — refused here rather than becoming a 401 the
            // person reads as "my token is wrong".
            errors.put("accountId", List.of(
                    "Jira Cloud needs the email address of the Atlassian account this token belongs to."));
        }
        if (input.baseUrl() == null || input.baseUrl().isBlank()) {
            errors.put("baseUrl", List.of("Give the tracker's address."));
        } else {
            switch (guard.check(normalise(input.baseUrl()))) {
                case ALLOWED -> { }
                case NOT_HTTPS -> errors.put("baseUrl", List.of(
                        "The address must start with https:// — a token sent in clear text is "
                                + "already spent."));
                case MALFORMED -> errors.put("baseUrl", List.of("That is not a valid address."));
                case UNRESOLVABLE -> errors.put("baseUrl", List.of(
                        "That address does not resolve to a server."));
                case PRIVATE_DESTINATION -> errors.put("baseUrl", List.of(
                        "This instance will not connect to a private address."));
            }
        }
        return errors;
    }

    private static String normalise(String baseUrl) {
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
