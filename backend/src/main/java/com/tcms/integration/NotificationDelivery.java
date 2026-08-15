package com.tcms.integration;

import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * What was attempted, to which channel, and what came back.
 *
 * <h2>It is the operator's log and the handler's idempotency record, in that order</h2>
 *
 * <p>The second job is the one that constrains the shape. Outbox delivery is
 * <strong>at least once</strong> — a handler that posted successfully and then lost the settle
 * runs again on the next tick — so without a durable record of what already went out, one crash
 * between the POST and the settle is a duplicate message, and the retry ladder makes it up to
 * eight of them. The unique key is {@code (company_id, channel_id, event_id)} and
 * {@link NotificationDispatchService} skips a channel whose row is already {@link Status#SENT}.
 *
 * <p><strong>{@code eventId} is the producer's, not the outbox row's.</strong>
 * {@code OutboxMessage} deliberately carries no row identity, so a handler cannot mutate the
 * delivery state of the row it is being delivered from; the occurrence therefore identifies itself
 * inside the payload.
 *
 * <h2>A row exists as soon as an attempt is made</h2>
 *
 * <p>Including a failed first attempt. An empty history and a webhook that has never once worked
 * must not look the same on a screen, which is the reading "record only successes" produces.
 */
@Entity
@Table(name = "notification_delivery")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class NotificationDelivery {

    /** How an attempt ended. Terminal either way — a retry updates the row rather than adding one. */
    public enum Status {
        SENT,
        FAILED
    }

    /**
     * How much of a failure is kept.
     *
     * <p>{@code OutboxEntry.MAX_ERROR_LENGTH}'s reason, one table over: the column is {@code TEXT},
     * nothing prunes this table, and the only reader is a person deciding whether the webhook is
     * broken. The first line and a bit answers that.
     */
    static final int MAX_ERROR_LENGTH = 1000;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "channel_id", nullable = false, updatable = false)
    private UUID channelId;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    /**
     * Attempts against <em>this channel</em> for this occurrence.
     *
     * <p>Not the outbox row's count, which covers the whole event across every subscribed channel:
     * a fan-out of three where one webhook is dead makes the two numbers different, and this is
     * the one an operator is asking about.
     */
    @Column(name = "attempt", nullable = false)
    private int attempt;

    /** Already redacted by {@link SecretRedaction} — see the column's own comment in {@code V54}. */
    @Column(name = "error")
    private String error;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA only. */
    protected NotificationDelivery() {
    }

    static NotificationDelivery firstAttempt(
            UUID channelId, UUID eventId, String eventType, Instant now) {

        NotificationDelivery created = new NotificationDelivery();
        created.id = UUID.randomUUID();
        created.companyId = CompanyContext.require();
        created.channelId = channelId;
        created.eventId = eventId;
        created.eventType = eventType;
        created.status = Status.FAILED;
        created.attempt = 0;
        created.createdAt = now;
        created.updatedAt = now;
        return created;
    }

    /** Delivered. Clears the previous failure's text, which no longer describes this row. */
    void succeeded(Instant now) {
        this.attempt = attempt + 1;
        this.status = Status.SENT;
        this.error = null;
        this.updatedAt = now;
    }

    /** Not delivered. {@code error} is redacted by the caller, never by the transport. */
    void failed(String error, Instant now) {
        this.attempt = attempt + 1;
        this.status = Status.FAILED;
        this.error = truncate(error);
        this.updatedAt = now;
    }

    private static String truncate(String error) {
        if (error == null || error.length() <= MAX_ERROR_LENGTH) {
            return error;
        }
        return error.substring(0, MAX_ERROR_LENGTH);
    }

    UUID getId() {
        return id;
    }

    UUID getChannelId() {
        return channelId;
    }

    UUID getEventId() {
        return eventId;
    }

    String getEventType() {
        return eventType;
    }

    Status getStatus() {
        return status;
    }

    int getAttempt() {
        return attempt;
    }

    String getError() {
        return error;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
