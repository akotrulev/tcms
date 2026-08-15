package com.tcms.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * A channel resolved into what an adapter needs — including, for the two webhook kinds, the
 * plaintext credential.
 *
 * <h2>This is the only place a webhook URL exists as a String, and it is deliberately short-lived</h2>
 *
 * <p>{@link NotificationChannel} hands back ciphertext and has no plaintext accessor at all, so an
 * instance of this record is the entire lifetime of a decrypted credential: built immediately
 * before a delivery, passed to one adapter, and unreferenced afterwards. Nothing stores it,
 * nothing logs it, and {@link SecretRedaction} takes it back out of whatever the transport says
 * about the attempt.
 *
 * <p>It has no {@code toString} of its own <em>on purpose</em> — a record's generated one prints
 * every component, which would put the URL into any log line that interpolated it. That is stated
 * rather than fixed by overriding, because the fix people reach for (redacting inside
 * {@code toString}) makes the type look safe to log, and the next component added would not be.
 * Do not interpolate this record.
 *
 * @param kind       which adapter this resolves to
 * @param webhookUrl the plaintext incoming-webhook URL, or null for {@link NotificationChannelKind#EMAIL}
 * @param recipients who receives an email, empty for the two webhook kinds
 */
record ChannelConfig(NotificationChannelKind kind, String webhookUrl, List<String> recipients) {

    /** The one key {@code notification_channel.config} carries today. */
    private static final String RECIPIENTS = "recipients";

    /**
     * Resolves a stored channel.
     *
     * @throws IllegalStateException when the row cannot be used — a missing credential, an
     *                               unreadable config document, an email channel with nobody on
     *                               it. Thrown rather than skipped so the failure is recorded
     *                               against the channel in the delivery log, which is where an
     *                               administrator will look; silently declining to deliver is the
     *                               state that presents as "notifications just don't work here"
     */
    static ChannelConfig of(NotificationChannel channel, SecretCipher cipher, ObjectMapper json) {
        List<String> recipients = recipientsOf(channel.getConfig(), json);
        return switch (channel.getKind()) {
            case SLACK, TEAMS -> {
                if (channel.getSecret() == null) {
                    throw new IllegalStateException(
                            "Channel '" + channel.getName() + "' is a " + channel.getKind()
                                    + " webhook with no URL configured");
                }
                yield new ChannelConfig(channel.getKind(), cipher.decrypt(channel.getSecret()), List.of());
            }
            case EMAIL -> {
                if (recipients.isEmpty()) {
                    throw new IllegalStateException(
                            "Channel '" + channel.getName() + "' is an email channel with no "
                                    + "recipients configured");
                }
                yield new ChannelConfig(NotificationChannelKind.EMAIL, null, recipients);
            }
        };
    }

    /**
     * The recipient list, or empty.
     *
     * <p>Tolerant of a document that has no {@code recipients} at all, because that is what a
     * webhook channel's config legitimately looks like — and intolerant of one it cannot parse,
     * because a config column holding something other than an object is a row nothing wrote and
     * the honest response is to say so rather than deliver a message to nobody.
     *
     * <p>Reachable without {@link #of} on purpose. A read model needs the recipient list and must
     * <strong>not</strong> take the rest of that method: decrypting is what {@code of} does, and a
     * settings screen that decrypted every channel to render a name would put every credential in
     * the instance into memory on one request.
     */
    static List<String> recipientsOf(String config, ObjectMapper json) {
        JsonNode root;
        try {
            root = json.readTree(config);
        } catch (JsonProcessingException unreadable) {
            throw new IllegalStateException("A channel's config is not readable JSON", unreadable);
        }
        JsonNode listed = root.path(RECIPIENTS);
        if (!listed.isArray()) {
            return List.of();
        }
        List<String> recipients = new ArrayList<>();
        for (JsonNode entry : listed) {
            String address = entry.asText("").trim();
            if (!address.isEmpty()) {
                recipients.add(address);
            }
        }
        return List.copyOf(recipients);
    }
}
