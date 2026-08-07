package com.tcms.execution;

import com.tcms.storage.BlobUpload;
import java.io.IOException;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

/**
 * Evidence on an execution — attach, download, remove.
 *
 * <p>Its own controller rather than three more methods on {@link RunController}: this is the only
 * surface in the product that speaks {@code multipart/form-data} and streams a binary body, and both
 * of those bring handlers ({@link #onTooLarge}) that would otherwise apply to every run endpoint
 * beside them.
 *
 * <p>The error envelope is Spring's {@link ProblemDetail}, following {@link RunController} — see its
 * header for why this module does not use {@code repository.ApiProblem}.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/runs/{runId}/executions/{executionId}/evidence")
public class EvidenceController {

    private final EvidenceService evidence;

    public EvidenceController(EvidenceService evidence) {
        this.evidence = evidence;
    }

    /**
     * Attaches a file to the result.
     *
     * <p>The bytes are read into memory with {@code getBytes()} rather than streamed to the store.
     * That is bounded by {@code spring.servlet.multipart.max-file-size} (10 MB), which the container
     * enforces before this method runs, so the ceiling is explicit rather than accidental — and it
     * is what lets the checksum be computed and the row written <em>before</em> anything touches the
     * disk, which is the ordering {@link EvidenceService} depends on. A streaming implementation
     * would have to write the blob first and would give up that property to save 10 MB.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Object> attach(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID runId,
            @PathVariable UUID executionId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "caption", required = false) String caption,
            @RequestParam(value = "stepId", required = false) UUID stepId) throws IOException {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(validationProblem(Map.of(
                    "file", List.of("Choose a file to attach."))));
        }

        EvidenceUpload upload = new EvidenceUpload(new BlobUpload(
                BlobUpload.sanitizeFilename(file.getOriginalFilename()),
                // A client that sends no content type gets the generic one rather than a null in a
                // NOT NULL column. Guessing from the extension would be worse: it would state a
                // type nobody claimed, and the browser trusts this header when rendering.
                file.getContentType() == null
                        ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                        : file.getContentType(),
                file.getSize(),
                file.getBytes()),
                caption);

        EvidenceService.Outcome outcome = evidence.attach(
                UUID.fromString(jwt.getSubject()), projectId, runId, executionId, stepId, upload);

        return switch (outcome) {
            case EvidenceService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            case EvidenceService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(validationProblem(invalid.errors()));
            case EvidenceService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                            forbidden("You do not have permission to attach evidence in this "
                                    + "project."));
            case EvidenceService.Outcome.Attached attached ->
                    ResponseEntity.created(URI.create(attached.view().url()))
                            .body(attached.view());
            case EvidenceService.Outcome.RunClosed ignored ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(runClosed());
            // Unreachable: attach never removes anything and never consults authorship. The arms
            // stay written because the switch is exhaustive over the sealed type, which is what
            // makes a new outcome a compile error at every consumer rather than a silently
            // unhandled case.
            case EvidenceService.Outcome.NotTheAuthor ignored ->
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            case EvidenceService.Outcome.Deleted ignored ->
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
    }

    /**
     * The bytes — what {@code Evidence.url} points at.
     *
     * <p>No permission beyond the project read gate: anyone who may see the execution may see its
     * evidence, and the catalogue has no {@code evidence:read}. Inventing one here would be a
     * permission no role is granted, which is a 403 nobody can fix.
     */
    @GetMapping("/{evidenceId}/content")
    public ResponseEntity<Resource> content(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID runId,
            @PathVariable UUID executionId,
            @PathVariable UUID evidenceId) {

        return evidence
                .open(UUID.fromString(jwt.getSubject()), projectId, runId, executionId, evidenceId)
                .map(EvidenceController::body)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{evidenceId}")
    public ResponseEntity<Object> delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID runId,
            @PathVariable UUID executionId,
            @PathVariable UUID evidenceId) {

        EvidenceService.Outcome outcome = evidence.delete(
                UUID.fromString(jwt.getSubject()), projectId, runId, executionId, evidenceId);

        return switch (outcome) {
            case EvidenceService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            case EvidenceService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                            forbidden("You do not have permission to remove evidence in this "
                                    + "project."));
            // The second sentence, and the reason there are two. A Test Engineer removes their own
            // attachments all day, so "you do not have permission" would be false — and false in
            // the direction that sends them to ask an administrator for something they already
            // hold. §32's argument, one entity over.
            case EvidenceService.Outcome.NotTheAuthor ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                            forbidden("You can only remove evidence you attached. Ask a test lead "
                                    + "to remove this one."));
            case EvidenceService.Outcome.Deleted ignored -> ResponseEntity.noContent().build();
            case EvidenceService.Outcome.RunClosed ignored ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(runClosed());
            // Unreachable: a delete has no input to validate and creates nothing. Written out
            // because the switch is exhaustive over the sealed type — which is what turned adding
            // Outcome.Invalid into a compile error here rather than a silently unhandled case.
            case EvidenceService.Outcome.Attached ignored ->
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            case EvidenceService.Outcome.Invalid ignored ->
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
    }

    /**
     * A file over {@code spring.servlet.multipart.max-file-size}.
     *
     * <p>Spring throws this <em>before</em> the handler runs, so it cannot be an
     * {@code Outcome} — and without this handler it surfaces as a bare 500, which tells the user
     * "something went wrong" about the one upload failure that is entirely theirs to fix.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Object> onTooLarge(MaxUploadSizeExceededException ignored) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.PAYLOAD_TOO_LARGE);
        problem.setTitle("File too large");
        problem.setDetail("Evidence files are limited to 10 MB. Attach a smaller file.");
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(problem);
    }

    private static ResponseEntity<Resource> body(EvidenceService.Download download) {
        return ResponseEntity.ok()
                .contentType(contentType(download.contentType()))
                .contentLength(download.blob().size())
                // `inline`, so the SPA can render a screenshot in place instead of downloading it —
                // which is the whole point of showing evidence beside a failed step. The filename
                // is the one client-supplied string that reaches a response HEADER, where a newline
                // is header injection rather than an odd name; headerSafeFilename neutralises it.
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\""
                                + BlobUpload.headerSafeFilename(download.filename()) + "\"")
                .body(new InputStreamResource(download.blob().stream()));
    }

    /**
     * The stored content type, or the generic one if it cannot be parsed.
     *
     * <p>The value came from a client on the way in and is echoed on the way out, so a malformed
     * one must not turn every download of that file into a 500 — the bytes are still perfectly
     * serveable, and {@code application/octet-stream} is the honest description of bytes whose type
     * nobody can state.
     */
    private static MediaType contentType(String stored) {
        try {
            return MediaType.parseMediaType(stored);
        } catch (org.springframework.http.InvalidMediaTypeException malformed) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    /** The frozen-run refusal. The sentence is {@link RunLifecycle#CLOSED_MESSAGE}'s, not a copy. */
    private static ProblemDetail runClosed() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("This run cannot change that way");
        problem.setDetail(RunLifecycle.CLOSED_MESSAGE);
        return problem;
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
        // The same sentence the rest of the product uses — two wordings is how one product starts
        // sounding like two.
        problem.setDetail("Check the highlighted fields.");
        problem.setProperty("errors", new HashMap<>(errors));
        return problem;
    }
}
