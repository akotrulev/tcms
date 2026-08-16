package com.tcms.documentation;

import com.tcms.storage.BlobUpload;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

/**
 * A {@code FILE} artifact — registered with its bytes, and downloaded.
 *
 * <p>Its own controller rather than two more methods on {@link ArtifactController}, for the reason
 * {@code EvidenceController}, {@code CaseAttachmentController} and {@link DocPageAttachmentController}
 * each give: this speaks {@code multipart/form-data} and streams a binary body, and both bring the
 * {@link #onTooLarge} handler. A {@code @ExceptionHandler} is scoped to its controller, so putting
 * these methods beside the register's JSON {@code POST} would make an oversized upload's 413 the
 * answer for a request that carries no file at all. Fourth instance, and the argument has not
 * weakened.
 *
 * <h2>A separate path, not a second media type on the same one</h2>
 *
 * <p>{@code POST /artifacts/files} rather than a multipart variant of {@code POST /artifacts}. The
 * tempting alternative narrows the existing mapping to {@code consumes = application/json} so the
 * two can share a path — which silently turns every client that posts a reference without a
 * {@code Content-Type} into a 415, on an endpoint this slice was not asked to change. The path
 * segment is the cheaper honesty.
 *
 * <p><strong>There is no {@code PUT} and no detach.</strong> An artifact's bytes are fixed at
 * registration: replacing the document is superseding it (plan §6n), which produces a second row
 * naming the first. See {@link ArtifactWriteService#createFile}.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/artifacts")
public class ArtifactFileController {

    private final ArtifactService register;
    private final ArtifactWriteService writes;

    ArtifactFileController(ArtifactService register, ArtifactWriteService writes) {
        this.register = register;
        this.writes = writes;
    }

    /**
     * Registers an uploaded file as an artifact — <strong>one request, two rows and the bytes.</strong>
     *
     * <p>Two parts: {@code artifact} carries the register fields as JSON and {@code file} carries the
     * bytes. {@link ArtifactFileInput} has no {@code kind}, so there is no way to send a payload that
     * disagrees with the endpoint it was sent to.
     *
     * <p>The bytes are read into memory with {@code getBytes()} rather than streamed to the store,
     * bounded by {@code spring.servlet.multipart.max-file-size}, which the container enforces before
     * this method runs. That is what lets the checksum be computed and both rows written
     * <em>before</em> anything touches the disk — the ordering {@link ArtifactWriteService} depends
     * on.
     */
    /** A header that is present and blank is a header that was not sent — see the sibling. */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @PostMapping(path = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Object> register(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestPart("artifact") ArtifactFileInput input,
            @RequestPart("file") MultipartFile file) throws IOException {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(
                    problem(Map.of("file", List.of("Choose a file to register."))));
        }

        BlobUpload upload = new BlobUpload(
                BlobUpload.sanitizeFilename(file.getOriginalFilename()),
                // A client that sends no content type gets the generic one rather than a null in a
                // NOT NULL column. Guessing from the extension would be worse: it would state a type
                // nobody claimed, and the browser trusts this header when rendering.
                file.getContentType() == null
                        ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                        : file.getContentType(),
                file.getSize(),
                file.getBytes());

        ArtifactWriteService.Outcome outcome = writes.createFile(
                UUID.fromString(jwt.getSubject()), projectId, input, upload,
                trimToNull(idempotencyKey));

        return switch (outcome) {
            case ArtifactWriteService.Outcome.Created created -> ResponseEntity
                    .created(location(projectId, created.view().id()))
                    .body(created.view());
            /*
              200, no Location, and NO SECOND BLOB — the reason this path carries a key at all.

              The bytes are read from the request before the replay is known (the container has
              already streamed them into memory by the time this method runs), and they are then
              simply not written: `ArtifactWriteService.createFile` answers from the stored row
              before it reaches the store. So a retried upload costs the upload again and leaves
              one copy on disk, which is the honest best a stateless retry can do.
            */
            case ArtifactWriteService.Outcome.Replayed replayed ->
                    ResponseEntity.ok(replayed.view());
            case ArtifactWriteService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(problem(invalid.errors()));
            case ArtifactWriteService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            case ArtifactWriteService.Outcome.ForbiddenAtScope ignored -> ResponseEntity
                    .status(HttpStatus.FORBIDDEN)
                    .body(scopeProblem());
            case ArtifactWriteService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            // The delete's and the status endpoint's outcomes, unreachable here. Written out
            // rather than defaulted, because a sealed interface's whole value is that a further
            // outcome fails to compile here instead of falling into an arm that renders it as
            // something else — which §209 collected on: adding two outcomes reddened this file.
            case ArtifactWriteService.Outcome.Deleted ignored ->
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            case ArtifactWriteService.Outcome.Undeletable ignored ->
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            case ArtifactWriteService.Outcome.Updated ignored ->
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            case ArtifactWriteService.Outcome.Unchangeable ignored ->
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
    }

    /**
     * The bytes — what {@code Artifact.url} points at for a {@code FILE}.
     *
     * <p>No permission beyond the register's read gate: anyone who may see that the document is
     * registered may open it, and the catalogue has no {@code attachment:read}. The 404 covers an
     * unreachable project, an artifact of another project, an artifact that is a link rather than an
     * upload, and a row whose blob is missing — all four collapsed, per §6t and
     * {@link ArtifactService#resolveFile}.
     */
    @GetMapping("/{artifactId}/content")
    public ResponseEntity<Resource> content(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID artifactId) {

        return register.openFile(UUID.fromString(jwt.getSubject()), projectId, artifactId)
                .map(ArtifactFileController::body)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * {@code attachment} served as a download rather than {@code inline}.
     *
     * <p><strong>The one deliberate difference from every other attachment response in the
     * product.</strong> A page's image and a case's screenshot are {@code inline} because they are
     * rendered beside the text they illustrate. An artifact is a held document — a signed-off PDF, a
     * seed-data archive, an environment guide — reached from a register that lists it by name, and
     * what somebody clicking it wants is the file, under the name it was uploaded with. Rendering a
     * 40 MB archive inline is a browser deciding what to do with bytes nobody asked it to display.
     */
    private static ResponseEntity<Resource> body(ArtifactService.Download download) {
        return ResponseEntity.ok()
                .contentType(contentType(download.contentType()))
                .contentLength(download.blob().size())
                // The filename is the one client-supplied string that reaches a response HEADER,
                // where a carriage return is header injection rather than an odd name.
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\""
                                + BlobUpload.headerSafeFilename(download.filename()) + "\"")
                .body(new InputStreamResource(download.blob().stream()));
    }

    /**
     * The stored content type, or the generic one if it cannot be parsed — a value that came from a
     * client on the way in must not turn every download of that file into a 500.
     */
    private static MediaType contentType(String stored) {
        try {
            return MediaType.parseMediaType(stored);
        } catch (InvalidMediaTypeException malformed) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    /** {@link ArtifactController#problem}'s envelope, worded for an upload. */
    private static ProblemDetail problem(Map<String, List<String>> errors) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "The file could not be registered.");
        detail.setTitle("Invalid artifact");
        detail.setProperty("errors", errors);
        return detail;
    }

    /** The scope-qualified refusal, in the words the register's own create uses. */
    private static ProblemDetail scopeProblem() {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN,
                "Your role can add artifacts to a plan, but not to the project.");
        detail.setTitle("Not allowed at this scope");
        detail.setProperty("errors", Map.of(
                "scope",
                List.of("Your role can add artifacts to a plan, but not to the project as a whole. "
                        + "Project artifacts are inherited by every plan, so a Test Lead owns them.")));
        return detail;
    }

    /**
     * A file over {@code spring.servlet.multipart.max-file-size}.
     *
     * <p>Spring throws this <em>before</em> the handler runs, so it cannot be an {@code Outcome} —
     * and without this handler it surfaces as a bare 500, which tells the user "something went
     * wrong" about the one upload failure that is entirely theirs to fix.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Object> onTooLarge(MaxUploadSizeExceededException ignored) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.PAYLOAD_TOO_LARGE);
        problem.setTitle("File too large");
        problem.setDetail("Artifacts are limited to 10 MB. Register a smaller file, or add it as a "
                + "link to where it is stored.");
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(problem);
    }

    /** {@link ArtifactController}'s, and coarse for the same reason: there is no per-artifact GET. */
    private static URI location(UUID projectId, UUID artifactId) {
        return URI.create("/api/v1/projects/" + projectId + "/artifacts#" + artifactId);
    }
}
