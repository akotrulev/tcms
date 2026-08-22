package com.tcms.execution;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Standing run requests — five operations under one prefix (§247).
 *
 * <p>Its own controller rather than five more methods on {@code RunController}, which is the
 * opposite call §245 made for the bulk endpoint and for the reason that decision gave: that one
 * hangs off {@code /runs/{runId}} and reuses three of {@code RunController}'s problem builders on
 * a run it has already resolved. This is a different prefix, a different resource with its own
 * lifecycle, and it shares no path variable with a run.
 *
 * <p>The error envelope is Spring's {@link ProblemDetail}, per {@code RunController} — and the
 * bodies are that class's, worded for the act and <strong>naming no permission key</strong> (§32):
 * a key is not actionable by someone who cannot grant it to themselves, and naming it publishes
 * the catalogue to exactly the caller not entitled to it.
 *
 * <p><strong>The wire's nested {@code scope} is not flattened here.</strong>
 * {@code RunScheduleWriteRequest} carries it as the same {@link RunScopeRequestView} the contract
 * {@code $ref}s, so this controller binds the body and hands it over unchanged — the flattening
 * into a {@code RunWriteRequest} happens once, in {@code RunScheduleEdit.scopeOf}, and every
 * consumer reads it from there.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/run-schedules")
class RunScheduleController {

    private final RunScheduleService schedules;

    RunScheduleController(RunScheduleService schedules) {
        this.schedules = schedules;
    }

    /**
     * The project's schedules.
     *
     * <p>Visibility only, like the run list — a 404 for a project this caller cannot open, and
     * never a 403, because there is no second question being asked.
     */
    @GetMapping
    ResponseEntity<List<RunScheduleView>> list(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {

        return schedules.list(UUID.fromString(jwt.getSubject()), projectId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{scheduleId}")
    ResponseEntity<Object> read(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID scheduleId) {

        return answer(schedules.read(UUID.fromString(jwt.getSubject()), projectId, scheduleId),
                projectId);
    }

    @PostMapping
    ResponseEntity<Object> create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestBody RunScheduleWriteRequest request) {

        return answer(schedules.create(UUID.fromString(jwt.getSubject()), projectId, request),
                projectId);
    }

    @PutMapping("/{scheduleId}")
    ResponseEntity<Object> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID scheduleId,
            @RequestBody RunScheduleWriteRequest request) {

        return answer(
                schedules.update(UUID.fromString(jwt.getSubject()), projectId, scheduleId, request),
                projectId);
    }

    @DeleteMapping("/{scheduleId}")
    ResponseEntity<Object> delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID scheduleId) {

        return answer(schedules.delete(UUID.fromString(jwt.getSubject()), projectId, scheduleId),
                projectId);
    }

    /**
     * One exhaustive mapping for all five operations.
     *
     * <p>Exhaustive over the sealed {@code Outcome} at compile time, which is what
     * {@code GateResult}'s header asks a switch here to be: a sixth outcome added later cannot be
     * handled in four of the five endpoints and defaulted in the fifth, because there is one
     * mapping and it stops compiling.
     */
    private static ResponseEntity<Object> answer(
            RunScheduleService.Outcome outcome, UUID projectId) {

        return switch (outcome) {
            case RunScheduleService.Outcome.Created created -> ResponseEntity
                    .created(URI.create("/api/v1/projects/" + projectId + "/run-schedules/"
                            + created.view().id()))
                    .body(created.view());
            case RunScheduleService.Outcome.Read read -> ResponseEntity.ok(read.view());
            case RunScheduleService.Outcome.Updated updated -> ResponseEntity.ok(updated.view());
            case RunScheduleService.Outcome.Deleted ignored -> ResponseEntity.noContent().build();
            // Bodiless, per the contract's own NotFound response and ErrorBodyPolicyTest: what a
            // Problem could say here — which of projectId and scheduleId failed to resolve — is
            // the disclosure §6t exists to prevent.
            case RunScheduleService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            case RunScheduleService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(forbiddenProblem());
            case RunScheduleService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(validationProblem(invalid.errors()));
        };
    }

    /**
     * A genuine write race, answered as a 409 rather than a 500.
     *
     * <p>There is no {@code If-Match} on the {@code PUT}, so unlike a case edit this is the
     * <em>only</em> concurrency answer this resource has: {@code run_schedule} carries a real
     * {@code @Version} and Hibernate detects the interleaving at flush.
     * {@code CaseController#onWriteRace} carries the full account of the shape.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<Object> onWriteRace(OptimisticLockingFailureException race) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Somebody else saved first");
        problem.setDetail("This schedule was changed while you were editing it. Reload it and "
                + "make your change again.");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * §32 — worded for the act, naming no permission key.
     *
     * <p>"Create runs" rather than "manage schedules", deliberately: the key really is
     * {@code run:create}, and a sentence describing a capability the catalogue does not have would
     * send the reader to ask an administrator for something that cannot be granted.
     */
    private static ProblemDetail forbiddenProblem() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Not allowed");
        problem.setDetail("You do not have permission to create runs in this project, so you "
                + "cannot set up a schedule that creates them for you.");
        return problem;
    }

    /** {@code RunController}'s sentence, so one product does not sound like two. */
    private static ProblemDetail validationProblem(Map<String, List<String>> errors) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Validation failed");
        problem.setDetail("Check the highlighted fields.");
        problem.setProperty("errors", new HashMap<>(errors));
        return problem;
    }
}
