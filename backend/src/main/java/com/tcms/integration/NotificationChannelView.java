package com.tcms.integration;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One channel as anybody outside this module may see it — <strong>without its credential.</strong>
 *
 * <p>There is no {@code secret} component and there must never be one. The webhook URL is the
 * authentication, so a read model carrying it would put a live credential into every response, log
 * and browser cache that ever renders a settings screen. {@link #secretHint} is what replaces it:
 * enough for a person to recognise which webhook is configured, and far short of enough to post to
 * it.
 *
 * <p>{@link #lockVersion} is here so the write path can carry an {@code If-Match} without a second
 * read. A channel edit is a field edit — a name and a recipient list typed over what somebody read
 * — which is the case {@code ApiToken}'s rubric says needs one, unlike the absolute-destination
 * writes that take none.
 *
 * @param secretHint null when the kind has no credential of its own, which is how an email channel
 *                   and an unconfigured webhook are told apart on the screen
 */
public record NotificationChannelView(
        UUID id,
        UUID projectId,
        NotificationChannelKind kind,
        String name,
        boolean enabled,
        List<String> recipients,
        String secretHint,
        List<NotificationSubscriptionView> subscriptions,
        Instant createdAt,
        Instant updatedAt,
        long lockVersion) {
}
