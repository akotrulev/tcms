package com.tcms.auth;

import com.tcms.identity.SocialProviderKey;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public social surfaces — reachable with no session, which is the point, and each route
 * added to {@code EndpointAccessTest}'s public set as the deliberate act that widening the
 * attack surface requires ({@link SsoController}'s rule).
 *
 * <p>{@code /providers} may answer publicly because it names instance configuration only —
 * no company, no account — so §6r's oracle concern does not reach it. The browser legs answer
 * 302 in every case, one opaque marker for every failure — including a {@code {provider}}
 * segment that names no member of {@link SocialProviderKey}, which is why the parameter is a
 * {@code String} parsed here rather than a converted enum: a conversion failure would be a 400
 * on a leg whose caller is a browser mid-redirect with no client to read it.
 */
@RestController
@RequestMapping("/api/v1/auth/social")
public class SocialController {

    private final SocialLoginService social;

    public SocialController(SocialLoginService social) {
        this.social = social;
    }

    public record SocialProviderList(List<SocialProviderKey> providers) {
    }

    public record SocialRedirect(String url) {
    }

    @GetMapping("/providers")
    public ResponseEntity<SocialProviderList> providers() {
        return ResponseEntity.ok(new SocialProviderList(social.configuredProviders()));
    }

    @GetMapping("/{provider}/authorize")
    public ResponseEntity<Void> authorize(@PathVariable String provider) {
        return redirectTo(providerOf(provider)
                .flatMap(social::authorize)
                .orElseGet(social::failureUrl));
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> callback(
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "code", required = false) String code) {

        return redirectTo(switch (social.callback(state, code)) {
            case SocialLoginService.Outcome.LoggedIn loggedIn ->
                    social.landingUrl(loggedIn.signedIn());
            case SocialLoginService.Outcome.Linked linked -> social.linkedUrl(linked.provider());
            case SocialLoginService.Outcome.Failed ignored -> social.failureUrl();
        });
    }

    static Optional<SocialProviderKey> providerOf(String value) {
        try {
            return Optional.of(SocialProviderKey.valueOf(value.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    private static ResponseEntity<Void> redirectTo(String url) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
    }
}
