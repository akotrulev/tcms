package com.tcms.identity;

import com.tcms.api.Problem;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /identity-providers} — how a company configures who may say who its people are.
 *
 * <p><strong>Outside {@code /auth/**} on purpose.</strong> That prefix is {@code permitAll} because
 * the surfaces under it run before anybody has a session; this one is the opposite — it is
 * administration, it requires a session, and it requires {@code company:idp} on top. Putting it
 * under {@code /auth} for tidiness would make the configuration of every issuer reachable by
 * anonymous callers, which is the exact mistake {@code EndpointAccessTest} walks the mappings to
 * find.
 *
 * <p>The caller comes from the token and the company from the context — neither is a parameter, for
 * the reason every controller here repeats: a {@code companyId} parameter would let request input
 * select the tenancy discriminator (ERD §0.1 point 2).
 *
 * <p>There is no delete. {@code enabled = false} is the off switch, and it is the right one:
 * {@code user_identity} rows reference a provider, so removing it would either orphan every
 * federated account in the company or cascade them away, and a company that turned SSO off for a
 * week must get its people back with their authorship intact.
 */
@RestController
@RequestMapping("/api/v1/identity-providers")
public class IdentityProviderController {

    private final IdentityProviderService providers;

    public IdentityProviderController(IdentityProviderService providers) {
        this.providers = providers;
    }

    /** What a replace of the claimed domain set carries. */
    public record EmailDomainsRequest(List<String> domains) {
    }

    /** What it answers with, and what {@code GET} answers with. */
    public record EmailDomainsResponse(List<String> domains) {
    }

    /** The company's providers, disabled ones included. 403 for a caller who may not ask. */
    @GetMapping
    public ResponseEntity<Object> list(@AuthenticationPrincipal Jwt jwt) {
        return respond(providers.list(caller(jwt)));
    }

    /** Registers a provider. Always disabled — enabling it is a second, deliberate request. */
    @PostMapping
    public ResponseEntity<Object> register(
            @AuthenticationPrincipal Jwt jwt, @RequestBody IdentityProviderInput input) {

        IdentityProviderService.Outcome outcome = providers.register(caller(jwt), input);
        return switch (outcome) {
            case IdentityProviderService.Outcome.Registered registered -> ResponseEntity
                    .created(URI.create("/api/v1/identity-providers/" + registered.view().id()))
                    .body(registered.view());
            default -> respond(outcome);
        };
    }

    /** Applies a whole edit. {@code key} and {@code kind} are set once and are not read here. */
    @PutMapping("/{providerId}")
    public ResponseEntity<Object> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID providerId,
            @RequestBody IdentityProviderInput input) {

        return respond(providers.update(caller(jwt), providerId, input));
    }

    /** The email domains this company claims. */
    @GetMapping("/email-domains")
    public ResponseEntity<Object> listDomains(@AuthenticationPrincipal Jwt jwt) {
        return respond(providers.listDomains(caller(jwt)));
    }

    /** Replaces the whole claimed set — see {@link IdentityProviderService#replaceDomains}. */
    @PutMapping("/email-domains")
    public ResponseEntity<Object> replaceDomains(
            @AuthenticationPrincipal Jwt jwt, @RequestBody EmailDomainsRequest request) {

        return respond(providers.replaceDomains(caller(jwt), request.domains()));
    }

    /**
     * Every outcome except {@code Registered}, which is the one needing a {@code Location} this
     * method does not have. One mapping rather than five, {@code ApiTokenController}'s arrangement
     * and for its reason: exhaustive over a sealed type, so a new outcome is a compile error in one
     * place rather than a 200 on a refusal in one of five.
     */
    private static ResponseEntity<Object> respond(IdentityProviderService.Outcome outcome) {
        return switch (outcome) {
            case IdentityProviderService.Outcome.Listed listed -> ResponseEntity.ok(listed.views());
            case IdentityProviderService.Outcome.Saved saved -> ResponseEntity.ok(saved.view());
            case IdentityProviderService.Outcome.Registered registered ->
                    ResponseEntity.ok(registered.view());
            case IdentityProviderService.Outcome.Domains domains ->
                    ResponseEntity.ok(new EmailDomainsResponse(domains.domains()));
            case IdentityProviderService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            case IdentityProviderService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(forbiddenProblem());
            case IdentityProviderService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(validationProblem(invalid.errors()));
        };
    }

    private static UUID caller(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private static ProblemDetail validationProblem(Map<String, List<String>> errors) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Validation failed");
        problem.setDetail("Some of the details need correcting.");
        problem.setProperty("errors", errors);
        return problem;
    }

    private static ProblemDetail forbiddenProblem() {
        return Problem.forbiddenDetail(
                "You do not have permission to configure this company's identity providers.",
                "company:idp",
                Problem.Scope.COMPANY);
    }
}
