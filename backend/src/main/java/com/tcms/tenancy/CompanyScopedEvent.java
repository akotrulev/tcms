package com.tcms.tenancy;

import java.util.UUID;

/**
 * The envelope contract every domain event crossing a module boundary must satisfy:
 * it carries the company the work belongs to.
 *
 * <p><strong>This is the only thing an async handler may re-establish context from.</strong>
 * {@link CompanyContext} is deliberately not an {@code InheritableThreadLocal} (see its
 * class comment), so a handler running on a worker thread starts with no company at all.
 * It must open one, and the only trustworthy source is the envelope — not the thread it
 * happens to be running on, and not a lookup keyed on something the payload references.
 *
 * <pre>{@code
 * @Async
 * @TransactionalEventListener(phase = AFTER_COMMIT)
 * void on(ResultsIngested event) {
 *     try (var ignored = CompanyContext.openFor(event)) {
 *         // company-owned data is reachable here, and only this company's
 *     }
 * }
 * }</pre>
 *
 * <p>The listener above returns {@code void}, which normally hides failures — an
 * {@code @Async} method with no future has nowhere to report one. That is accepted for
 * event handlers specifically, and the answer is not a return type: V1 already carries an
 * {@code outbox} table, and a handler that fails must land there rather than in a
 * {@code CompletableFuture} nobody joins. ~~Wiring that up belongs to the module that
 * publishes the first real event.~~
 *
 * <p><strong>That is built (round 3 #52), and it did not land where this sentence expected.</strong>
 * The mechanism is {@link Outbox} / {@link OutboxDispatcher} here in {@code tenancy}, not in the
 * publishing module — and the promise is kept by a different route than "catch what the handler
 * threw": <strong>delivery goes through the outbox in the first place</strong>, so there is no
 * {@code @Async} handler to fail. A handler's exception is recorded on its own row with an attempt
 * count, a schedule and a message, and the row is retried; the {@code @TransactionalEventListener}
 * sketched above is not how any cross-module event is delivered in this product.
 *
 * <p>{@link OutboxMessage} is the first implementation of this interface in production code, and
 * for the reason the next paragraph gives it is likely to remain the only one: a Spring event
 * carrying a type across a module boundary is forbidden here, so the envelope's real job is to be
 * the thing a dispatcher opens a company from.
 *
 * <p>Cross-module events are how modules other than {@code tenancy}, {@code identity},
 * {@code project} and {@code storage} communicate at all (plan §3, enforced by ArchUnit), so every
 * one of them passes through here. And note what that rule does <em>not</em> exempt: an event
 * <em>type</em> is a dependency like any other, so the publishing module's class is unreachable
 * from the consuming one. That is why the outbox carries a {@code TEXT} event type and a
 * {@code JSONB} payload rather than an object — see {@link OutboxEntry}. Making the company id part of the contract rather than a
 * convention means a new event that forgets it does not compile.
 *
 * @see CompanyContextTaskDecorator the guard that makes forgetting fail closed
 */
public interface CompanyScopedEvent {

    /**
     * The company this event's work belongs to. Never {@code null} — an event with no
     * company has no business being published on a company-owned surface.
     */
    UUID companyId();
}
