package com.tcms.tenancy;

import java.util.UUID;

/**
 * What a handler is given: one event, out of the row and away from it.
 *
 * <p><strong>The first {@link CompanyScopedEvent} implementation in production code.</strong> That
 * interface has carried the contract since M0 with nothing implementing it — the envelope every
 * cross-module event must satisfy, so that a handler running with no company (the task decorator
 * clears it, and {@code CompanyContext} is deliberately not inheritable) has exactly one trustworthy
 * source to open one from. This is that source, and {@link OutboxDispatcher} opens the context from
 * {@link #companyId()} and from nothing else.
 *
 * <p>Deliberately <strong>not</strong> the entity. A handler holding a managed {@link OutboxEntry}
 * could mutate delivery state from inside a delivery — set its own row {@code SENT} and then throw,
 * or bump the attempt count — and the dispatcher's bookkeeping would be a suggestion. It is also the
 * property that keeps a handler runnable outside any transaction of ours, which is the whole shape
 * of {@link OutboxDispatcher}.
 *
 * @param payload the event body as a JSON document, exactly as the producer wrote it. A
 *                {@code String} rather than a parsed type on purpose: the producing and consuming
 *                modules may not share a class (plan §3 — see {@link OutboxEntry}'s header), so the
 *                consumer parses it into a record of its own. That record and the producer's are two
 *                declarations of one shape, and the thing that keeps them honest is a test on each
 *                side rather than the compiler.
 */
public record OutboxMessage(
        UUID companyId,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        String payload) implements CompanyScopedEvent {
}
