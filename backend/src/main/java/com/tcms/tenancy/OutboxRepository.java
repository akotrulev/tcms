package com.tcms.tenancy;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * The outbox's two reads: what is due, and one row taken for delivery.
 *
 * <p>Both are HQL rather than native SQL, and for once that is the security-relevant choice rather
 * than a stylistic one. A native query drops the Hibernate company filter, leaving only RLS between
 * one client's events and another's — {@code AuditEventRepository}'s header makes the same call on
 * the same grounds, and the stake is higher here: this table's rows are handed to code that then
 * writes with them.
 */
// A repository interface must be @Transactional or its QUERY METHODS run with no transaction at
// all — and therefore with no app.current_company, which under RLS means zero rows. See
// ProjectRepository's header and RepositoryTransactionTest, which fails the build on a repository
// missing this.
@Transactional(readOnly = true)
public interface OutboxRepository extends JpaRepository<OutboxEntry, UUID> {

    /**
     * The ids of the active company's due events, oldest schedule first.
     *
     * <p><strong>Ids, not entities, and the difference is the whole dispatch design.</strong> The
     * rows are claimed and settled one at a time, each in its own short transaction, so loading a
     * batch of managed entities here would hand the dispatcher objects belonging to a transaction
     * that ended — stale the moment another worker touches one.
     *
     * <p>{@code nextAttemptAt <= now} is the only due-ness test, with no {@code IS NULL} arm,
     * because {@link OutboxEntry#pending} always sets the column. That is what lets
     * {@code outbox_pending_idx} — {@code V1}'s partial index on exactly this predicate — serve the
     * query as written.
     */
    @Query("""
            SELECT e.id FROM OutboxEntry e
             WHERE e.status = com.tcms.tenancy.OutboxStatus.PENDING
               AND e.nextAttemptAt <= :now
             ORDER BY e.nextAttemptAt ASC, e.createdAt ASC
            """)
    List<UUID> findDueIds(@Param("now") Instant now, Limit limit);

    /**
     * Whether the active company already has an event of this type that <strong>nobody has started
     * delivering</strong> — the test behind {@link Outbox#recordCoalesced}.
     *
     * <p>{@code attempts = 0} is the load-bearing half and it is not "still pending". A row that has
     * been claimed is one a handler may already have read the database for, before the write now
     * being recorded had committed; folding this notice into that one would mean the newest change
     * is never projected until the next scheduled tick, which is the staleness the event exists to
     * remove. An unclaimed row has been read by nobody, so it can still stand for both.
     */
    @Query("""
            SELECT count(e) > 0 FROM OutboxEntry e
             WHERE e.eventType = :eventType
               AND e.status = com.tcms.tenancy.OutboxStatus.PENDING
               AND e.attempts = 0
            """)
    boolean hasUnclaimed(@Param("eventType") String eventType);

    /**
     * One row, locked for the length of the claiming transaction.
     *
     * <p><strong>The lock guards the claim, not the delivery.</strong> It is held across a read and
     * a single update — microseconds — and its only job is to stop two workers incrementing the same
     * attempt and both dispatching. What keeps two workers from overlapping on a <em>slow</em>
     * delivery is the lease {@link OutboxEntry#claim} writes, which is visible to every other worker
     * the instant the claim commits and survives this process dying.
     *
     * <p>That division is why there is no {@code SKIP LOCKED} here, which the slice plan originally
     * called for: skipping matters when the lock is held for the duration of the work, and here it
     * is not. A second worker blocked for the length of one {@code UPDATE} then re-reads the row and
     * finds it leased, which is the same outcome one statement later.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM OutboxEntry e WHERE e.id = :id")
    Optional<OutboxEntry> findByIdForUpdate(@Param("id") UUID id);
}
