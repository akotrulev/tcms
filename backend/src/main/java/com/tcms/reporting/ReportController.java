package com.tcms.reporting;

import java.net.URI;
import java.util.HashMap;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The report pipeline's three operations: ask for one, watch it, take it away.
 *
 * <p>They are one controller because they are one conversation — a client that calls the first calls
 * the other two, in that order, about the same job — and because the download's binary body brings
 * response handling that would otherwise sit beside every reporting endpoint written after it
 * ({@code EvidenceController}'s reason for existing separately from {@code RunController}).
 *
 * <h2>The 202 and the poll answer the same shape</h2>
 *
 * <p>{@link ReportJobView}, both times. §6e makes generation asynchronous by contract, so the accept
 * is the first of a series of answers to one question and not a different kind of answer; a client
 * that has to parse two shapes to follow one job holds the accept's parser on a path it never
 * exercises again.
 *
 * <h2>Why {@code parameters} is re-serialized rather than bound</h2>
 *
 * <p>The request body's {@code parameters} is a free-form JSON object — every report takes different
 * ones — so it arrives as a {@link com.fasterxml.jackson.databind.JsonNode} and goes to the service
 * as text. Binding it to a Java type would be a second definition of every report's inputs that has
 * to move whenever the first does, which is the copy that drifts (§58 E1); {@link ReportParameters}
 * is where the one reading lives.
 *
 * <p>The error envelope is Spring's {@link ProblemDetail}, following {@code RunController} and
 * {@code EvidenceController} — {@code repository.ApiProblem} is in a module this one may not import,
 * and duplicating it for three endpoints would be the same E1 one layer up.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/reports")
class ReportController {

    private final ReportJobService jobs;
    private final ReportReadService reads;

    ReportController(ReportJobService jobs, ReportReadService reads) {
        this.jobs = jobs;
        this.reads = reads;
    }

    /**
     * The request body.
     *
     * @param format text, not a bound {@link ReportFormat}. Jackson's refusal of an unknown enum
     *     value is a 400 about deserialization, where the product's own refusal names the formats
     *     this server can actually render — so the parse lives in {@link ReportJobService} beside
     *     the other two refusals, and an unparseable value takes the same path as an unrenderable
     *     one.
     * @param definitionId a saved report to regenerate (§91), or absent for an ad-hoc request.
     *     Bound as a {@link UUID} where {@code type} and {@code format} are text, and the
     *     difference is not an inconsistency: those two are open sets whose refusals name what this
     *     server has, so the product must word them; an id that is not an id names nothing under
     *     any deployment, and Jackson's refusal says exactly that.
     * @param parameters may be absent; {@code null} becomes {@code "{}"} in the service, which is
     *     what {@code V27} defaults the column to — or the saved definition's parameters, when one
     *     is named
     */
    record GenerateRequest(
            UUID definitionId,
            String type,
            String format,
            com.fasterxml.jackson.databind.JsonNode parameters) {
    }

    @PostMapping("/generate")
    ResponseEntity<Object> generate(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestBody GenerateRequest request) {

        ReportJobService.Outcome outcome = jobs.request(
                UUID.fromString(jwt.getSubject()),
                projectId,
                request.definitionId(),
                request.type(),
                request.format(),
                request.parameters() == null ? null : request.parameters().toString());

        return switch (outcome) {
            case ReportJobService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            case ReportJobService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(forbidden(
                            "You do not have permission to generate reports in this project."));
            case ReportJobService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(validationProblem(invalid.errors()));
            // 202 with a Location pointing at the poll, so a client that follows Location rather
            // than reading the body arrives at the one route that can tell it anything further.
            case ReportJobService.Outcome.Accepted accepted -> ResponseEntity
                    .accepted()
                    .location(URI.create("/api/v1/projects/" + projectId + "/reports/jobs/"
                            + accepted.job().jobId()))
                    .body(accepted.job());
        };
    }

    /**
     * Where a job got to.
     *
     * <p>A {@code FAILED} job is a 200 carrying {@code error} — the poll succeeded, the report is
     * what failed, and an error status here is what a client retries instead of showing the sentence
     * that explains the problem.
     */
    @GetMapping("/jobs/{jobId}")
    ResponseEntity<Object> job(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID jobId) {

        ReportReadService.Outcome<ReportJobView> outcome =
                reads.poll(UUID.fromString(jwt.getSubject()), projectId, jobId);

        return switch (outcome) {
            case ReportReadService.Outcome.NotFound<ReportJobView> ignored ->
                    ResponseEntity.notFound().build();
            case ReportReadService.Outcome.Forbidden<ReportJobView> ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(forbidden(
                            "You do not have permission to view reports in this project."));
            case ReportReadService.Outcome.Found<ReportJobView> found ->
                    ResponseEntity.ok(found.value());
        };
    }

    /**
     * The bytes — what {@code ReportJob.downloadUrl} and {@code modelUrl} point at.
     *
     * <p>{@code attachment}, where evidence is served {@code inline}: a screenshot is shown beside
     * the step it failed on, and a report is a file somebody keeps. The filename is composed
     * entirely from server-generated values ({@link ReportReadService}), which is what keeps a
     * newline out of a response header without a sanitizer standing between them.
     */
    @GetMapping("/artifacts/{artifactId}/content")
    ResponseEntity<Resource> content(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID artifactId) {

        ReportReadService.Outcome<ReportReadService.Download> outcome =
                reads.open(UUID.fromString(jwt.getSubject()), projectId, artifactId);

        return switch (outcome) {
            case ReportReadService.Outcome.NotFound<ReportReadService.Download> ignored ->
                    ResponseEntity.notFound().build();
            // No body on the 403 here, unlike the other two: the response is typed to a binary
            // Resource, and a client that asked for bytes and is handed a JSON problem document
            // under a status it can already read learns nothing it could act on.
            case ReportReadService.Outcome.Forbidden<ReportReadService.Download> ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            case ReportReadService.Outcome.Found<ReportReadService.Download> found ->
                    body(found.value());
        };
    }

    private static ResponseEntity<Resource> body(ReportReadService.Download download) {
        return ResponseEntity.ok()
                .contentType(contentType(download.contentType()))
                .contentLength(download.blob().size())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + download.filename() + "\"")
                .body(new InputStreamResource(download.blob().stream()));
    }

    /**
     * The renderer's media type, or the generic one if it cannot be parsed.
     *
     * <p>{@code EvidenceController}'s argument with a different source: the value comes from a
     * renderer rather than from a client, so a malformed one is a bug here — but the bytes are still
     * perfectly serveable, and failing a download over the string that describes it would lose the
     * artifact to protect a header.
     */
    private static MediaType contentType(String declared) {
        try {
            return MediaType.parseMediaType(declared);
        } catch (org.springframework.http.InvalidMediaTypeException malformed) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private static ProblemDetail forbidden(String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Not allowed");
        problem.setDetail(detail);
        return problem;
    }

    private static ProblemDetail validationProblem(Map<String, List<String>> errors) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Validation failed");
        // The product's one sentence for this. Two wordings is how one product starts sounding
        // like two.
        problem.setDetail("Check the highlighted fields.");
        problem.setProperty("errors", new HashMap<>(errors));
        return problem;
    }
}
