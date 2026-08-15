package com.tcms.integration;

/**
 * Which transport a channel is, and therefore which adapter delivers it.
 *
 * <p>Three from the start rather than one plus a promise. Plan §5 makes the argument for the
 * issue-tracker port and it is the same one here: <em>a port with a single implementation always
 * leaks that implementation's vocabulary</em>. Slack's block kit and Teams' message card disagree
 * enough to keep {@link NotificationChannelPort} honest, and SMTP disagrees with both about
 * whether a message even has a room to be posted into.
 *
 * <p>Stored as text in {@code notification_channel.kind} rather than as a database enum, so adding
 * a fourth is a Java change and a migration only if existing rows have to move.
 */
public enum NotificationChannelKind {

    /** An incoming webhook. The URL is the credential — see {@link SecretCipher}. */
    SLACK,

    /** An incoming webhook, {@link #SLACK}'s shape and a different document. */
    TEAMS,

    /**
     * The instance's configured relay, to recipients named in the channel's config.
     *
     * <p>The one kind with no credential of its own: it authenticates as whatever
     * {@code spring.mail} names, which is deployment configuration and belongs nowhere near a
     * company-owned row.
     */
    EMAIL
}
