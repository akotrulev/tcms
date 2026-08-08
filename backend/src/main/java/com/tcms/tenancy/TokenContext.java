package com.tcms.tenancy;

import java.util.UUID;

/**
 * The API token this request is authenticated by, if any — {@link CompanyContext}'s shape, for the
 * other half of "who is asking".
 *
 * <h2>Why it lives in {@code tenancy} rather than in {@code identity}, which owns the token</h2>
 *
 * <p>It did live there, until the first thing that reads it for a reason other than the permission
 * bound: {@link AuditEvent#record} fills {@code actor_token_id} from here, exactly as it fills
 * {@code company_id} from {@link CompanyContext}. {@code AuditEvent} is in this module for the
 * reason its own header gives — every module writes there, so the entity cannot live in whichever
 * one wrote first — and {@code tenancy} may not import {@code identity} (ERD §0.2). The type is a
 * {@code ThreadLocal} over five UUIDs with no imports of its own, so it moved rather than the rule
 * bending.
 *
 * <h2>Why a context rather than a parameter</h2>
 *
 * <p>The caller identity on a token-authenticated request is the token's <em>owner</em>, so every
 * controller keeps working unchanged: {@code caller(jwt)} is still a user id, {@code created_by} is
 * still a person, and the {@code OWN} qualifier still compares against one. What changes is the
 * <em>bound</em>, and the bound is consulted in exactly one place — {@code PermissionEvaluator}.
 * Threading a token id through every service signature to reach it would touch a hundred files to
 * express one fact, and every one of those signatures would be a place a future call site could
 * forget to pass it, defaulting to the unbounded answer.
 *
 * <p>That argument is <em>sharper</em> for the audit column than for the bound, which is why the
 * 42 call sites of {@link AuditEvent#record} gained no parameter: a forgotten bound defaults to the
 * unbounded answer, but a forgotten attribution defaults to <em>"a person did this"</em> — a false
 * record rather than a missing one.
 *
 * <h2>A scope guard, never a setter</h2>
 *
 * <p>Opened by {@code ApiTokenAuthenticationFilter} in a try-with-resources, so the scope closes
 * even when the handler throws. Servlet threads are pooled: a token left open would be inherited by
 * the next request on that thread, which is a request answered under a CI credential's cap — or,
 * far worse in the other direction, a session request silently capped by somebody else's token.
 *
 * <p><strong>Deliberately not {@code InheritableThreadLocal}</strong>, exactly like
 * {@link CompanyContext} and for a sharper reason: an {@code @Async} handler that
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
        return () -> restore(previous);
    }

    /** The token this request is authenticated by, or null for an ordinary session. */
    public static ApiTokenPrincipal currentOrNull() {
        return CURRENT.get();
    }

    /**
     * Runs with no token active, restoring the previous one when the scope closes —
     * {@link CompanyContext#clear()}'s shape, called by {@link CompanyContextTaskDecorator} on
     * every submitted task.
     *
     * <h2>This used to be an unguarded exposure, argued away, with the argument's expiry written
     * into it</h2>
     *
     * <p>The guard was unbuildable while this type lived in {@code identity}: the decorator is in
     * {@code tenancy}, which may not import it. What that left was a recycled pooled worker
     * inheriting a token from a task that leaked one, and the recorded reason for tolerating it was
     * that <em>this context only ever narrows</em> — an inherited token makes background work do
     * less rather than more, where an inherited company reads another client's rows. The paragraph
     * ended by naming its own expiry: <em>"it stops holding the moment anything reads this context
     * to grant rather than to bound."</em>
     *
     * <p>{@link AuditEvent#record} now reads it to <em>attribute</em>, which is neither, and which
     * breaks the asymmetry from the other side: an inherited token does not make an audit row do
     * less, it makes the row say something false — a scheduled recompute filed against a CI
     * credential that had nothing to do with it. Moving the type is what made the guard buildable
     * and reading it for attribution is what made it necessary.
     */
    public static Scope clear() {
        ApiTokenPrincipal previous = CURRENT.get();
        CURRENT.remove();
        return () -> restore(previous);
    }

    private static void restore(ApiTokenPrincipal previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
