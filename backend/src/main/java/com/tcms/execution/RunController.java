package com.tcms.execution;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runs, their executions, and the one write in this module.
 *
 * <h2>The error envelope is Spring's {@link ProblemDetail}</h2>
 *
 * <p>Following §34 rather than {@code repository.ApiProblem}: that record is package-private and
 * {@code InvitationController.ProblemBody} is already a second copy, so a third would make the
 * eventual extraction into the {@code shared} module reconcile three shapes instead of two.
 * {@code ProblemDetail} serializes to exactly the {@code Problem} the contract declares.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/runs")
public class RunController {

    private final RunService runs;
    private final ExecutionService executions;
    private final RunCreateService creates;
    private final RunStatusService lifecycle;

    public RunController(
            RunService runs,
            ExecutionService executions,
            RunCreateService creates,
            RunStatusService lifecycle) {

        this.runs = runs;
        this.executions = executions;
        this.creates = creates;
        this.lifecycle = lifecycle;
    }

    /**
     * Creates a run <strong>and its executions</strong> — see {@link RunCreateService}.
     *
     * <p>The response is the run, not the run with its checklist: {@code TestRun} is what the
     * contract declares here and what the run list renders, and the SPA's next navigation is to
     * {@code GET /runs/{runId}} which serves the executions. Returning a two-hundred-row detail from
     * a create would make the create the largest response in the module for a screen that is about
     * to fetch it again anyway.
     */
    @PostMapping
    public ResponseEntity<Object> create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody RunWriteRequest request) {

        RunCreateService.Outcome outcome = creates.create(
                UUID.fromString(jwt.getSubject()), projectId, request, idempotencyKey);

        return switch (outcome) {
            case RunCreateService.Outcome.Created created -> ResponseEntity
                    .created(URI.create("/api/v1/projects/" + projectId + "/runs/"
                            + created.view().id()))
                    .body(created.view());
            case RunCreateService.Outcome.Replayed replayed -> ResponseEntity.ok(replayed.view());
            case RunCreateService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            case RunCreateService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(createForbiddenProblem());
            case RunCreateService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(validationProblem(invalid.errors()));
            // §6g's gating rule (§82). A 409 with its own title, not refusedProblem's: that one
            // says "this run cannot change that way" and there is no run yet — what is refused is
            // the project's policy on what may go into one.
            case RunCreateService.Outcome.Refused refused ->
                    ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(gatedProblem(refused.message()));
        };
    }

    /**
     * §6g's approval gate refusing a run before it exists.
     *
     * <p>The sentence is the domain's, as on every other 409 here — {@code RunCreateService} names
     * the cases, and it is the only thing that can.
     */
    private static ProblemDetail gatedProblem(String message) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("This run needs approved cases");
        problem.setDetail(message);
        return problem;
    }

    /** Worded for the action, naming no permission key — §32, as on {@code PlanController}. */
    private static ProblemDetail createForbiddenProblem() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Not allowed");
        problem.setDetail("You do not have permission to create runs in this project.");
        return problem;
    }

    /**
     * The project's runs, optionally narrowed by plan and release.
     *
     * <p>A filter matching nothing is an empty list, never a 404 — see {@link RunService}.
     */
    @GetMapping
    public ResponseEntity<List<TestRunView>> list(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestParam(name = "planId", required = false) UUID planId,
            @RequestParam(name = "releaseVersion", required = false) String releaseVersion) {

        return runs.list(UUID.fromString(jwt.getSubject()), projectId, planId, releaseVersion)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{runId}")
    public ResponseEntity<TestRunDetailView> detail(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID runId) {

        return runs.findOne(UUID.fromString(jwt.getSubject()), projectId, runId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Completes, aborts or reopens the run.
     *
     * <p><strong>{@code PUT}, not {@code PATCH}</strong>, and a sub-resource rather than a field on
     * the run: the status <em>is</em> the resource being replaced, and the request names an absolute
     * target rather than a delta. It mirrors {@code PUT /doc-pages/{pageId}/status} exactly, which is
     * the precedent to follow rather than a second shape for the same act.
     *
     * <p>There is no {@code If-Match} here and that is a decision, not an omission — see
     * {@link RunStatusService}.
     */
    @PutMapping("/{runId}/status")
    public ResponseEntity<Object> status(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID runId,
            @RequestBody RunStatusRequest request) {

        RunStatusService.Outcome outcome =
                lifecycle.setStatus(UUID.fromString(jwt.getSubject()), projectId, runId, request);

        return switch (outcome) {
            case RunStatusService.Outcome.Updated updated -> ResponseEntity.ok(updated.view());
            case RunStatusService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            case RunStatusService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(closeForbiddenProblem());
            case RunStatusService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(validationProblem(invalid.errors()));
            case RunStatusService.Outcome.Refused refused ->
                    ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(refusedProblem(refused.message()));
        };
    }

    /** §32 again — worded for the act, naming no permission key. */
    private static ProblemDetail closeForbiddenProblem() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Not allowed");
        problem.setDetail("Ending or reopening a run needs a test lead.");
        return problem;
    }

    /**
     * A transition the run's state refuses, and the run-closed refusal both writes now raise.
     *
     * <p>The sentence comes from the domain rather than being written here, because the same 409 is
     * raised from three call sites and one of them is in another service; a copy per controller
     * method is how the product ends up telling a tester and a lead two different things about the
     * same rule.
     */
    private static ProblemDetail refusedProblem(String message) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("This run cannot change that way");
        problem.setDetail(message);
        return problem;
    }

    /**
     * The frozen-run refusal on a write to an execution — {@code V1}'s "immutable once the run
     * closes", which §67 deferred and §72 applies to the verdict and its evidence at once.
     */
    private static ProblemDetail runClosedProblem() {
        return refusedProblem(RunLifecycle.CLOSED_MESSAGE);
    }

    @GetMapping("/{runId}/executions/{executionId}")
    public ResponseEntity<TestExecutionView> execution(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID runId,
            @PathVariable UUID executionId) {

        return executions.findOne(UUID.fromString(jwt.getSubject()), projectId, runId, executionId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Records — or amends — a result.
     *
     * <p>{@code If-Match} carries the {@code lockVersion} the client read, exactly as on a case edit
     * and a suite rename. Here it is not a formality: a run is worked by several testers at once by
     * design (ERD §5), so two people on one execution is the ordinary case rather than the race. A
     * missing header is last-write-wins, which is right for an importer with no prior read.
     *
     * <p>The 403 sentence depends on which of the two permissions was refused — see
     * {@link ExecutionService}.
     */
    @PatchMapping("/{runId}/executions/{executionId}")
    public ResponseEntity<Object> record(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID runId,
            @PathVariable UUID executionId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody ExecutionResultRequest request) {

        Long expected;
        try {
            expected = parseIfMatch(ifMatch);
        } catch (NumberFormatException notAVersion) {
            // 400 rather than 409, for CaseController's reason: a 409 would send a client with a
            // malformed header into a reload-and-retry loop that cannot succeed.
            return ResponseEntity.badRequest().body(validationProblem(Map.of(
                    "If-Match",
                    List.of("Send the execution's lockVersion as the If-Match header, or omit the "
                            + "header."))));
        }

        ExecutionService.Outcome outcome = executions.record(
                UUID.fromString(jwt.getSubject()), projectId, runId, executionId, request, expected);

        return switch (outcome) {
            case ExecutionService.Outcome.Recorded recorded -> ResponseEntity.ok(recorded.view());
            case ExecutionService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            case ExecutionService.Outcome.Forbidden forbidden ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(forbiddenProblem(forbidden));
            case ExecutionService.Outcome.NotExecuting ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(notExecutingProblem());
            case ExecutionService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(validationProblem(invalid.errors()));
            case ExecutionService.Outcome.Stale ignored ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(staleProblem());
            case ExecutionService.Outcome.RunClosed ignored ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(runClosedProblem());
        };
    }

    /**
     * A genuine write race, answered as a 409 rather than a 500.
     *
     * <p>The {@code If-Match} check above catches the case the client can see: it read version 3 and
     * somebody has since saved version 4. This catches the one it cannot — two saves interleaving
     * <em>inside</em> the window between the check and the flush, which Hibernate's {@code @Version}
     * detects at commit. Not covered by the suite, because reproducing it needs two interleaved
     * transactions; {@code CaseController#onWriteRace} carries the full account.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Object> onWriteRace(OptimisticLockingFailureException race) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(staleProblem());
    }

    /** Reads {@code If-Match}, tolerating the quoted form — {@code CaseController} explains both. */
    private static Long parseIfMatch(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String value = header.trim();
        if (value.length() > 1 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return Long.valueOf(value);
    }

    private static ProblemDetail validationProblem(Map<String, List<String>> errors) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Validation failed");
        // The same sentence the other two modules use, deliberately: the fields say what is wrong
        // and this says where to look, and two wordings is how one product starts sounding like two.
        problem.setDetail("Check the highlighted fields.");
        problem.setProperty("errors", new HashMap<>(errors));
        return problem;
    }

    /**
     * The 403, worded by which permission was refused.
     *
     * <p>Two sentences rather than one, for §32's reason. A Test Engineer holds {@code result:submit}
     * and records results all day; telling them "you do not have permission to record results" when
     * what they were actually refused is amending somebody else's verdict sends them to ask for a
     * permission they already hold. Neither sentence names a permission key — a key is not actionable
     * by someone who cannot grant it to themselves, and naming it publishes the catalogue to exactly
     * the caller not entitled to it.
     */
    private static ProblemDetail forbiddenProblem(ExecutionService.Outcome.Forbidden forbidden) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Not allowed");
        problem.setDetail(forbidden.amending()
                ? "A result has already been recorded here, and changing one needs a test lead."
                : "You do not have permission to record results in this project.");
        return problem;
    }

    /**
     * The refusal that points at the other door.
     *
     * <p>Every other 403 in this controller ends the conversation: the caller cannot do the thing
     * and must ask somebody. This one is different — the caller <em>can</em> post results, through
     * {@code POST /ingestions}, and is holding the wrong endpoint. So the sentence names the way
     * forward rather than the refusal, which is the same judgement {@link #forbiddenProblem} makes
     * when it distinguishes amending from recording.
     *
     * <p>No permission key, for this file's standing reason: a key is not actionable by somebody
     * who cannot grant it to themselves, and naming it publishes the catalogue to exactly the
     * caller not entitled to it. "This credential" rather than "you", because the reader here is
     * a CI job's log and not a person at a screen.
     */
    private static ProblemDetail notExecutingProblem() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Not allowed");
        problem.setDetail(
                "Recording a result here is part of executing the run by hand, which this "
                        + "credential may not do. Post an automated suite's results to the "
                        + "ingestion endpoint instead.");
        return problem;
    }

    /**
     * The lost-update refusal.
     *
     * <p>Worded for this screen rather than reused from the case edit: "reload the case" on an
     * execution sends the reader to the wrong page, and what they need to see before reapplying is
     * the colleague's result, not the case's text.
     */
    private static ProblemDetail staleProblem() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Someone else recorded a result first");
        problem.setDetail(
                "Reload the execution to see their result before recording yours.");
        return problem;
    }
}
