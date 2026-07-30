package com.tcms.tenancy;

import java.util.Optional;
import java.util.UUID;

/**
 * The active company for the current thread — the single source the Hibernate filter
 * and the {@code app.current_company} RLS GUC are fed from.
 *
 * <p><strong>The context is never populated from client input.</strong> Not from a
 * request parameter, not from a header, not from a path segment. It comes from the
 * authenticated session (ERD §0.1 point 2). A URL may carry the company slug for
 * readability, but it is <em>validated against</em> the session, never trusted as its
 * source.
 *
 * <h2>Why the API is scoped rather than a setter</h2>
 *
 * <p>A bare {@code set()} leaks: whatever ran last leaves its company active on a
 * pooled thread, and the next task on that thread inherits it. That is invisible in
 * synchronous tests and is the realistic way a multi-company instance leaks
 * (plan §0.2). {@link #open(UUID)} returns an {@link AutoCloseable} that restores the
 * previous value, so a context cannot outlive its block:
 *
 * <pre>{@code
 * try (var ignored = CompanyContext.open(companyId)) {
 *     // filter + RLS GUC apply to every transaction opened in here
 * }
 * }</pre>
 *
 * <h2>Async handlers must re-establish, never inherit</h2>
 *
 * <p>This is deliberately <em>not</em> an {@code InheritableThreadLocal}. An
 * {@code @Async} handler or a scheduled job must open the context from its event
 * envelope's {@code companyId}, because the thread it runs on may have been recycled
 * from an unrelated company's request. Inheriting silently would make a leak look
 * like correct behaviour under test.
 *
 * <h2>A note on the "no static mutable state" rule</h2>
 *
 * <p>The {@code be-java} operating rules forbid static mutable state, and a
 * {@code ThreadLocal} is exactly that. The conflict is deliberate and noted: an
 * ambient context must be readable from code that has no injection point — Hibernate
 * filter parameter resolution, a transaction manager hook, an event handler — so a
 * request-scoped bean cannot serve it. The mitigation is that the mutable surface is
 * private and reachable only through a scope that always restores.
 */
public final class CompanyContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private CompanyContext() {
    }

    /**
     * Activates {@code companyId} until the returned scope is closed, restoring
     * whatever was active before. Nesting is supported so an operator task can switch
     * company and return.
     *
     * @param companyId the company to activate; never {@code null}
     */
    public static Scope open(UUID companyId) {
        if (companyId == null) {
            throw new IllegalArgumentException(
                    "companyId must not be null — use clear() to run without a company context");
        }
        UUID previous = CURRENT.get();
        CURRENT.set(companyId);
        return () -> restore(previous);
    }

    /**
     * Activates the company an event belongs to — the one correct way for an async
     * handler or a scheduled job to establish context.
     *
     * <p>Exists so the correct path is also the shortest one. The alternative a handler
     * reaches for when this is missing is the ambient thread value, which is exactly the
     * leak {@link CompanyContextTaskDecorator} exists to make impossible.
     */
    public static Scope openFor(CompanyScopedEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        return open(event.companyId());
    }

    /**
     * Runs without any company active. The correct state for instance-level operator
     * work (provisioning, the SYSTEM-scope grant lookup during login) — and the state
     * in which RLS must yield <em>zero</em> rows rather than all rows.
     */
    public static Scope clear() {
        UUID previous = CURRENT.get();
        CURRENT.remove();
        return () -> restore(previous);
    }

    /** The active company, or empty when running without company context. */
    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * The active company, or {@code null}. For the transaction-manager hook, which
     * must distinguish "no context" (a legitimate instance-level transaction) from an
     * error without allocating.
     */
    static UUID currentOrNull() {
        return CURRENT.get();
    }

    /**
     * The active company, failing loudly when absent.
     *
     * <p>Use this wherever company-owned data is about to be touched. Failing here
     * turns a silent cross-company read into a stack trace at the call site.
     */
    public static UUID require() {
        UUID companyId = CURRENT.get();
        if (companyId == null) {
            throw new IllegalStateException(
                    "No company context is active. Company-owned data must never be reached without "
                            + "one — open CompanyContext.open(companyId) from the authenticated session, "
                            + "or from the event envelope's companyId in an async handler.");
        }
        return companyId;
    }

    private static void restore(UUID previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    /** An active company scope. Closing restores the previously active company. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
