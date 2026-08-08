package com.tcms.auth;

import com.tcms.identity.ApiToken;
import com.tcms.identity.ApiTokenLookup;
import com.tcms.identity.ApiTokenLookupRepository;
import com.tcms.identity.ApiTokenProperties;
import com.tcms.identity.ApiTokenRepository;
import com.tcms.identity.ApiTokenSecrets;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.TokenContext;
import java.time.Clock;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Resolves a presented API token to the principal it authenticates — <strong>the two-step read the
 * whole {@code api_token_lookup} decision exists for.</strong>
 *
 * <h2>The two steps, and why they cannot be one</h2>
 *
 * <ol>
 *   <li><strong>With no company context open</strong>, the digest is resolved through
 *       {@link ApiTokenLookupRepository}, which reads an instance-level table. This is the step
 *       that would return zero rows if the row lived on {@code api_token} — {@code V7}'s defect,
 *       and the reason {@code V36} exists.</li>
 *   <li><strong>Inside that company</strong>, the policed {@code api_token} row is read and its
 *       lifecycle checked. Expiry and revocation are decided here, on a row under RLS, rather than
 *       on the unprotected lookup — so a credential's death is enforced inside the isolation
 *       boundary and is visible to the operator who caused it.</li>
 * </ol>
 *
 * <h2>THE TWO STEPS ARE TWO TRANSACTIONS, AND THAT IS NOT A STYLE CHOICE</h2>
 *
 * <p>The first draft was one {@code @Transactional} method that opened the company between the two
 * reads. <strong>Every valid credential got a 401</strong>, and the test suite caught it only
 * because the assertion was made over HTTP.
 *
 * <p>{@code CompanyAwareTransactionManager} establishes isolation in {@code doBegin} — it sets the
 * Hibernate filter and the {@code app.current_company} GUC <em>when the transaction starts</em>.
 * A transaction that begins with no context open therefore runs to completion with the GUC unset,
 * whatever {@link CompanyContext#open} is called afterwards: the scope guard changes a
 * {@code ThreadLocal} that nothing re-reads. So the policed {@code api_token} read ran under RLS
 * with no company, returned zero rows, and the instance answered 401 to a live token — the exact
 * shape of the defect {@code V36} exists to prevent, arriving one layer up.
 *
 * <p>Hence two {@link TransactionTemplate} calls rather than two {@code @Transactional} methods:
 * a self-invocation is not intercepted by the proxy, so splitting them into private methods on this
 * class would have produced the identical single transaction and the identical silent 401. That is
 * {@code ReportJobRunner}'s argument for its three transactions, and it is worth restating here
 * because this failure mode is invisible in a unit test and in any harness that opens a context
 * before calling in.
 *
 * <p>Both refusals are the same {@link Optional#empty()}. An unknown digest, a revoked token, an
 * expired one and a token whose row has vanished are one outcome to the caller, for
 * {@code MembershipInvitation}'s reason: distinguishable failures on a pre-authentication surface
 * are an oracle, and here the thing they would be an oracle about is which guessed strings are real
 * credentials.
 *
 * <h2>The company opened here is thrown away, deliberately</h2>
 *
 * <p>The scope closes when this method returns. The request's own context is opened afterwards by
 * {@code CompanyContextFilter}, from the {@code co} claim of the principal this produces — the same
 * path a session takes, so there is exactly one place a request's company is established and it
 * still reads from an authenticated principal rather than from request input. Leaving the context
 * open from here would be a second, earlier opener that nothing closes on a rejected token.
 */
@Service
public class ApiTokenAuthenticationService {

    private final ApiTokenLookupRepository lookups;
    private final ApiTokenRepository tokens;
    private final ApiTokenSecrets secrets;
    private final ApiTokenProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ApiTokenAuthenticationService(
            ApiTokenLookupRepository lookups,
            ApiTokenRepository tokens,
            ApiTokenSecrets secrets,
            ApiTokenProperties properties,
            TransactionTemplate transactions,
            Clock clock) {

        this.lookups = lookups;
        this.tokens = tokens;
        this.secrets = secrets;
        this.properties = properties;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * @param presented the raw {@code Authorization} bearer value. Hashed here and never stored,
     *                  logged or returned.
     * @return the principal, or empty for every kind of refusal
     */
    public Optional<TokenContext.ApiTokenPrincipal> authenticate(String presented) {
        if (!secrets.looksLikeApiToken(presented)) {
            return Optional.empty();
        }
        String digest = secrets.hash(presented);

        // Transaction one: no context, instance-level table.
        Optional<ApiTokenLookup> lookup =
                transactions.execute(status -> lookups.findByTokenHash(digest));
        if (lookup == null || lookup.isEmpty()) {
            return Optional.empty();
        }

        // Transaction two: opened INSIDE the company, so doBegin sets the filter and the GUC. The
        // scope must wrap the transaction and not the other way round — see the class header.
        try (var ignored = CompanyContext.open(lookup.get().getCompanyId())) {
            return transactions.execute(status ->
                    tokens.findScopedById(lookup.get().getTokenId())
                            .filter(token -> token.isUsable(clock.instant()))
                            .map(this::accept));
        }
    }

    /**
     * Records the use and produces the principal.
     *
     * <p>The {@code last_used_at} bump rides <strong>this</strong> transaction rather than getting
     * one of its own, and that is the whole reason it is here and not in the filter: the policed
     * row is already loaded, already company-scoped and already managed, so the cost of the write
     * is one {@code UPDATE} and the cost of <em>not</em> writing is nothing at all.
     * {@link ApiToken#markUsed} answers "nothing at all" for all but the first request in each
     * resolution window, which is what keeps a CI token's row off the hot path.
     *
     * <p>Only a <em>usable</em> token reaches here — the filter above runs first — so a revoked or
     * expired credential presenting itself does not move the timestamp. "Last used" means last
     * used, not last attempted; the second is an access log's question and this column would
     * answer it badly.
     */
    private TokenContext.ApiTokenPrincipal accept(ApiToken token) {
        if (token.markUsed(clock.instant(), properties.lastUsedResolution())) {
            tokens.save(token);
        }
        return principalOf(token);
    }

    /**
     * The company comes from the token's <em>own row</em> rather than from the lookup, even though
     * the two must agree. The policed row is the record; the lookup is an index in front of it, and
     * an index disagreeing with what it indexes must not be the thing an authorization decision
     * reads.
     */
    private static TokenContext.ApiTokenPrincipal principalOf(ApiToken token) {
        return new TokenContext.ApiTokenPrincipal(
                token.getId(), token.getCompanyId(), token.getOwnerId(),
                token.getRoleId(), token.getProjectId());
    }
}
