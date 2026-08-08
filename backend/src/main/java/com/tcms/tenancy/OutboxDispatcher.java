package com.tcms.tenancy;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Delivers the active company's due events, one at a time: claim, hand to a handler, settle.
 *
 * <h2>Three transactions per event, and none of them contains the handler</h2>
 *
 * <p>The claim is one transaction ({@link OutboxLedger#claim}), the handler runs in none of ours,
 * and the settle is another. That is the arrangement, and it is the answer to the failure every
 * simpler shape has:
 *
 * <ul>
 *   <li><strong>One transaction around everything</strong> — a handler's exception marks it
 *       rollback-only, so the attempt count and the error message it was supposed to record are
 *       discarded along with it. The row retries for ever, and the table says nothing has ever gone
 *       wrong.</li>
 *   <li><strong>A lock held across the handler</strong> — a database connection is held open for the
 *       length of somebody else's work, which for the notifications this table exists to carry is a
 *       network call to a third party. {@code V1}'s comment is about exactly that call.</li>
 * </ul>
 *
 * <p>What replaces the long lock is the lease {@link OutboxEntry#claim} writes: visible to every
 * other worker the moment the claim commits, and it expires on its own if this process dies. The
 * cost is at-least-once delivery, which {@link OutboxHandler} carries as a contract.
 *
 * <h2>This method must not be called inside a transaction, and refuses to be</h2>
 *
 * <p>Every property above dissolves if there is one: the ledger's three transactions would all join
 * it, the settle would be inside the handler's failure, and the whole class would be the "one
 * transaction around everything" it is written to avoid. That is invisible at runtime — everything
 * still delivers, on the happy path — so it is a refusal rather than a comment.
 *
 * <h2>Company context is the caller's to establish</h2>
 *
 * <p>{@link OutboxScheduler} opens it per company, for the reason its header gives at length: the
 * table is under {@code FORCE ROW LEVEL SECURITY}, so a dispatcher run without a context sees zero
 * rows and reports a successful tick having delivered nothing. The context around a handler is
 * re-opened from the row's own {@code companyId} rather than inherited — {@link CompanyScopedEvent}'s
 * rule, and here the two are the same company by construction, which is asserted rather than assumed
 * because the day they differ is the day one client's handler writes into another's tables.
 */
@Service
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxRepository entries;
    private final OutboxLedger ledger;
    private final OutboxProperties properties;
    private final Clock clock;
    private final Map<String, List<OutboxHandler>> handlers;

    OutboxDispatcher(
            OutboxRepository entries,
            OutboxLedger ledger,
            OutboxProperties properties,
            Clock clock,
            List<OutboxHandler> handlers) {
        this.entries = entries;
        this.ledger = ledger;
        this.properties = properties;
        this.clock = clock;
        this.handlers = index(handlers);
    }

    /**
     * Every handler, grouped by the type it claims — several per type is normal.
     *
     * <p>An earlier draft failed the context when two handlers named one type, on the reasoning
     * that it is a code error and therefore checkable before anything runs. That was wrong about
     * the product: {@code execution.results_changed} is a fact that moves both the fact log in
     * {@code reporting} and the health signals in {@code repository}, neither module may call the
     * other, and one row is precisely how the two are told. Fan-out is the feature, not a mistake
     * to catch.
     */
    private static Map<String, List<OutboxHandler>> index(List<OutboxHandler> handlers) {
        Map<String, List<OutboxHandler>> byType = new LinkedHashMap<>();
        for (OutboxHandler handler : handlers) {
            byType.computeIfAbsent(handler.eventType(), type -> new ArrayList<>()).add(handler);
        }
        return byType;
    }

    /**
     * Delivers up to {@code batchSize} of the active company's due events.
     *
     * @return how many were delivered successfully — for the log line, and for a test that would
     *         otherwise have to assert on the absence of something
     */
    public int dispatchDue() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "OutboxDispatcher.dispatchDue must not run inside a transaction — the claim, "
                            + "the handler and the settle are deliberately three separate ones. "
                            + "See the class comment.");
        }
        UUID company = CompanyContext.require();
        // The repository's own @Transactional(readOnly = true) starts the transaction this read
        // needs; without one there is no app.current_company and RLS answers zero rows.
        List<UUID> due = entries.findDueIds(clock.instant(), Limit.of(properties.batchSize()));

        int delivered = 0;
        for (UUID id : due) {
            if (deliver(company, id)) {
                delivered++;
            }
        }
        return delivered;
    }

    private boolean deliver(UUID company, UUID id) {
        Optional<OutboxMessage> claimed = ledger.claim(id, clock.instant());
        if (claimed.isEmpty()) {
            // Somebody else has it, or it is no longer due. Normal, and not worth a log line at
            // any level a person reads.
            return false;
        }
        OutboxMessage message = claimed.get();

        if (!company.equals(message.companyId())) {
            // Unreachable through RLS and asserted anyway: the whole async design rests on a
            // handler's company coming from the envelope, and the failure it guards against is a
            // handler writing into another client's tables with nothing on screen to show it.
            ledger.dead(id, "Event belongs to company " + message.companyId()
                    + " but was claimed under " + company);
            log.error("Outbox entry {} crossed a company boundary: row {} claimed under {}",
                    id, message.companyId(), company);
            return false;
        }

        List<OutboxHandler> claimants = handlers.getOrDefault(message.eventType(), List.of());
        if (claimants.isEmpty()) {
            ledger.dead(id, "No handler claims the event type '" + message.eventType() + "'");
            log.warn("No OutboxHandler for event type '{}' — entry {} is DEAD. This is what a "
                            + "deployment with the consuming module disabled looks like.",
                    message.eventType(), id);
            return false;
        }

        try {
            // Re-opened from the envelope rather than left to the caller's context: CompanyScopedEvent's
            // rule, and it keeps the handler correct if this class is ever driven from somewhere else.
            try (var ignored = CompanyContext.open(message.companyId())) {
                // ONE row for all of them, so the first exception ends the attempt and the retry
                // re-runs the handlers that already succeeded. That is affordable only because
                // every handler is required to be idempotent, and it is the trade being made
                // rather than one being overlooked: the alternative is per-handler delivery state,
                // which is a second table and a second thing to get wrong for a fan-out of two.
                for (OutboxHandler handler : claimants) {
                    handler.handle(message);
                }
            }
            ledger.succeeded(id);
            return true;
        } catch (RuntimeException failure) {
            Instant now = clock.instant();
            ledger.failed(id, now, describe(failure));
            // WARN and not ERROR: one failed attempt is what the retry is for. The row's own
            // attempts column is where a persistent problem becomes visible, and it is durable,
            // unlike this line.
            log.warn("Outbox delivery failed for entry {} ({}), will retry", id,
                    message.eventType(), failure);
            return false;
        }
    }

    /**
     * The message a person reading {@code last_error} needs, which is the exception's class as well
     * as its text — a bare {@code getMessage()} on an NPE is null, and "null" in that column has
     * ended more than one investigation in the wrong place.
     */
    private static String describe(RuntimeException failure) {
        return failure.getClass().getSimpleName() + ": " + failure.getMessage();
    }
}
