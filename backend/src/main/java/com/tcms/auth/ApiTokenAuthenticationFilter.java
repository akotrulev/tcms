package com.tcms.auth;

import com.tcms.identity.TokenContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates a CI job — <strong>the second way into this API, and the first that is not a
 * person.</strong>
 *
 * <h2>It produces a {@link Jwt}, and that is the whole integration</h2>
 *
 * <p>Every controller in the product takes {@code @AuthenticationPrincipal Jwt} and reads
 * {@code jwt.getSubject()} for the caller. A token principal of a different type would mean
 * touching every one of them, and each touch would be a place a future endpoint could handle one
 * shape and not the other. So this filter builds a {@link Jwt} <em>value</em> — the class is a
 * plain claim container, not a parsed credential — carrying the same two claims a session carries:
 * {@code sub} (the token's <strong>owner</strong>) and {@code co} (the company the digest resolved
 * to). {@code CompanyContextFilter} then opens the company from {@code co} exactly as it does for a
 * session, so a request has one company opener whatever authenticated it.
 *
 * <p>{@link #CLAIM_TOKEN} is what makes the two distinguishable where the difference matters, and
 * it has exactly one reader: {@link PermissionVersionFilter}.
 *
 * <h2>What is NOT in the claim set, and why that is the point</h2>
 *
 * <p>No permissions, no role, no project. The token's bound travels in {@link TokenContext} and is
 * applied by {@code PermissionEvaluator}, which is the only thing in the product that answers "may
 * this caller do X". Putting a grant in this claim set would be the second authorization path
 * {@code SessionTokens}' header refuses to create — and it would be worse here, because nothing
 * signs this object: it is constructed in memory, so a claim on it is a fact this filter asserts
 * rather than one anybody can verify.
 *
 * <h2>The scope closes even when the handler throws</h2>
 *
 * <p>try-with-resources, {@code CompanyContextFilter}'s arrangement and for its reason. Servlet
 * threads are pooled, and a token left open would cap the next request on that thread with a
 * credential that has nothing to do with it.
 *
 * <h2>A rejected token is left unauthenticated rather than 401'd here</h2>
 *
 * <p>An unknown, revoked or expired credential simply produces no authentication, and the chain's
 * own {@code .anyRequest().authenticated()} answers 401 through the configured entry point. One
 * refusal path rather than two: a status written here would be a second place the API's
 * unauthenticated response shape is decided, and it would be the place that gets forgotten when
 * that shape changes.
 */
@Component
// Non-web CLI runs have no request to filter. Same condition as SecurityConfiguration.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ApiTokenAuthenticationFilter extends OncePerRequestFilter {

    /**
     * Marks a principal as belonging to an API token rather than a session, carrying the
     * {@code api_token} id. Read by {@link PermissionVersionFilter} and by nothing else.
     */
    public static final String CLAIM_TOKEN = "tkn";

    private static final String BEARER = "Bearer ";

    private final ApiTokenAuthenticationService authentication;

    /**
     * The application clock, not {@code Instant.now()} — §60's rule, and {@code ArchitectureTest}
     * enforces it. The pair below is never compared against anything, but a second time source in
     * the codebase is exactly how the microsecond defect got in.
     */
    private final Clock clock;

    public ApiTokenAuthenticationFilter(ApiTokenAuthenticationService authentication, Clock clock) {
        this.authentication = authentication;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        Optional<TokenContext.ApiTokenPrincipal> principal = presentedSecret(request)
                .flatMap(authentication::authenticate);

        if (principal.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwtFor(principal.get(), clock.instant()), List.of()));

        try (var ignored = TokenContext.open(principal.get())) {
            chain.doFilter(request, response);
        }
    }

    private static Optional<String> presentedSecret(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return header != null && header.startsWith(BEARER)
                ? Optional.of(header.substring(BEARER.length()))
                : Optional.empty();
    }

    /**
     * The in-memory principal.
     *
     * <p>The token value is the literal {@code "api-token"} rather than the presented secret:
     * {@link Jwt#getTokenValue()} is what a debugger, a log-enrichment filter or a future error
     * handler would print, and the credential must not be reachable from an object that lives for
     * the length of the request.
     */
    private static Jwt jwtFor(TokenContext.ApiTokenPrincipal principal, Instant now) {
        return Jwt.withTokenValue("api-token")
                .header("alg", "none")
                .subject(principal.ownerId().toString())
                .claim(SessionTokens.CLAIM_COMPANY, principal.companyId().toString())
                .claim(CLAIM_TOKEN, principal.tokenId().toString())
                .issuedAt(now)
                // Nimbus requires the pair to be sane; nothing reads it, because the credential's
                // expiry lives on the api_token row and was checked before this object existed.
                .expiresAt(now.plusSeconds(60))
                .build();
    }
}
