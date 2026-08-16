package com.tcms.auth;

import com.tcms.api.Problem;
import com.tcms.identity.SocialIdentityService;
import com.tcms.identity.SocialProviderKey;
import com.tcms.identity.UserSocialIdentity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A person's own linked social identities — under {@code /me} and not {@code /auth}, because
 * {@code /api/v1/auth/**} is permitAll and everything here requires the session it manages.
 * No permission key gates any of it: managing your own sign-in identities is self-service on
 * your own account, like changing a password (the 2026-08-16 plan's decision 12).
 *
 * <p>The link leg is a POST answering {@code {url}} rather than a 302: a top-level browser
 * navigation cannot carry the Authorization header, so the authenticated step must be an API
 * call and the navigation happens after, holding only the single-use {@code state}.
 *
 * <p>The caller is the <strong>token's</strong> subject, {@code MeController}'s rule — never a
 * parameter, which here would be an unlink of somebody else's credential.
 */
@RestController
@RequestMapping("/api/v1/me/social-identities")
public class SocialIdentityController {

    private final SocialIdentityService identities;
    private final SocialLoginService social;

    public SocialIdentityController(SocialIdentityService identities, SocialLoginService social) {
        this.identities = identities;
        this.social = social;
    }

    public record SocialIdentityView(
            SocialProviderKey provider, Instant linkedAt, Instant lastLoginAt, String emailAtLink) {

        static SocialIdentityView of(UserSocialIdentity identity) {
            return new SocialIdentityView(
                    identity.getProvider(),
                    identity.getLinkedAt(),
                    identity.getLastLoginAt(),
                    identity.getEmailAtLink());
        }
    }

    public record SocialIdentityList(List<SocialIdentityView> identities) {
    }

    @GetMapping
    public ResponseEntity<SocialIdentityList> list(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(new SocialIdentityList(
                identities.identitiesOf(callerOf(jwt)).stream()
                        .map(SocialIdentityView::of)
                        .toList()));
    }

    /** 404 for a provider that is unknown or unconfigured — to this XHR caller they are the same fact: no such route. */
    @PostMapping("/{provider}")
    public ResponseEntity<SocialController.SocialRedirect> beginLink(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String provider) {

        Optional<String> url = SocialController.providerOf(provider)
                .flatMap(key -> social.beginLink(key, callerOf(jwt)));
        return url.map(value -> ResponseEntity.ok(new SocialController.SocialRedirect(value)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{provider}")
    public ResponseEntity<Problem> unlink(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String provider) {

        Optional<SocialProviderKey> key = SocialController.providerOf(provider);
        if (key.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return switch (identities.unlink(callerOf(jwt), key.get())) {
            case SocialIdentityService.UnlinkOutcome.Unlinked ignored ->
                    ResponseEntity.noContent().build();
            case SocialIdentityService.UnlinkOutcome.LastCredential ignored ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(Problem.conflict(
                            "This identity cannot be removed",
                            "It is this account's only way to sign in. Set a password or link "
                                    + "another provider first."));
        };
    }

    private static UUID callerOf(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
