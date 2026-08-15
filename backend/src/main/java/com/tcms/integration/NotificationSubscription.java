package com.tcms.integration;

import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * One event type a channel wants, and the one filter this slice ships.
 *
 * <h2>{@code minFailures} is a predicate on the event, not an event of its own</h2>
 *
 * <p>"Run failed threshold" reads like a fourth event name and is refused as one. Modelled here,
 * the producer never learns what any subscriber considers alarming — modelled as an event,
 * {@code execution} would have to record a distinct name against a number configured in this
 * module, which is an edge plan §3 forbids and which breaks anyway the moment two subscribers
 * disagree about what a bad run is.
 *
 * <p>Zero means every occurrence and is what a subscription is created with. It is not nullable,
 * so "no threshold" has one spelling rather than two for every reader to handle.
 *
 * <h2>Meaningless for an event with no failure count, and left at zero rather than null</h2>
 *
 * <p>{@code work.item_assigned} carries no verdicts. {@link #wants} answers true for any
 * occurrence whose count is unknown, so the column is inert there rather than being a filter that
 * silently never matches — which is the reading a nullable column would invite.
 */
@Entity
@Table(name = "notification_subscription")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class NotificationSubscription {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "channel_id", nullable = false, updatable = false)
    private UUID channelId;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @Column(name = "min_failures", nullable = false)
    private int minFailures;

    /** JPA only. */
    protected NotificationSubscription() {
    }

    static NotificationSubscription of(UUID channelId, String eventType, int minFailures) {
        NotificationSubscription created = new NotificationSubscription();
        created.id = UUID.randomUUID();
        created.companyId = CompanyContext.require();
        created.channelId = channelId;
        created.eventType = eventType;
        created.minFailures = minFailures;
        return created;
    }

    /**
     * Whether this subscription wants an occurrence with the given failure count.
     *
     * @param failures how many results failed, or null when the event type has no such notion —
     *                 which is a match, because a threshold nobody set on a count nobody has must
     *                 not silence a channel that asked to be told
     */
    boolean wants(Integer failures) {
        return failures == null || failures >= minFailures;
    }

    UUID getId() {
        return id;
    }

    UUID getChannelId() {
        return channelId;
    }

    String getEventType() {
        return eventType;
    }

    int getMinFailures() {
        return minFailures;
    }
}
