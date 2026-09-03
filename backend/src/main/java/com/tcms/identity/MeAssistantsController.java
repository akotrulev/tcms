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
 * {@code /me/assistants} — the caller's own connected AI assistants (D26).
 *
 * <p>Under {@code /me} rather than {@code /api-tokens/…} because the resource is the caller's, the
 * way {@code /me} already is: {@code AuthController}'s current-user surface answers "who am I", and
 * this answers "what have I connected". Putting it under the administrative path would put a
 * developer's own credential on a screen gated by {@code token:issue}, which is the one key the
 * people this feature is for do not hold.
 *
 * <p><strong>Authentication and nothing else.</strong> The argument is on {@link AssistantService}
 * and it is not "this is low risk": an assistant is capped at the intersection of the Agent role
 * and its owner's own grants, so it can reach nothing its owner cannot.
 *
 * <p>The caller comes from the token and the company from the context, neither from a parameter —
 * ERD §0.1 point 2, as every controller here repeats.
 */
@RestController
@RequestMapping("/api/v1/me/assistants")
public class MeAssistantsController {

    private final AssistantService assistants;

    public MeAssistantsController(AssistantService assistants) {
        this.assistants = assistants;
    }

    @GetMapping
    public ResponseEntity<Object> list(@AuthenticationPrincipal Jwt jwt) {
        return respond(assistants.list(caller(jwt)));
    }

    /**
     * Connects an assistant — <strong>the second response in the product that carries a
     * secret</strong>, and the first an ordinary member can ask for. It is returned once and cannot
     * be recovered afterwards.
     */
    @PostMapping
    public ResponseEntity<Object> connect(
            @AuthenticationPrincipal Jwt jwt, @RequestBody AssistantInput input) {

        AssistantService.Outcome outcome = assistants.connect(caller(jwt), input);
        return switch (outcome) {
            case AssistantService.Outcome.Connected connected -> ResponseEntity
                    .created(URI.create(
                            "/api/v1/me/assistants/" + connected.view().assistant().id()))
                    .body(connected.view());
            default -> respond(outcome);
        };
    }

    /** Revokes one. 204 whether it was live or already revoked; somebody else's is a 404. */
    @DeleteMapping("/{assistantId}")
    public ResponseEntity<Object> revoke(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID assistantId) {

        return respond(assistants.revoke(caller(jwt), assistantId));
    }

    /**
     * Every outcome except {@code Connected}, which is the one needing a {@code Location} this
     * method does not have — {@link ApiTokenController}'s arrangement and for its reason.
     */
    private static ResponseEntity<Object> respond(AssistantService.Outcome outcome) {
        return switch (outcome) {
            case AssistantService.Outcome.Listed listed -> ResponseEntity.ok(listed.views());
            case AssistantService.Outcome.Connected connected ->
                    ResponseEntity.ok(connected.view());
            case AssistantService.Outcome.Revoked ignored -> ResponseEntity.noContent().build();
            case AssistantService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            case AssistantService.Outcome.Invalid invalid ->
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
}
