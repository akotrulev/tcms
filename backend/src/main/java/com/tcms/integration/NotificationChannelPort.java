package com.tcms.integration;

/**
 * Delivers one notice over one transport.
 *
 * <h2>Throwing is how a failure is reported, and there is no other way</h2>
 *
 * <p>{@code OutboxHandler}'s rule, one level down. A {@code void} return that swallowed an error
 * would have {@link NotificationDispatchService} write {@code SENT} into the delivery log for a
 * message nobody received — a claim with no evidence behind it, in the one table an operator
 * consults to find out whether the channel works. The exception is caught one layer up, redacted,
 * and recorded against the channel.
 *
 * <h2>Three implementations from the start</h2>
 *
 * <p>Plan §5's argument for the issue-tracker port applies unchanged: <em>a port with a single
 * implementation always leaks that implementation's vocabulary</em>. Written against Slack alone,
 * this interface would take blocks; Teams' message card and an email body are what keep it taking
 * a {@link RenderedNotification}.
 *
 * <h2>Nothing here retries</h2>
 *
 * <p>The outbox already does, on a backoff, with a ceiling and a visible terminal state. An
 * adapter that retried internally would multiply those attempts by its own and hold the
 * dispatcher's thread while doing it.
 */
interface NotificationChannelPort {

    /** Which channel kind this serves. Exactly one port per kind — see {@code NotificationPorts}. */
    NotificationChannelKind kind();

    /**
     * Sends it.
     *
     * @throws RuntimeException if the notice did not go out, for any reason at all
     */
    void deliver(RenderedNotification notification, ChannelConfig config);
}
