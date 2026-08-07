package com.tcms.auth;

import com.tcms.identity.AppUser;
import com.tcms.identity.AppUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses a session token that predates a change to what its holder may do.
 *
 * <p>A signed token is valid until it expires, which is the whole problem with putting
 * authorization anywhere near one: <strong>without this check a revoked grant survives
 * until the token lapses</strong>. Plan §6b is explicit that the epoch is mandatory rather
 * than an optimization, and it is the reason a longer TTL is defensible at all here.
 *
 * <p>It is one integer comparison, not a permission resolution. The comparison currently
 * costs a primary-key read of {@code app_user}; §6b specifies a Caffeine cache in front of
 * it and that is deliberately deferred, on the same principle as the evaluator's own cache
 * (handoff §13): correctness first, because a wrongly-keyed cache here does not slow
 * anything down, it accepts revoked sessions.
 *
 * <h2>Deactivation is checked here too, and that is not redundant</h2>
 *
 * <p>{@link AppUser#deactivate} bumps the epoch, so a deactivated account's tokens fail the
 * comparison anyway. The status check stays because it is the one that holds if a future
 * path forgets the bump — and the failure it prevents is a deactivated operator continuing
 * to work for hours.
 */
@Component
// Non-web CLI runs have no request to filter, and constructing a servlet filter there
// would be ceremony. Same condition as SecurityConfiguration, which is what consumes it.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class PermissionVersionFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PermissionVersionFilter.class);

    private final AppUserRepository users;

    public PermissionVersionFilter(AppUserRepository users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        Optional<Jwt> jwt = currentJwt();
        if (jwt.isEmpty()) {
            // Anonymous, or authenticated by something that is not a JWT. Not this filter's
            // business — the chain's own rules decide whether that is allowed through.
            chain.doFilter(request, response);
            return;
        }

        UUID userId = parseUuid(jwt.get().getSubject());
        Optional<AppUser> user = userId == null ? Optional.empty() : users.findById(userId);

        if (jwt.get().getClaim(ApiTokenAuthenticationFilter.CLAIM_TOKEN) != null) {
            // An API token, not a session. The epoch does not apply and fabricating one would be
            // a comparison against a number this same request had just produced.
            //
            // It does not need to apply: `pv` exists because a SIGNED token is valid until it
            // expires, so a revoked grant would otherwise survive in an artifact the server cannot
            // reach. An API token is looked up in the database on EVERY request, so a revocation
            // is already effective on the next one and a grant change is already reflected by the
            // evaluator reading the owner's rows.
            //
            // The account gate still applies, and it is the half that matters here: deactivating a
            // person must stop the credentials they issued, and nothing else in the token path
            // asks about their account at all.
            if (user.isEmpty() || !user.get().isActive()) {
                refuse(response, userId);
                return;
            }
            chain.doFilter(request, response);
            return;
        }

        // Number, not Integer. A JSON integer comes back from Nimbus as a Long regardless
        // of what was encoded, so a typed getClaim(...) here throws ClassCastException on
        // EVERY authenticated request — a 500 where a 401 or a 200 belongs, and one that
        // no amount of reading the encoder side would reveal.
        Number presented = jwt.get().getClaim(SessionTokens.CLAIM_PERMISSION_VERSION);

        if (presented == null || user.isEmpty() || !user.get().isActive()
                || user.get().getPermissionVersion() != presented.intValue()) {
            refuse(response, userId);
            return;
        }

        chain.doFilter(request, response);
    }

    private static void refuse(HttpServletResponse response, UUID userId) {
        // Cleared, not just 401'd: leaving a rejected authentication in the context
        // would let anything later in the chain read a principal this filter has
        // already decided is not entitled to one.
        SecurityContextHolder.clearContext();
        log.info("Session refused for subject {} — the token predates a change to what "
                + "that account may do, or the account is no longer active", userId);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        // Names the remedy without naming the cause: the client must re-authenticate,
        // and which of the four conditions failed is not something to publish.
        response.setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\"");
    }

    private static Optional<Jwt> currentJwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }
        return Optional.of(jwt);
    }

    static UUID parseUuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            // A signed token whose subject is not a UUID means the key is shared with
            // something that mints a different claim set. Treated as unauthenticated.
            return null;
        }
    }
}
