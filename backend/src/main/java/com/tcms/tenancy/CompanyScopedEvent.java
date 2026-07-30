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
 * {@code CompletableFuture} nobody joins. Wiring that up belongs to the module that
 * publishes the first real event.
 *
 * <p>Cross-module events are how modules other than {@code tenancy}, {@code identity}
 * and {@code project} communicate at all (plan §3, enforced by ArchUnit), so every one
 * of them passes through here. Making the company id part of the contract rather than a
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
