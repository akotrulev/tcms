package com.tcms.integration;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

// @Transactional for ProjectRepository's reason — see NotificationChannelRepository.
@Transactional(readOnly = true)
interface NotificationSubscriptionRepository extends JpaRepository<NotificationSubscription, UUID> {

    /** Everything one channel is subscribed to — the settings screen, and the reconcile's before. */
    @Query("SELECT s FROM NotificationSubscription s WHERE s.channelId = :channelId ORDER BY s.eventType")
    List<NotificationSubscription> findForChannel(@Param("channelId") UUID channelId);

    /**
     * One subscription per channel, for the channels a dispatch has already narrowed to.
     *
     * <p>A single query over the whole fan-out rather than one per channel: a company with twenty
     * channels subscribed to run completions would otherwise issue twenty reads per delivered
     * event, and the threshold each one carries is the only reason the rows are needed at all.
     */
    @Query("""
            SELECT s FROM NotificationSubscription s
             WHERE s.eventType = :eventType AND s.channelId IN :channelIds
            """)
    List<NotificationSubscription> findForChannels(
            @Param("eventType") String eventType, @Param("channelIds") List<UUID> channelIds);
}
