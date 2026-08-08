package com.tcms.tenancy;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every write the outbox makes to its own rows, and nothing else — the bookkeeping half of
 * dispatch, split from {@link OutboxDispatcher} because the two must be in different transactions.
 *
 * <h2>Why this is a second bean rather than four methods on the dispatcher</h2>
 *
 * <p>Two reasons, and either alone is sufficient:
 *
 * <ol>
 *   <li><strong>A handler's failure must not poison the bookkeeping.</strong> A handler calls a
 *       {@code @Transactional} service; when that throws, Spring marks the surrounding transaction
 *       rollback-only. If the same transaction then tried to record the failure on the row, the
 *       update would be discarded and the commit would raise
 *       {@code UnexpectedRollbackException} — the row would stay leased, retry, fail, and never
 *       accumulate an attempt count or an error message. The failure path is exactly the path that
 *       must survive, so it runs in a transaction of its own, after the handler's has ended.</li>
 *   <li><strong>Spring AOP cannot intercept a self-invocation.</strong> A {@code @Transactional}
 *       method called from another method of the same bean runs with no transaction at all —
 *       §116 shipped that defect and §117 corrected it before it ran. Putting these on a separate
 *       bean makes the boundary real rather than intended.</li>
 * </ol>
 *
 * <p>Each method is its own short transaction over a single locked row: read, one field group, done.
 */
@Service
class OutboxLedger {

    private final OutboxRepository entries;
    private final OutboxProperties properties;

    OutboxLedger(OutboxRepository entries, OutboxProperties properties) {
        this.entries = entries;
        this.properties = properties;
    }

    /**
     * Takes the row for one attempt, if it is still ours to take.
     *
     * <p>The row is re-read under a lock and re-tested here rather than trusted from the due-ids
     * query, because between that query and this call another worker may have claimed it, an
     * operator may have cancelled it, or the whole batch may simply be older than one tick. Empty
     * means "somebody else has it", which is a normal outcome and not a failure.
     *
     * @return the envelope to hand a handler, or empty if the row is no longer due
     */
    @Transactional
    Optional<OutboxMessage> claim(UUID id, Instant now) {
        return entries.findByIdForUpdate(id)
                .filter(entry -> entry.getStatus() == OutboxStatus.PENDING)
                .filter(entry -> !entry.getNextAttemptAt().isAfter(now))
                .map(entry -> {
                    entry.claim(now, properties.lease());
                    return entry.toMessage();
                });
    }

    /** Delivered. Terminal. */
    @Transactional
    void succeeded(UUID id) {
        entries.findByIdForUpdate(id).ifPresent(OutboxEntry::succeeded);
    }

    /**
     * The handler threw. Schedules the next attempt, or stops if this was the last one.
     *
     * <p>The ceiling is read off the row's own attempt count rather than counted here, so a row
     * whose earlier attempts died with the process — leased, never settled, retried after the lease
     * — still exhausts. That is the property {@link OutboxEntry#claim} exists for, and it only holds
     * because the count moves at the claim.
     */
    @Transactional
    void failed(UUID id, Instant now, String error) {
        entries.findByIdForUpdate(id).ifPresent(entry -> {
            if (entry.getAttempts() >= properties.maxAttempts()) {
                entry.stopped(OutboxStatus.FAILED, error);
            } else {
                entry.retryAt(now.plus(properties.backoffAfter(entry.getAttempts())), error);
            }
        });
    }

    /**
     * Nothing about retrying this can help — no handler claims the type, or the payload is
     * unreadable. Terminal, and deliberately not {@link OutboxStatus#FAILED}: the difference is the
     * one question an operator asks of this table.
     */
    @Transactional
    void dead(UUID id, String error) {
        entries.findByIdForUpdate(id).ifPresent(entry -> entry.stopped(OutboxStatus.DEAD, error));
    }
}
