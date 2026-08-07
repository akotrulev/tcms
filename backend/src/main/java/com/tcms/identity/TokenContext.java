package com.tcms.identity;

import java.util.UUID;

/**
 * The API token this request is authenticated by, if any — {@link com.tcms.tenancy.CompanyContext}'s
 * shape, for the other half of "who is asking".
 *
 * <h2>Why a context rather than a parameter</h2>
 *
 * <p>The caller identity on a token-authenticated request is the token's <em>owner</em>, so every
 * controller keeps working unchanged: {@code caller(jwt)} is still a user id, {@code created_by} is
 * still a person, and the {@code OWN} qualifier still compares against one. What changes is the
 * <em>bound</em>, and the bound is consulted in exactly one place —
 * {@link PermissionEvaluator}. Threading a token id through every service signature to reach it
 * would touch a hundred files to express one fact, and every one of those signatures would be a
 * place a future call site could forget to pass it, defaulting to the unbounded answer.
 *
 * <h2>A scope guard, never a setter</h2>
 *
 * <p>Opened by {@code ApiTokenAuthenticationFilter} in a try-with-resources, so the scope closes
 * even when the handler throws. Servlet threads are pooled: a token left open would be inherited by
 * the next request on that thread, which is a request answered under a CI credential's cap — or,
 * far worse in the other direction, a session request silently capped by somebody else's token.
 *
 * <p><strong>Deliberately not {@code InheritableThreadLocal}</strong>, exactly like
 * {@link com.tcms.tenancy.CompanyContext} and for a sharper reason: an {@code @Async} handler that
 * inherited a token would run background work under a credential's bound with nothing having
 * decided that it should, while one that does not inherit runs uncapped as the system — which is
 * why nothing capped may be dispatched asynchronously. Ingestion establishes context from its event
 * envelope; it does not inherit one.
 */
public final class TokenContext {

    private static final ThreadLocal<ApiTokenPrincipal> CURRENT = new ThreadLocal<>();

    private TokenContext() {
    }

    /**
     * Which token is asking, and what it may be capped to.
     *
     * @param tokenId   the {@code api_token} row, for auditing
     * @param companyId the company it was issued in and the only one it can reach. Bound at issue
     *                  time ({@code V1}'s column comment), resolved from the digest, and never from
     *                  anything the caller said.
     * @param ownerId   the person answerable for it — the caller identity for the whole request
     * @param roleId    the role the token carries. The cap's first half.
     * @param projectId the one project it may reach, or null. The cap's second half, and a hard
     *                  scope: a different project resolves to the empty set before the permission
     *                  intersection is reached at all.
     */
    public record ApiTokenPrincipal(
            UUID tokenId, UUID companyId, UUID ownerId, UUID roleId, UUID projectId) {
    }

    /** Closes the scope. Named so a {@code try (var ignored = TokenContext.open(…))} reads. */
    public interface Scope extends AutoCloseable {

        @Override
        void close();
    }

    public static Scope open(ApiTokenPrincipal principal) {
        ApiTokenPrincipal previous = CURRENT.get();
        CURRENT.set(principal);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    /** The token this request is authenticated by, or null for an ordinary session. */
    public static ApiTokenPrincipal currentOrNull() {
        return CURRENT.get();
    }

    /**
     * Clears the slot unconditionally.
     *
     * <p><strong>Nothing calls this yet, and the reason is worth stating rather than leaving as an
     * apparent omission.</strong> {@code CompanyContextTaskDecorator} clears the company on every
     * submitted task so a handler that forgets fails closed; it cannot clear this one, because
     * {@code tenancy} depends on nothing and may not import {@code identity} (ERD §0.2). The
     * exposure that leaves is a recycled pooled worker inheriting a token from a task that leaked
     * one — and it is safe in a way the company's is not: <em>this context only ever narrows</em>,
     * so an inherited token makes background work do less rather than more. The company's leak
     * reads another client's rows; this one refuses work the system was entitled to do.
     *
     * <p>That asymmetry is why the guard is not worth moving a type across a module boundary for,
     * and it stops holding the moment anything reads this context to <em>grant</em> rather than to
     * bound. If that ever happens, this method is what a decorator would call and the boundary
     * question has to be answered first.
     */
    public static void clear() {
        CURRENT.remove();
    }
}
