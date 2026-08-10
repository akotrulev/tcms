package com.tcms.execution;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
 * {@code POST /projects/{projectId}/ingestions} — where CI posts its results.
 *
 * <p><strong>The one endpoint in the product whose expected caller is not a browser.</strong>
 * Everything that follows from that is in {@link IngestionService}; what belongs here is the two
 * ways this controller differs from every other one.
 *
 * <p><strong>{@code Idempotency-Key} is {@code required = false} at the binding and required by the
 * validation.</strong> Declared required, Spring answers a missing header with its own 400 — a
 * {@code ProblemDetail} with no {@code errors} property — and the caller's error handling, which
 * reads {@code errors}, sees a shape it has no branch for. Every refusal from this endpoint has the
 * same shape, so the CI job has one thing to parse.
 *
 * <p><strong>201 with a {@code Location}, and a replay is 200 without one.</strong> Both carry the
 * whole job, because the interesting part of the response is the counts and the unmatched names,
 * and a CI step that has to follow a redirect to find out whether its results landed will not.
 *
 * <p><strong>The two {@code GET}s are not for CI</strong>, and they are why that {@code Location}
 * now names something. Until this slice it pointed at a resource that had never existed — a claim
 * this API made and could not honour. They are read by a person: the history screen, and the binder
 * that turns an unmatched name into a mapping. {@link IngestionReadService} carries the gate.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/ingestions")
public class IngestionController {

    private final IngestionService ingestion;
    private final IngestionReadService history;

    public IngestionController(IngestionService ingestion, IngestionReadService history) {
        this.ingestion = ingestion;
        this.history = history;
    }

    /** This project's ingestion history, newest first. Project membership. */
    @GetMapping
    public ResponseEntity<Object> list(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {

        return history.list(caller(jwt), projectId)
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** One job and the tests it could not place — the resource {@code Location} names. */
    @GetMapping("/{jobId}")
    public ResponseEntity<Object> findOne(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID jobId) {

        return history.findOne(caller(jwt), projectId, jobId)
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * The verbatim file CI posted for this job (§160).
     *
     * <p><strong>{@code inline}, not {@code attachment}</strong> — the opposite of a generated
     * report and for {@code EvidenceController}'s reason. A report is a file somebody keeps; this is
     * 40 lines of XML somebody opens from the ingestion history to check one test name against what
     * the binder says, and forcing a download puts a second application between the question and the
     * answer. The filename is composed entirely from server-generated values, which is what keeps a
     * newline out of a response header without a sanitizer standing between them.
     *
     * <p>One bodiless 404 covers every way this is unreachable, including a job posted before §160
     * and a blob that is gone — {@link IngestionReadService#openRawReport} carries why those are not
     * told apart. No 403 arm: the gate is membership, so an unreachable project is already the 404.
     */
    @GetMapping("/{jobId}/report")
    public ResponseEntity<Resource> rawReport(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID jobId) {

        return history.openRawReport(caller(jwt), projectId, jobId)
                .map(IngestionController::body)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static ResponseEntity<Resource> body(IngestionReadService.RawReport report) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(report.contentType()))
                .contentLength(report.blob().size())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + report.filename() + "\"")
                .body(new InputStreamResource(report.blob().stream()));
    }

    /** Posts a CI report. 201 when ingested, 200 on a replay of the same {@code Idempotency-Key}. */
    @PostMapping
    public ResponseEntity<Object> ingest(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) IngestionRequest request) {

        IngestionService.Outcome outcome =
                ingestion.ingest(caller(jwt), projectId, request, idempotencyKey);

        return switch (outcome) {
            case IngestionService.Outcome.Ingested ingested -> ResponseEntity
                    .created(URI.create("/api/v1/projects/" + projectId + "/ingestions/"
                            + ingested.view().id()))
                    .body(ingested.view());
            case IngestionService.Outcome.Replayed replayed ->
                    ResponseEntity.ok(replayed.view());
            case IngestionService.Outcome.NotFound ignored ->
                    ResponseEntity.notFound().build();
            case IngestionService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(forbiddenProblem());
            case IngestionService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(validationProblem(invalid.errors()));
            case IngestionService.Outcome.RunClosed ignored ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(runClosedProblem());
        };
    }

    private static UUID caller(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private static ProblemDetail validationProblem(Map<String, List<String>> errors) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Validation failed");
        problem.setDetail("That report could not be ingested.");
        problem.setProperty("errors", errors);
        return problem;
    }

    private static ProblemDetail forbiddenProblem() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Not permitted");
        problem.setDetail("This credential may not record results in this project.");
        return problem;
    }

    private static ProblemDetail runClosedProblem() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("The run has ended");
        problem.setDetail("That run is closed, so its results are frozen. Reopen it to post more.");
        return problem;
    }
}
