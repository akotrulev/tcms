package com.tcms.execution;

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

    public RunController(RunService runs, ExecutionService executions) {
        this.runs = runs;
        this.executions = executions;
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
            case ExecutionService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(validationProblem(invalid.errors()));
            case ExecutionService.Outcome.Stale ignored ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(staleProblem());
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
