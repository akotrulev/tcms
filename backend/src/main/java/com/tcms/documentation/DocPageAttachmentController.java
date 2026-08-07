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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

/**
 * Files on a documentation page — attach, download, remove.
 *
 * <p>Its own controller rather than three more methods on {@link DocPageController}, and the reason
 * is mechanical rather than stylistic: this speaks {@code multipart/form-data} and streams a binary
 * body, and both bring the {@link #onTooLarge} handler below. A {@code @ExceptionHandler} is scoped
 * to its controller, so putting these methods on {@link DocPageController} would make an oversized
 * upload's 413 the answer for a JSON {@code PATCH} that has nothing to do with files.
 * {@code EvidenceController} and {@code CaseAttachmentController} are shaped this way for the same
 * reason; this is the third instance and the argument has not weakened.
 *
 * <p><strong>There is no {@code GET} collection here, deliberately.</strong> The list is served as
 * {@code DocPage.attachments} by the page endpoint, which is the only screen that shows it. A second
 * read path would be a second answer to "what is attached to this page", and the two would be free
 * to disagree the moment one of them grew a filter — the call {@code TestCase.attachments} already
 * made one module over.
 *
 * <p>The problem bodies are {@link ProblemDetail}, not {@code repository.ApiProblem}, for the reason
 * {@link DocPageController}'s header sets out: that type is package-private in another module, and
 * this module declining to make a third hand-rolled envelope is what keeps the {@code shared}-module
 * decision an actual decision.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/doc-pages/{pageId}/attachments")
public class DocPageAttachmentController {

    private final DocPageAttachmentService files;

    DocPageAttachmentController(DocPageAttachmentService files) {
        this.files = files;
    }

    /**
     * Attaches a file to the page.
     *
     * <p>The bytes are read into memory with {@code getBytes()} rather than streamed to the store.
     * That is bounded by {@code spring.servlet.multipart.max-file-size}, which the container enforces
     * before this method runs, so the ceiling is explicit rather than accidental — and it is what
     * lets the checksum be computed and the row written <em>before</em> anything touches the disk,
     * which is the ordering {@link DocPageAttachmentService} depends on.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Object> attach(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID pageId,
            @RequestPart("file") MultipartFile file) throws IOException {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(DocPageController.validationProblem(Map.of(
                    "file", List.of("Choose a file to attach."))));
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

        DocPageAttachmentService.Outcome outcome =
                files.attach(UUID.fromString(jwt.getSubject()), projectId, pageId, upload);

        return switch (outcome) {
            case DocPageAttachmentService.Outcome.Attached attached ->
                    ResponseEntity.created(URI.create(attached.file().url()))
                            .body(attached.file());
            default -> problem(outcome);
        };
    }

    /**
     * The bytes — what {@code Attachment.url} points at.
     *
     * <p>No permission beyond the page read gate: anyone who may see the page may see what is
     * attached to it, and the catalogue has no {@code attachment:read}. Inventing one here would be
     * a permission no role is granted, which is a 403 nobody can fix.
     */
    @GetMapping("/{attachmentId}/content")
    public ResponseEntity<Resource> content(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID pageId,
            @PathVariable UUID attachmentId) {

        return files.open(UUID.fromString(jwt.getSubject()), projectId, pageId, attachmentId)
                .map(DocPageAttachmentController::body)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{attachmentId}")
    public ResponseEntity<Object> delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID pageId,
            @PathVariable UUID attachmentId) {

        DocPageAttachmentService.Outcome outcome =
                files.delete(UUID.fromString(jwt.getSubject()), projectId, pageId, attachmentId);

        return switch (outcome) {
            case DocPageAttachmentService.Outcome.Deleted ignored ->
                    ResponseEntity.noContent().build();
            default -> problem(outcome);
        };
    }

    /**
     * The failure half of both writes, mapped once.
     *
     * <p>ONE sentence for the 403, where {@code EvidenceController} has two: there is no authorship
     * arm to explain, because whoever may edit the page may remove any of its files. Wording it as
     * "you can only remove your own" would describe a rule this endpoint does not have — see
     * {@link DocPageAttachmentService}.
     */
    private static ResponseEntity<Object> problem(DocPageAttachmentService.Outcome outcome) {
        return switch (outcome) {
            case DocPageAttachmentService.Outcome.NotFound ignored ->
                    ResponseEntity.notFound().build();
            case DocPageAttachmentService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                            DocPageController.forbiddenProblem(
                                    "You do not have permission to edit documentation pages in "
                                            + "this project."));
            case DocPageAttachmentService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(DocPageController.validationProblem(
                            Map.of(invalid.field(), List.of(invalid.message()))));
            // 409 and not 400: nothing about the request is malformed, and the user fixes it by
            // editing the page rather than by resending this. The sentence names the count and the
            // remedy, because "in use" alone leaves somebody hunting a page for the block.
            case DocPageAttachmentService.Outcome.InUse inUse -> {
                ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
                problem.setTitle("File is in use");
                problem.setDetail(inUse.blocks() == 1
                        ? "An image block on this page shows this file. Remove or repoint that "
                                + "block first."
                        : inUse.blocks() + " image blocks on this page show this file. Remove or "
                                + "repoint them first.");
                yield ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
            }
            // The two success shapes. Reached only if a call site above routes one here — each
            // handles its own success and delegates the rest — so these arms exist to satisfy
            // exhaustiveness, not as a fallback that quietly renders an upload as an error.
            case DocPageAttachmentService.Outcome.Attached ignored ->
                    throw new IllegalStateException("An attached file was mapped as a problem");
            case DocPageAttachmentService.Outcome.Deleted ignored ->
                    throw new IllegalStateException("A deleted file was mapped as a problem");
        };
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
        problem.setDetail("Attachments are limited to 10 MB. Attach a smaller file.");
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(problem);
    }

    private static ResponseEntity<Resource> body(DocPageAttachmentService.Download download) {
        return ResponseEntity.ok()
                .contentType(contentType(download.contentType()))
                .contentLength(download.blob().size())
                // `inline`, so a diagram renders in place beside the paragraphs it belongs to
                // instead of landing in the downloads folder. The filename is the one
                // client-supplied string that reaches a response HEADER, where a newline is header
                // injection rather than an odd name; headerSafeFilename neutralises it.
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\""
                                + BlobUpload.headerSafeFilename(download.filename()) + "\"")
                .body(new InputStreamResource(download.blob().stream()));
    }

    /**
     * The stored content type, or the generic one if it cannot be parsed.
     *
     * <p>The value came from a client on the way in and is echoed on the way out, so a malformed one
     * must not turn every download of that file into a 500 — the bytes are still perfectly
     * serveable, and {@code application/octet-stream} is the honest description of bytes whose type
     * nobody can state.
     */
    private static MediaType contentType(String stored) {
        try {
            return MediaType.parseMediaType(stored);
        } catch (InvalidMediaTypeException malformed) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
