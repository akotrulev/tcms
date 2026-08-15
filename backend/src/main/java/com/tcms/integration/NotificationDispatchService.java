package com.tcms.integration;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Sends one rendered notice to every channel that asked for it.
 *
 * <h2>Nothing here is transactional, and that is the design</h2>
 *
 * <p>Every database access goes through {@link NotificationLedger}, which is a separate bean so
 * that its {@code @Transactional} methods actually take effect. What is left in this class is the
 * part that must <em>not</em> be inside a transaction: a POST to somebody else's server, three
 * times over for a fan-out of three. Holding a connection open across that is the failure
 * {@code V1}'s comment on the {@code outbox} table names — <em>"a failing Slack webhook must never
 * roll back a test result"</em> — arriving one layer further in.
 *
 * <h2>One channel's failure does not stop the others, and the event still fails</h2>
 *
 * <p>Both halves matter. A dead webhook must not silence the two channels beside it, so every
 * target is attempted; and the outbox must still retry, so the accumulated failure is thrown at
 * the end. The channels that succeeded are skipped on the retry by
 * {@link NotificationLedger#beginAttempt}, which is what makes throwing safe rather than a way of
 * sending everything twice.
 *
 * <h2>The exception is redacted before it goes anywhere durable</h2>
 *
 * <p>An HTTP client's message quotes the URL it was posting to, and for this caller that URL
 * <em>is</em> the credential. It is redacted on the way into the delivery log and on the way into
 * the log line, and the exception this method finally throws carries only the channel names —
 * because that exception is written into {@code outbox.last_error}, which is a third durable place
 * a secret would otherwise land.
 */
@Service
public class NotificationDispatchService {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatchService.class);

    private final NotificationLedger ledger;
    private final Map<NotificationChannelKind, NotificationChannelPort> ports;

    NotificationDispatchService(NotificationLedger ledger, List<NotificationChannelPort> ports) {
        this.ledger = ledger;
        this.ports = index(ports);
    }

    /**
     * One port per kind, checked rather than assumed.
     *
     * <p>Two beans claiming one kind is a silent coin toss over which transport a company's
     * channels use — unlike {@code OutboxHandler}, where several handlers per event type is the
     * feature. Failing the context is the right answer because it is decidable before anything
     * runs.
     */
    private static Map<NotificationChannelKind, NotificationChannelPort> index(
            List<NotificationChannelPort> ports) {

        Map<NotificationChannelKind, NotificationChannelPort> byKind =
                new EnumMap<>(NotificationChannelKind.class);
        for (NotificationChannelPort port : ports) {
            NotificationChannelPort clash = byKind.put(port.kind(), port);
            if (clash != null) {
                throw new IllegalStateException(
                        "Two NotificationChannelPort beans claim " + port.kind() + ": "
                                + clash.getClass().getSimpleName() + " and "
                                + port.getClass().getSimpleName());
            }
        }
        return byKind;
    }

    /**
     * @param eventId  the occurrence, from the payload — the delivery log's idempotency key
     * @param failures how many results failed, or null when the event has no verdicts at all. Null
     *                 matches every threshold; see {@code NotificationSubscription#wants}
     * @throws NotificationDeliveryFailure if any subscribed channel could not be told, so that the
     *                                     outbox retries the event on its backoff
     */
    public void dispatch(
            UUID projectId,
            String eventType,
            UUID eventId,
            Integer failures,
            RenderedNotification notification) {

        List<String> failedChannels = new ArrayList<>();

        for (UUID channelId : ledger.subscribed(projectId, eventType, failures)) {
            if (!ledger.beginAttempt(channelId, eventId, eventType)) {
                continue;
            }
            deliverOne(channelId, eventId, notification).ifPresent(failedChannels::add);
        }

        if (!failedChannels.isEmpty()) {
            throw new NotificationDeliveryFailure(eventType, failedChannels);
        }
    }

    /**
     * @return the channel's name if it could not be told, empty if it was
     */
    private Optional<String> deliverOne(
            UUID channelId, UUID eventId, RenderedNotification notification) {

        // Resolved inside the loop and inside the try: a channel deleted between the fan-out and
        // this line is ordinary — nothing holds a lock across a delivery — and a channel with an
        // unusable config has to fail against its own row rather than against the whole event.
        String name = channelId.toString();
        String secret = null;
        try {
            ChannelTarget target = ledger.resolve(channelId).orElseThrow(() ->
                    new IllegalStateException("The channel was deleted before it could be told"));
            name = target.name();
            secret = target.config().webhookUrl();

            NotificationChannelPort port = ports.get(target.config().kind());
            if (port == null) {
                throw new IllegalStateException(
                        "No adapter is registered for channel kind " + target.config().kind());
            }
            port.deliver(notification, target.config());
            ledger.succeeded(channelId, eventId);
            return Optional.empty();
        } catch (RuntimeException failure) {
            String described = SecretRedaction.apply(
                    failure.getClass().getSimpleName() + ": " + failure.getMessage(), secret);
            ledger.failed(channelId, eventId, described);
            // WARN rather than ERROR: one failed attempt is what the outbox's backoff is for, and
            // notification_delivery is where a persistent problem becomes visible and durable.
            // The exception is NOT passed to the logger — its stack trace carries the URL.
            log.warn("Notification delivery to channel '{}' failed: {}", name, described);
            return Optional.of(name);
        }
    }

    /**
     * Some channel was not told.
     *
     * <p>Its message names channels and nothing else, deliberately: this exception is caught by
     * {@code OutboxDispatcher} and written into {@code outbox.last_error}, which is a durable row
     * an operator browses. Carrying the underlying cause would carry the URL that caused it —
     * {@code notification_delivery.error} is where the redacted detail lives, keyed to the channel
     * this names.
     */
    static final class NotificationDeliveryFailure extends RuntimeException {

        NotificationDeliveryFailure(String eventType, List<String> channels) {
            super(eventType + " could not be delivered to " + channels.size()
                    + " channel(s): " + String.join(", ", channels)
                    + ". See notification_delivery for each one's redacted error.");
        }
    }
}
