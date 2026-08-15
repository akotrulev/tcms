package com.tcms.integration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

// @Transactional for the reason spelled out on ProjectRepository: without it a query method
// DECLARED on the interface runs outside any transaction, app.current_company is never set, and
// every RLS-protected read returns zero rows — fail-closed and silent. RepositoryTransactionTest
// derives the repository list from the live context, so omitting it fails the build.
@Transactional(readOnly = true)
interface NotificationChannelRepository extends JpaRepository<NotificationChannel, UUID> {

    /** One project's channels, in a stable order so the settings screen does not reshuffle. */
    @Query("SELECT c FROM NotificationChannel c WHERE c.projectId = :projectId ORDER BY c.name")
    List<NotificationChannel> findInProject(@Param("projectId") UUID projectId);

    /**
     * One channel, resolved <em>within its project</em>.
     *
     * <p>Never {@code findById} at a call site: a channel id from another project in the same
     * company would otherwise resolve, and the caller's gate was asked about the project rather
     * than about the row.
     */
    @Query("SELECT c FROM NotificationChannel c WHERE c.projectId = :projectId AND c.id = :channelId")
    Optional<NotificationChannel> findInProject(
            @Param("projectId") UUID projectId, @Param("channelId") UUID channelId);

    /**
     * The enabled channels in this company that are subscribed to an event type — the handler's
     * one read.
     *
     * <p>A join rather than two queries because the answer is one set and the alternative is a
     * channel list this module would then filter in memory, which is the same query with the
     * predicate moved somewhere it cannot use the index.
     *
     * <p>{@code enabled} is applied here rather than at the call site so that "switched off"
     * cannot mean "still delivered but not shown" in some later caller.
     */
    @Query("""
            SELECT c FROM NotificationChannel c
             WHERE c.projectId = :projectId
               AND c.enabled = TRUE
               AND EXISTS (SELECT 1 FROM NotificationSubscription s
                            WHERE s.channelId = c.id AND s.eventType = :eventType)
             ORDER BY c.name
            """)
    List<NotificationChannel> findSubscribed(
            @Param("projectId") UUID projectId, @Param("eventType") String eventType);
}
