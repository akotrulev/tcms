package com.tcms.tenancy;

/**
 * The consumer side of the outbox: one bean per event type, in whichever module cares.
 *
 * <h2>Resolved by string, and that is the design rather than a shortcut</h2>
 *
 * <p>Plan §3 forbids a module from depending on any module outside the foundation, with no exemption
 * for an event type ({@code ArchitectureRulesTest}), so a handler in {@code reporting} cannot name a
 * class in {@code execution}. {@link #eventType()} is therefore a {@code String} matched against the
 * column — the only kind of reference that crosses. See {@link OutboxEntry}'s header for what that
 * costs and what checks it instead.
 *
 * <h2>Two rules, and both are the caller's problem if broken</h2>
 *
 * <ul>
 *   <li><strong>Idempotent.</strong> Delivery is at least once: a handler that succeeded and then
 *       lost the settle — a crash, a lock timeout on the update — runs again on the next tick. See
 *       {@link OutboxEntry#claim}. A handler that cannot be run twice must make itself so, and
 *       saying which of its writes is keyed on what is the honest way to do it.</li>
 *   <li><strong>Several handlers may claim one event type, and their order is undefined.</strong>
 *       Two modules genuinely care about the same fact — {@code execution.results_changed} moves
 *       both the fact log in {@code reporting} and the health signals in {@code repository} — and
 *       neither may call the other, so fanning out here is the only place it can happen. The price
 *       is stated where it is paid, in {@link OutboxDispatcher}: they share one row, so one
 *       handler's failure retries all of them. A handler that depends on another having run first
 *       is a handler in the wrong place — put both effects in one, as {@code reporting} does with
 *       the fact refresh and the rollups, which §101 requires to move on one clock.</li>
 *   <li><strong>Zero handlers is a {@link OutboxStatus#DEAD} row</strong> naming the type, not a
 *       silent success. It cannot be checked at startup — an event type is a string that exists in
 *       data, and a deployment with the consuming module disabled legitimately has none.</li>
 * </ul>
 *
 * <h2>Company context is already open, and a handler must not change it</h2>
 *
 * <p>{@link OutboxDispatcher} opens {@link CompanyContext} from the message's {@code companyId}
 * around the call, so a handler reads and writes company-owned data normally. Opening a
 * <em>different</em> company inside a handler is the leak the whole envelope design exists to
 * prevent; if a handler needs another company's data, it is the wrong handler.
 *
 * <h2>Throwing is how a handler reports a failure, and there is no other way</h2>
 *
 * <p>A handler returns {@code void} and its exception is caught by the dispatcher, recorded on the
 * row and retried on a backoff. Swallowing an error internally and returning normally marks the row
 * {@code SENT} — which is the "claim with no evidence behind it" this repository has paid for four
 * times over, and here it is invisible by construction, because a delivered row is indistinguishable
 * from a delivered one.
 */
public interface OutboxHandler {

    /**
     * The dotted verb this handler claims, matched exactly against {@code outbox.event_type}.
     *
     * <p>Not a pattern and not a prefix. A handler claiming {@code execution.*} looks convenient
     * and makes "who handles this event" unanswerable without reading every implementation in
     * every module — which is precisely the question an operator has when a row is sitting in
     * {@link OutboxStatus#DEAD}. Several handlers naming the <em>same</em> type is fine and is not
     * that: the question stays answerable by grep.
     */
    String eventType();

    /**
     * Deliver one event. Throw to have it retried.
     *
     * <p>Runs <strong>outside any transaction the outbox holds</strong>, on purpose (see
     * {@link OutboxDispatcher}), so a handler that needs one opens its own — normally by calling a
     * {@code @Transactional} service, which is where a handler's work should live anyway. A handler
     * with logic of its own is a second copy of something a service already does.
     */
    void handle(OutboxMessage message);
}
