package com.tcms.integration;

import java.util.UUID;

/**
 * One event type a channel wants, as the settings screen reads it.
 *
 * <p>{@link #minFailures} is served even for event types that have no verdicts, where it is inert.
 * Hiding it per type would put a rule about which events carry counts into the client, where it
 * would be a second copy of a fact this module owns.
 */
record NotificationSubscriptionView(UUID id, String eventType, int minFailures) {
}
