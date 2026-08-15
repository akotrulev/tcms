package com.tcms.integration;

import java.time.Instant;
import java.util.UUID;

/**
 * One delivery attempt, as an operator reads it.
 *
 * <p>{@link #error} is already redacted — it is stored that way, because a column an operator
 * browses and a backup copies is the wrong place to be relying on a rendering step to keep a
 * credential out. See {@code SecretRedaction}.
 *
 * @param attempt attempts against <em>this channel</em> for this occurrence, which is not the
 *                outbox row's count — a fan-out where one webhook is dead makes the two differ,
 *                and this is the one being asked about
 */
record NotificationDeliveryView(
        UUID id,
        UUID channelId,
        UUID eventId,
        String eventType,
        NotificationDelivery.Status status,
        int attempt,
        String error,
        Instant createdAt,
        Instant updatedAt) {
}
