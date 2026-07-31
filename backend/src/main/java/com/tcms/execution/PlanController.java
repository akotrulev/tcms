package com.tcms.execution;

import java.net.URI;
import java.util.HashMap;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test plans.
 *
 * <p>The caller comes from the token and the company from the context — neither is a parameter, for
 * the reasons {@code ProjectController} sets out: a {@code userId} parameter answers "what may
 * <em>that</em> user see", and a {@code companyId} parameter lets request input select the tenancy
 * discriminator, which is the one thing {@link com.tcms.tenancy.CompanyContext} exists to prevent
 * (ERD §0.1 point 2).
 *
 * <p>404, never 403, on both reads: an absent project, one in another company, one this caller holds
 * no grant on, and a plan in a sibling project all collapse into an empty {@link java.util.Optional}
 * before this class sees them, so there is nothing here to un-collapse (§6t).
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/plans")
public class PlanController {

    private final PlanService plans;
    private final PlanCreateService creates;

    public PlanController(PlanService plans, PlanCreateService creates) {
        this.plans = plans;
        this.creates = creates;
    }

    /**
     * The project's plans, newest first.
     *
     * <p>{@code []} is a valid answer and means the project has no plans — the state of every
     * freshly provisioned project, since {@code tcms_seed_project_defaults} seeds a configuration
     * and a status vocabulary and deliberately not a plan.
     */
    @GetMapping
    public ResponseEntity<List<TestPlanView>> list(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {

        return plans.list(UUID.fromString(jwt.getSubject()), projectId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{planId}")
    public ResponseEntity<TestPlanView> detail(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID planId) {

        return plans.findOne(UUID.fromString(jwt.getSubject()), projectId, planId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Creates a plan. {@code TP-n} is allocated server-side; the caller never supplies a key.
     *
     * <p>{@code Idempotency-Key} is optional and honoured — a retry answers <strong>200 with the
     * plan it already made</strong> rather than 201 with a second one. §29 built this for cases
     * after finding that the SPA had been sending the header since Phase 1 while nothing read it;
     * here the header is read from the first day the endpoint exists, so the two sides cannot
     * diverge in the meantime.
     */
    @PostMapping
    public ResponseEntity<Object> create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody PlanWriteRequest request) {

        PlanCreateService.Outcome outcome = creates.create(
                UUID.fromString(jwt.getSubject()), projectId, request, idempotencyKey);

        return switch (outcome) {
            case PlanCreateService.Outcome.Created created -> ResponseEntity
                    .created(URI.create("/api/v1/projects/" + projectId + "/plans/"
                            + created.view().id()))
                    .body(created.view());
            // No Location: nothing was created by THIS request, and a 200 carrying one would read
            // as a create to anything following the header rather than the status.
            case PlanCreateService.Outcome.Replayed replayed ->
                    ResponseEntity.ok(replayed.view());
            case PlanCreateService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            case PlanCreateService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(forbiddenProblem());
            case PlanCreateService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(validationProblem(invalid.errors()));
        };
    }

    private static ProblemDetail validationProblem(Map<String, List<String>> errors) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Validation failed");
        problem.setDetail("Check the highlighted fields.");
        problem.setProperty("errors", new HashMap<>(errors));
        return problem;
    }

    /**
     * The refusal, naming no permission key.
     *
     * <p>§32's rule: a key is not actionable by somebody who cannot grant it to themselves, and
     * naming it publishes the catalogue to exactly the caller not entitled to it. The sentence names
     * the action instead, which is what the reader was trying to do.
     */
    private static ProblemDetail forbiddenProblem() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Not allowed");
        problem.setDetail("You do not have permission to create test plans in this project.");
        return problem;
    }
}
