package com.tcms.identity;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api-tokens} — the company's non-human credentials.
 *
 * <p>Company-scoped rather than project-scoped, even though a token may name one project. The
 * credential belongs to the company: it is issued by a Company Admin, it is revoked by one, and
 * putting the surface under {@code /projects/{projectId}/…} would make a token that names no project
 * unreachable and a token that names one editable from a screen whose gate is about that project.
 *
 * <p>The caller comes from the token and the company from the context — neither is a parameter, for
 * the reason every controller here repeats: a {@code companyId} parameter would let request input
 * select the tenancy discriminator (ERD §0.1 point 2).
 *
 * <h2>The principal here is always a session, never an API token</h2>
 *
 * <p>A credential that could mint credentials is a privilege-escalation ladder with one rung: a
 * leaked CI token would be able to issue itself a Company Admin token and the cap in
 * {@link PermissionEvaluator} would not stop it, because the new token's owner would be the
 * original token's owner. It does not arise, and it does not arise by construction rather than by a
 * check here — Automation Service does not hold {@code token:issue}, and no PROJECT-scope role
 * does. Written down because "no role holds it today" is a property of the seed, and the seed can
 * change.
 */
@RestController
@RequestMapping("/api/v1/api-tokens")
public class ApiTokenController {

    private final ApiTokenService tokens;

    public ApiTokenController(ApiTokenService tokens) {
        this.tokens = tokens;
    }

    /** The company's tokens, newest first, revoked ones included. 403 for a caller who may not ask. */
    @GetMapping
    public ResponseEntity<Object> list(@AuthenticationPrincipal Jwt jwt) {
        return respond(tokens.list(caller(jwt)));
    }

    /**
     * Issues a token — <strong>the one response in the product that carries a secret</strong>. It
     * is returned once and cannot be recovered afterwards.
     */
    @PostMapping
    public ResponseEntity<Object> issue(
            @AuthenticationPrincipal Jwt jwt, @RequestBody ApiTokenIssueRequest request) {

        ApiTokenService.Outcome outcome = tokens.issue(caller(jwt), request);
        return switch (outcome) {
            case ApiTokenService.Outcome.Issued issued -> ResponseEntity
                    .created(URI.create("/api/v1/api-tokens/" + issued.view().token().id()))
                    .body(issued.view());
            default -> respond(outcome);
        };
    }

    /** Revokes a token. 204 whether it was live or already revoked. */
    @DeleteMapping("/{tokenId}")
    public ResponseEntity<Object> revoke(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID tokenId) {

        return respond(tokens.revoke(caller(jwt), tokenId));
    }

    /**
     * Every outcome except {@code Issued}, which is the one needing a {@code Location} this method
     * does not have.
     *
     * <p>One mapping rather than three, {@code TeamController}'s arrangement and for its reason: all
     * three endpoints agree on what each outcome means, and a per-endpoint copy is how one of them
     * answers 200 to a refusal. Exhaustive over a sealed type, so a new outcome is a compile error
     * in one place.
     */
    private static ResponseEntity<Object> respond(ApiTokenService.Outcome outcome) {
        return switch (outcome) {
            case ApiTokenService.Outcome.Listed listed -> ResponseEntity.ok(listed.views());
            case ApiTokenService.Outcome.Issued issued -> ResponseEntity.ok(issued.view());
            case ApiTokenService.Outcome.Revoked ignored -> ResponseEntity.noContent().build();
            case ApiTokenService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            case ApiTokenService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(forbiddenProblem());
            case ApiTokenService.Outcome.Invalid invalid ->
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
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Not permitted");
        problem.setDetail("You do not have permission to manage this company's API tokens.");
        return problem;
    }
}
