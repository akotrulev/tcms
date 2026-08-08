package com.tcms.auth;

import com.tcms.tenancy.TokenContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bounds what one API token may ask for — the MCP plan's phase 9.
 *
 * <p>Everything §140 shipped is reachable over a public ingress, and the caller on the other side
 * of it is a model. The failure this exists for is named in that plan: <em>an agent looping
 * {@code search_cases} is real load on the box running Postgres</em> — a realistic accident rather
 * than an attack, which is why the numbers are generous and the refusal is written to be read.
 *
 * <h2>Only a token is counted</h2>
 *
 * <p>A <strong>session</strong> is not. The shape being prevented is a loop, which a person
 * clicking cannot produce, and a person fast enough to trip a generous limit would meet a 429 the
 * SPA has no copy for — a frontend slice, not this one. An <strong>unauthenticated</strong> request
 * cannot be counted at all: there is no key the caller does not control, and an in-process map
 * keyed on something spoofable is a memory leak with a security story attached. Volume in front of
 * authentication belongs to the ingress, which already terminates TLS for {@code /api/*}.
 *
 * <p>The key is {@code api_token.id} rather than the company or the source address. Per company,
 * one agent's loop refuses its colleagues' CI job — the blast radius of one mistake becomes
 * everybody sharing a client organisation. Per address, a runner fleet behind one egress shares a
 * bucket with no relationship to who issued what. The token is what was issued, what can be
 * revoked, and what the loop belongs to.
 *
 * <h2>This is the fourth reader of {@link TokenContext}, and the first ordinary one</h2>
 *
 * <p>Its header names the boundary: the type's home held while it was read to <em>bound</em> and
 * would have to move the moment something read it to <em>grant</em>. §138 read it to refuse and
 * §132 to attribute; this reads it to bound, which is the case the type was written for.
 *
 * <h2>Where it sits, and why a 429 from here can be retried blind</h2>
 *
 * <p>After {@link PermissionVersionFilter}, so a token whose owner has been deactivated is 401'd
 * rather than counted; before {@code CompanyContextFilter}, so a refused request opens no company
 * and starts no transaction. That ordering has a consequence worth stating, because the client
 * says it out loud to the model: <strong>a 429 written here is the one failure in this API that is
 * guaranteed to have changed nothing.</strong> It is decided before dispatch, so there is no
 * handler that ran, no row that may or may not exist, and no reason to read anything back before
 * trying again.
 */
@Component
// Non-web CLI runs have no request to filter. Same condition as SecurityConfiguration.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final TokenRateLimiter limiter;

    RateLimitFilter(TokenRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        TokenContext.ApiTokenPrincipal token = TokenContext.currentOrNull();
        if (token == null) {
            chain.doFilter(request, response);
            return;
        }

        TokenRateLimiter.Decision decision = limiter.check(token.tokenId());
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }

        // The token id, never the owner and never the secret: this line is what an operator reads
        // when a CI job starts failing, and the answer they need is WHICH CREDENTIAL to look at.
        log.info("Rate limit reached by api_token {} — refusing {} {} for {}s",
                token.tokenId(), request.getMethod(), request.getRequestURI(),
                decision.retryAfter().toSeconds());

        refuse(response, decision.retryAfter().toSeconds());
    }

    /**
     * The refusal.
     *
     * <h2>A third copy of the error envelope, recorded as one</h2>
     *
     * <p>{@code ApiProblem} ({@code repository}) is the first and {@code
     * InvitationController.ProblemBody} the second, and {@code ApiProblem}'s own header already
     * names the duplication as deliberate with a {@code shared} module as the follow-up. This copy
     * is forced twice over: a filter's response never reaches Spring MVC's exception handling, and
     * {@code auth} may not import {@code repository} in any case. Composed as a string rather than
     * through Jackson because the shape is four constants and a number, and reaching for the
     * mapper here would be a bean this module does not otherwise need.
     *
     * <p>The wait is in the {@code detail} as well as in {@code Retry-After}. Both readers need it:
     * {@code @tcms/cli} prints {@code detail} verbatim (§139), and a model handed a failure with no
     * number in it retries immediately — which is the loop this filter exists to stop, arriving one
     * refusal later.
     */
    private static void refuse(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        response.setStatus(429);
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("""
                {"type":"about:blank","title":"Too many requests",\
                "status":429,\
                "detail":"This token has made too many requests. Wait %d second%s and try again. \
                Nothing was changed by this call."}"""
                .formatted(retryAfterSeconds, retryAfterSeconds == 1 ? "" : "s"));
    }
}
