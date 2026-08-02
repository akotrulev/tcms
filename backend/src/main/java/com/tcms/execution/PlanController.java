package com.tcms.execution;

import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final PlanStatisticsService statistics;
    private final PlanStatusService lifecycle;
    private final PlanDeleteService deletes;

    public PlanController(
            PlanService plans,
            PlanCreateService creates,
            PlanStatisticsService statistics,
            PlanStatusService lifecycle,
            PlanDeleteService deletes) {

        this.plans = plans;
        this.creates = creates;
        this.statistics = statistics;
        this.lifecycle = lifecycle;
        this.deletes = deletes;
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
     * Where the plan stands right now (plan §6p).
     *
     * <p>{@code aggregation} is bound as a {@code String} and parsed here rather than declared as the
     * enum. Spring's own converter would answer a bare 400 whose body names the Java type and the
     * rejected value, which publishes an internal class name and tells the caller nothing about what
     * it should have sent; the five valid policies are worth naming, since choosing between them is
     * the whole point of the parameter.
     */
    @GetMapping("/{planId}/statistics")
    public ResponseEntity<Object> statistics(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID planId,
            @RequestParam(name = "aggregation", required = false) String aggregation) {

        AggregationPolicy policy;
        try {
            policy = aggregation == null || aggregation.isBlank()
                    ? AggregationPolicy.DEFAULT
                    : AggregationPolicy.valueOf(aggregation.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return ResponseEntity.badRequest().body(unknownPolicyProblem(aggregation));
        }

        return statistics.statistics(UUID.fromString(jwt.getSubject()), projectId, planId, policy)
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
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

    /**
     * Submits, approves, starts, closes or reopens the plan.
     *
     * <p><strong>{@code PUT}, not {@code PATCH}</strong>, and a sub-resource rather than a field on
     * the plan: the status <em>is</em> the resource being replaced and the request names an absolute
     * target. It mirrors {@code PUT /runs/{runId}/status} and {@code PUT /doc-pages/{pageId}/status}
     * exactly, which is the precedent to follow rather than a third shape for one act.
     *
     * <p>There is no {@code If-Match}, and that is a decision — see {@link PlanStatusService}.
     */
    @PutMapping("/{planId}/status")
    public ResponseEntity<Object> status(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID planId,
            @RequestBody PlanStatusRequest request) {

        PlanStatusService.Outcome outcome =
                lifecycle.setStatus(UUID.fromString(jwt.getSubject()), projectId, planId, request);

        return switch (outcome) {
            case PlanStatusService.Outcome.Updated updated -> ResponseEntity.ok(updated.view());
            case PlanStatusService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            case PlanStatusService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(statusForbiddenProblem());
            case PlanStatusService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(validationProblem(invalid.errors()));
            case PlanStatusService.Outcome.Refused refused ->
                    ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(refusedProblem(refused.message()));
        };
    }

    /**
     * Deletes the plan, unless runs have been executed against it.
     *
     * <p>204 with no body on success, and the 409 carries its count — see {@link PlanDeleteService}
     * for why the refusal is the interesting half.
     */
    @DeleteMapping("/{planId}")
    public ResponseEntity<Object> delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID planId) {

        PlanDeleteService.Outcome outcome =
                deletes.delete(UUID.fromString(jwt.getSubject()), projectId, planId);

        return switch (outcome) {
            case PlanDeleteService.Outcome.Deleted ignored -> ResponseEntity.noContent().build();
            case PlanDeleteService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            case PlanDeleteService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(deleteForbiddenProblem());
            case PlanDeleteService.Outcome.Refused refused ->
                    ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(refusedProblem(refused.message()));
        };
    }

    /**
     * Names the five, unlike the permission refusal below.
     *
     * <p>The two are not in tension: §32's rule is that a <em>permission key</em> must not be named
     * to a caller who cannot grant it to themselves, because that publishes the catalogue. An
     * aggregation policy is public vocabulary already on every response and in the SPA's own
     * dropdown, and withholding it would leave the caller guessing at a closed set.
     */
    private static ProblemDetail unknownPolicyProblem(String rejected) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Unknown aggregation policy");
        problem.setDetail("'" + rejected + "' is not an aggregation policy. Valid values: "
                + Arrays.stream(AggregationPolicy.values())
                        .map(Enum::name)
                        .collect(Collectors.joining(", "))
                + ".");
        return problem;
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

    /**
     * §32 again — worded for the act and naming no key.
     *
     * <p>One sentence for both keys, and that is deliberate: the gate a caller failed depends on
     * which move they asked for, so a message that named it would have to say either "editing plans"
     * or "approving plans" — and telling a Test Engineer which of the two they are missing is how a
     * refusal becomes a map of the catalogue. The sentence names the act instead.
     */
    private static ProblemDetail statusForbiddenProblem() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Not allowed");
        problem.setDetail("You do not have permission to make this change to the plan's status.");
        return problem;
    }

    private static ProblemDetail deleteForbiddenProblem() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Not allowed");
        problem.setDetail("Deleting a test plan needs a test lead.");
        return problem;
    }

    /**
     * A move the plan's own state refuses, and the delete a live run refuses.
     *
     * <p>The sentence comes from the domain rather than being written here, for the reason
     * {@code RunController} gives: it is raised from two methods and computed in two classes, and a
     * copy per controller method is how one rule ends up explained two ways.
     */
    private static ProblemDetail refusedProblem(String message) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Not possible right now");
        problem.setDetail(message);
        return problem;
    }
}
