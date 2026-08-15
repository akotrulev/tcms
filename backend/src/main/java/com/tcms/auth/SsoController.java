package com.tcms.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /auth/providers} and {@code /auth/sso/**} — the three surfaces of external sign-in.
 *
 * <p>All three are reachable by anyone on the internet, which is the point: somebody using them has
 * no session yet. {@code SecurityConfiguration} already opens {@code /api/v1/auth/**} by an
 * enumerated matcher, so nothing there changes and nothing there should — but each route is added to
 * {@code EndpointAccessTest}'s public set, which is the deliberate act that makes widening the
 * attack surface a decision rather than a side effect of choosing a path.
 *
 * <p><strong>Discovery always answers 200 with a list.</strong> Never a 404, never an error, never a
 * different shape for a domain no company claims: §6r, and the reason is that anything finer turns
 * this endpoint into an oracle for which client organisations exist on this instance.
 *
 * <p>The two browser-facing legs answer 302 in every case, success and failure alike. The person on
 * the other end is mid-redirect in a browser and has no client to read a status code — so a failure
 * lands them on the SPA with one opaque marker, which is also what keeps the causes
 * indistinguishable.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class SsoController {

    private final SsoLoginService sso;

    public SsoController(SsoLoginService sso) {
        this.sso = sso;
    }

    /** @param email what the person typed on the sign-in form; only its domain is used */
    public record ProviderDiscoveryRequest(@NotBlank String email) {
    }

    /** Always present, often empty — see the class header. */
    public record ProviderDiscoveryResponse(List<SsoLoginService.ProviderChoice> providers) {
    }

    /**
     * Which sign-in buttons to show for an address.
     *
     * <p>A {@code POST} rather than a {@code GET} with a query parameter, though it reads nothing:
     * an email address in a URL lands in access logs, browser history and {@code Referer} headers,
     * and this one is typed by somebody who has not signed in and may never do so.
     */
    @PostMapping("/providers")
    public ResponseEntity<ProviderDiscoveryResponse> providers(
            @Valid @RequestBody ProviderDiscoveryRequest request) {

        return ResponseEntity.ok(new ProviderDiscoveryResponse(sso.providersFor(request.email())));
    }

    /** Sends the browser to the issuer, or to the SPA's failure landing if it cannot be built. */
    @GetMapping("/sso/{providerId}/authorize")
    public ResponseEntity<Void> authorize(@PathVariable UUID providerId) {
        return redirectTo(sso.authorize(providerId).orElseGet(sso::failureUrl));
    }

    /**
     * Completes the sign-in and hands the session to the SPA.
     *
     * <p><strong>The provider is not a path parameter here</strong>, though it is on the authorize
     * leg. The {@code state} already fixes which attempt — and therefore which provider and which
     * company — this callback belongs to, and that binding is server-side. A second, caller-supplied
     * copy of the same fact could only either be ignored or be trusted, and on a pre-authentication
     * surface the second of those is how a callback gets pointed at somebody else's provider.
     */
    @GetMapping("/sso/callback")
    public ResponseEntity<Void> callback(
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "code", required = false) String code) {

        Optional<SsoLoginService.SignedIn> signedIn = sso.callback(state, code);
        return redirectTo(signedIn.map(sso::landingUrl).orElseGet(sso::failureUrl));
    }

    private static ResponseEntity<Void> redirectTo(String url) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
    }
}
