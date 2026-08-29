package com.tcms.integration;

import java.util.List;

/**
 * What a caller may set on a channel.
 *
 * <h2>{@code secret} is null-means-unchanged, and that is not the usual convention here</h2>
 *
 * <p>Everywhere else in this codebase a null field on an edit means "set it to nothing". It cannot
 * mean that here: the screen never shows a secret, so an edit form has nothing to send back, and
 * an update that treated the absent field as a clear would blank the credential of every channel
 * anybody renamed. The row would still look configured and delivery would start failing later, at
 * a time unconnected to the edit that caused it.
 *
 * <p>{@link NotificationChannel#replaceSecret} is therefore a separate mutator rather than part of
 * the general one, so this decision is visible in the entity as well as in the request shape.
 *
 * @param kind        ignored on update. A channel's transport is not editable — see
 *                    {@link NotificationChannel}'s field comment for what changing it would leave
 *                    behind
 * @param recipients  email addresses, for {@link NotificationChannelKind#EMAIL} only. The
 *                    non-credential half of the config, and the only key it has today
 * @param secret      the incoming-webhook URL in plaintext, on the way in only. Encrypted before
 *                    it is stored and never returned by anything
 */
/**
 * A channel and the events it subscribes to.
 *
 * <p>Public, with {@link NotificationChannelService#create}, for the composition root's
 * {@code DemoWorkspaceSeeder} — a seeded channel has to be one that service made, because it is
 * what encrypts the webhook secret and writes the subscription rows beside the channel.
 */
public record NotificationChannelInput(
        NotificationChannelKind kind,
        String name,
        boolean enabled,
        List<String> recipients,
        String secret,
        List<SubscriptionInput> subscriptions) {

    /**
     * One event type this channel wants.
     *
     * @param minFailures 0 for every occurrence. Meaningless for an event type with no verdicts,
     *                    where it is inert rather than a filter that never matches
     */
    public record SubscriptionInput(String eventType, int minFailures) {
    }
}
