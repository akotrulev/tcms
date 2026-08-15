package com.tcms.integration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

// @Transactional for ProjectRepository's reason — see NotificationChannelRepository.
@Transactional(readOnly = true)
interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, UUID> {

    /**
     * The row that says whether this channel has already been told about this occurrence.
     *
     * <p>The idempotency lookup, and the reason the unique constraint names these three columns.
     * At-least-once delivery makes a second run of a successful handler ordinary rather than
     * exceptional, so this is a normal-path read.
     */
    @Query("""
            SELECT d FROM NotificationDelivery d
             WHERE d.channelId = :channelId AND d.eventId = :eventId
            """)
    Optional<NotificationDelivery> findAttempt(
            @Param("channelId") UUID channelId, @Param("eventId") UUID eventId);

    /** One channel's history, newest first — what an operator opens when a webhook looks dead. */
    @Query("""
            SELECT d FROM NotificationDelivery d
             WHERE d.channelId = :channelId ORDER BY d.createdAt DESC
            """)
    List<NotificationDelivery> findForChannel(@Param("channelId") UUID channelId, Limit limit);
}
