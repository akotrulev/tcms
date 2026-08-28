package com.tcms.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /auth/login} and {@code /auth/logout} — matching {@code docs/specs/openapi.yaml},
 * which is the contract and was written before either side existed.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthenticationService authentication;
    private final CurrentUserService currentUser;
    private final SignInThrottle throttle;

    public AuthController(
            AuthenticationService authentication,
            CurrentUserService currentUser,
            SignInThrottle throttle) {
        this.authentication = authentication;
        this.currentUser = currentUser;
        this.throttle = throttle;
    }

    /** @param rememberMe Boolean rather than boolean: the contract defaults it, absent is not false */
    public record LoginRequest(@NotBlank String email, @NotBlank String password, Boolean rememberMe) {
    }

    /** {@code AuthResult} in the contract. */
    public record LoginResponse(String token, Instant expiresAt, CurrentUserService.CurrentUser user) {
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest http) {

        String address = http.getRemoteAddr();
        if (!throttle.mayAttempt(address)) {
            // The same 401 and the same silence as a wrong password (005 S10): a locked address
            // is told nothing it could pace itself by. The operator reads this line instead.
            log.info("Sign-in refused unverified for '{}' — address {} is locked out",
                    request.email(), address);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Optional<AuthenticationService.Authenticated> result = authentication.authenticate(
                request.email(), request.password(), Boolean.TRUE.equals(request.rememberMe()));

        if (result.isEmpty()) {
            throttle.recordFailure(address);
            // No body. Any detail here — "no such account", "wrong password", "not yet
            // activated" — turns the form into a directory of who exists on this instance,
            // and email is unique instance-wide, so that directory spans every client.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        throttle.recordSuccess(address);

        AuthenticationService.Authenticated authenticated = result.get();
        UUID companyId = authenticated.companyId().orElse(null);
        return ResponseEntity.ok(new LoginResponse(
                authenticated.session().token(),
                authenticated.session().expiresAt(),
                currentUser.describe(authenticated.userId(), companyId)));
    }

    /**
     * Ends the session <em>as far as this endpoint can</em>, which is honestly not very far.
     *
     * <p>The token is stateless: nothing server-side is holding the session, so there is
     * nothing here to invalidate. The client discards it and stops sending it. Returning 204
     * and pretending otherwise would be worse than saying so — a caller who believes a
     * leaked token was revoked by a logout will not rotate anything.
     *
     * <p>Real revocation needs either a short-lived access token with a refresh flow, or a
     * {@code jti} denylist. {@link SessionTokens} already stamps a {@code jti} so the second
     * is possible later; both belong with the grant-claims work, where the TTL comes down.
     * Until then the immediate lever is {@code pv}: bumping it invalidates every session for
     * that account at once.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent().build();
    }
}
