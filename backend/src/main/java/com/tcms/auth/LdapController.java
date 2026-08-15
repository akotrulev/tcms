package com.tcms.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /auth/ldap/{providerId}/login} — the fourth public surface of external sign-in, and
 * the only one that is not a browser redirect.
 *
 * <p>It answers {@link AuthController.LoginResponse}: the same body, the same session, the same
 * empty 401. A directory proves who somebody is; the credential it proves it with never becomes a
 * credential for this API, exactly as an {@code id_token} does not.
 *
 * <p>Added to {@code EndpointAccessTest}'s public set in the same commit — the enumerated matcher in
 * {@code SecurityConfiguration} already opens {@code /api/v1/auth/**}, so listing it there is the
 * deliberate act that keeps widening the unauthenticated surface a decision rather than a
 * consequence of choosing a path.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class LdapController {

    private final LdapLoginService ldap;
    private final CurrentUserService currentUser;

    public LdapController(LdapLoginService ldap, CurrentUserService currentUser) {
        this.ldap = ldap;
        this.currentUser = currentUser;
    }

    /** @param rememberMe Boolean rather than boolean: the contract defaults it, absent is not false */
    public record DirectoryLoginRequest(@NotBlank String email, @NotBlank String password,
                                        Boolean rememberMe) {
    }

    @PostMapping("/ldap/{providerId}/login")
    public ResponseEntity<AuthController.LoginResponse> login(
            @PathVariable UUID providerId,
            @Valid @RequestBody DirectoryLoginRequest request) {

        Optional<LdapLoginService.SignedIn> result = ldap.signIn(
                providerId, request.email(), request.password(),
                Boolean.TRUE.equals(request.rememberMe()));

        if (result.isEmpty()) {
            // No body, and no cause. AuthController.login's rule, on a surface a stranger reaches.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        LdapLoginService.SignedIn signedIn = result.get();
        return ResponseEntity.ok(new AuthController.LoginResponse(
                signedIn.session().token(),
                signedIn.session().expiresAt(),
                currentUser.describe(signedIn.userId(), signedIn.companyId())));
    }
}
