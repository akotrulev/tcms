package com.tcms.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every database transaction a delivery needs, in a bean of its own — so that none of them can
 * contain the network call.
 *
 * <h2>Why this is a separate class from {@link NotificationDispatchService}</h2>
 *
 * <p>{@code OutboxDispatcher} and {@code OutboxLedger} have the same split for the same reason,
 * and it is not stylistic. A {@code @Transactional} method called from another method of the
 * <em>same</em> bean does not go through the proxy, so the annotation does nothing — and here that
 * means no {@code app.current_company}, which under RLS is zero rows and a fan-out that silently
 * delivers to nobody. It is also what keeps a database connection from being held open across a
 * POST to somebody else's server, which is the failure {@code V1}'s comment on {@code outbox} is
 * about in the first place.
 *
 * <p>So each method here is one short transaction, and the orchestration — including everything
 * slow — happens outside all of them.
 */
@Service
public class NotificationLedger {

    private final NotificationChannelRepository channels;
    private final NotificationSubscriptionRepository subscriptions;
    private final NotificationDeliveryRepository deliveries;
    private final SecretCipher cipher;
    private final ObjectMapper json;
    private final Clock clock;

    NotificationLedger(
            NotificationChannelRepository channels,
            NotificationSubscriptionRepository subscriptions,
            NotificationDeliveryRepository deliveries,
            SecretCipher cipher,
            ObjectMapper json,
            Clock clock) {

        this.channels = channels;
        this.subscriptions = subscriptions;
        this.deliveries = deliveries;
        this.cipher = cipher;
        this.json = json;
        this.clock = clock;
    }

    /**
     * The enabled channels in this project that asked for this event <em>and</em> whose threshold
     * it clears.
     *
     * <p>The threshold is applied here rather than in the repository query because it is a
     * property of the occurrence rather than of the row: {@code min_failures} is compared against
     * a number that arrived in the payload, and pushing it into SQL would put a parameter with no
     * column behind it into the one query a later reader will assume is about channels.
     *
     * @param failures how many results failed, or null for an event type that has no such notion —
     *                 which matches every subscription, per {@code NotificationSubscription#wants}
     */
    @Transactional(readOnly = true)
    public List<UUID> subscribed(UUID projectId, String eventType, Integer failures) {
        List<UUID> candidates = channels.findSubscribed(projectId, eventType).stream()
                .map(NotificationChannel::getId)
                .toList();
        if (candidates.isEmpty()) {
            return List.of();
        }

        Map<UUID, NotificationSubscription> byChannel = new HashMap<>();
        for (NotificationSubscription subscription
                : subscriptions.findForChannels(eventType, candidates)) {
            byChannel.put(subscription.getChannelId(), subscription);
        }

        List<UUID> wanted = new ArrayList<>();
        for (UUID channelId : candidates) {
            NotificationSubscription subscription = byChannel.get(channelId);
            if (subscription != null && subscription.wants(failures)) {
                wanted.add(channelId);
            }
        }
        return List.copyOf(wanted);
    }

    /**
     * Resolves one channel into something an adapter can be handed.
     *
     * <p>Per channel rather than for the whole fan-out, so that one misconfigured channel — a
     * webhook with no URL, an email channel with nobody on it — fails on its own row instead of
     * stopping every other channel's delivery of the same event.
     *
     * @return empty if the channel has been deleted since the fan-out was computed, which is
     *         ordinary rather than exceptional: nothing holds a lock across a delivery
     */
    @Transactional(readOnly = true)
    public Optional<ChannelTarget> resolve(UUID channelId) {
        return channels.findById(channelId)
                .map(channel -> new ChannelTarget(
                        channel.getId(),
                        channel.getName(),
                        ChannelConfig.of(channel, cipher, json)));
    }

    /**
     * Takes this channel for one attempt at this occurrence.
     *
     * <p><strong>This is the idempotency gate, and it is what makes at-least-once delivery legal
     * here.</strong> An outbox row whose handler succeeded and then lost the settle is re-delivered
     * on the next tick; without this, that is a duplicate message in somebody's release channel,
     * and the retry ladder makes it up to eight.
     *
     * <p>The row is created on the first attempt and left {@code FAILED} until something says
     * otherwise, so a channel that has never once been reachable appears in the history rather
     * than being absent from it — an empty log and a dead webhook must not look the same.
     *
     * @return false when this channel has already been told, in which case nothing further happens
     */
    @Transactional
    public boolean beginAttempt(UUID channelId, UUID eventId, String eventType) {
        Optional<NotificationDelivery> existing = deliveries.findAttempt(channelId, eventId);
        if (existing.isPresent()) {
            return existing.get().getStatus() != NotificationDelivery.Status.SENT;
        }
        deliveries.save(NotificationDelivery.firstAttempt(
                channelId, eventId, eventType, clock.instant()));
        return true;
    }

    /** Delivered. */
    @Transactional
    public void succeeded(UUID channelId, UUID eventId) {
        deliveries.findAttempt(channelId, eventId)
                .ifPresent(delivery -> delivery.succeeded(clock.instant()));
    }

    /**
     * Not delivered.
     *
     * @param error <strong>already redacted</strong> by {@link SecretRedaction}. Redacting here
     *              instead would be safer and is not available: the caller is the only code that
     *              still knows which credential this attempt used, and a general URL rule alone
     *              cannot cover a secret that is not a URL
     */
    @Transactional
    public void failed(UUID channelId, UUID eventId, String error) {
        deliveries.findAttempt(channelId, eventId)
                .ifPresent(delivery -> delivery.failed(error, clock.instant()));
    }
}
