package com.tcms.execution;

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
 * {@code /projects/{projectId}/automation-mappings} — the learned bindings a person confirms.
 *
 * <p>Three operations and two gates: the list costs {@code case:read}, both writes cost
 * {@code case:edit}. {@link AutomationMappingService}'s header carries why, and why a leaked CI
 * token holding neither is the point.
 *
 * <p><strong>{@code POST} is 200, not 201, and there is no {@code Location}.</strong> The row is
 * keyed on the test name rather than on an id the client chose, so a second post of the same name is
 * the same resource re-pointed rather than a new one — and answering 201 to a request that created
 * nothing is a claim a client's retry logic reads. The id is in the body for the delete.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/automation-mappings")
public class AutomationMappingController {

    private final AutomationMappingService mappings;

    public AutomationMappingController(AutomationMappingService mappings) {
        this.mappings = mappings;
    }

    /** This project's mappings, by test name. */
    @GetMapping
    public ResponseEntity<Object> list(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {

        return respond(mappings.list(caller(jwt), projectId));
    }

    /** Confirms that a test name means a case — creating the binding or re-pointing it. */
    @PostMapping
    public ResponseEntity<Object> confirm(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestBody(required = false) AutomationMappingRequest request) {

        return respond(mappings.confirm(caller(jwt), projectId, request));
    }

    /** Removes a binding. 204 whether it was there or not. */
    @DeleteMapping("/{mappingId}")
    public ResponseEntity<Object> delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID mappingId) {

        return respond(mappings.delete(caller(jwt), projectId, mappingId));
    }

    /**
     * One mapping rather than three — {@code ApiTokenController}'s arrangement, for its reason: all
     * three endpoints agree on what each outcome means, and a per-endpoint copy is how one of them
     * answers 200 to a refusal. Exhaustive over a sealed type, so a new outcome is a compile error
     * in one place.
     */
    private static ResponseEntity<Object> respond(AutomationMappingService.Outcome outcome) {
        return switch (outcome) {
            case AutomationMappingService.Outcome.Listed listed ->
                    ResponseEntity.ok(listed.views());
            case AutomationMappingService.Outcome.Confirmed confirmed ->
                    ResponseEntity.ok(confirmed.view());
            case AutomationMappingService.Outcome.Deleted ignored ->
                    ResponseEntity.noContent().build();
            case AutomationMappingService.Outcome.NotFound ignored ->
                    ResponseEntity.notFound().build();
            case AutomationMappingService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(forbiddenProblem());
            case AutomationMappingService.Outcome.Invalid invalid ->
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
        problem.setDetail("You do not have permission to edit this project's cases.");
        return problem;
    }
}
