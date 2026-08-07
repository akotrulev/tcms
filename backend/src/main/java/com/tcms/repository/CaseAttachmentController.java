package com.tcms.repository;

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
 * Files on a case — attach, download, remove.
 *
 * <p>Its own controller rather than three more methods on {@link CaseController}, for
 * {@code EvidenceController}'s stated reason and not merely by analogy: this speaks
 * {@code multipart/form-data} and streams a binary body, and both bring an
 * {@link #onTooLarge} handler that would otherwise apply to every case endpoint beside it — turning
 * an oversized upload's 413 into the answer for a JSON PATCH that has nothing to do with files.
 *
 * <p><strong>There is no {@code GET} collection here, deliberately.</strong> The list is served as
 * {@code TestCase.attachments} by the detail endpoint, which is the only screen that shows it; a
 * second read path would be a second answer to "what is attached to this case", and the two would be
 * free to disagree the moment one of them grew a filter.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/cases/{caseId}/attachments")
class CaseAttachmentController {

    private final CaseAttachmentService files;

    CaseAttachmentController(CaseAttachmentService files) {
        this.files = files;
    }

    /**
     * Attaches a file to the case.
     *
     * <p>The bytes are read into memory with {@code getBytes()} rather than streamed to the store.
     * That is bounded by {@code spring.servlet.multipart.max-file-size}, which the container enforces
     * before this method runs, so the ceiling is explicit rather than accidental — and it is what
     * lets the checksum be computed and the row written <em>before</em> anything touches the disk,
     * which is the ordering {@link CaseAttachmentService} depends on. A streaming implementation
     * would have to write the blob first and would give that up to save 10 MB.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<Object> attach(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID caseId,
            @RequestPart("file") MultipartFile file) throws IOException {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiProblem.validation(
                    Map.of("file", List.of("Choose a file to attach."))));
        }

        BlobUpload upload = new BlobUpload(
                BlobUpload.sanitizeFilename(file.getOriginalFilename()),
                // A client that sends no content type gets the generic one rather than a null in a
                // NOT NULL column. Guessing from the extension would be worse: it would state a
                // type nobody claimed, and the browser trusts this header when rendering.
                file.getContentType() == null
                        ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                        : file.getContentType(),
                file.getSize(),
                file.getBytes());

        CaseAttachmentService.Outcome outcome =
                files.attach(UUID.fromString(jwt.getSubject()), projectId, caseId, upload);

        return switch (outcome) {
            case CaseAttachmentService.Outcome.NotFound ignored ->
                    ResponseEntity.notFound().build();
            case CaseAttachmentService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiProblem.forbidden(
                            "You do not have permission to edit cases in this project."));
            case CaseAttachmentService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(ApiProblem.validation(
                            Map.of(invalid.field(), List.of(invalid.message()))));
            case CaseAttachmentService.Outcome.Attached attached ->
                    ResponseEntity.created(URI.create(attached.file().url()))
                            .body(attached.file());
            // Unreachable: attach removes nothing. Written out because the switch is exhaustive
            // over the sealed type, which is what makes a new outcome a compile error here rather
            // than a silently unhandled case.
            case CaseAttachmentService.Outcome.Deleted ignored ->
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
    }

    /**
     * The bytes — what {@code CaseAttachment.url} points at.
     *
     * <p>No permission beyond the case read gate: anyone who may see the case may see what is
     * attached to it, and the catalogue has no {@code attachment:read}. Inventing one here would be
     * a permission no role is granted, which is a 403 nobody can fix.
     */
    @GetMapping("/{attachmentId}/content")
    ResponseEntity<Resource> content(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID caseId,
            @PathVariable UUID attachmentId) {

        return files.open(UUID.fromString(jwt.getSubject()), projectId, caseId, attachmentId)
                .map(CaseAttachmentController::body)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{attachmentId}")
    ResponseEntity<Object> delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID caseId,
            @PathVariable UUID attachmentId) {

        CaseAttachmentService.Outcome outcome =
                files.delete(UUID.fromString(jwt.getSubject()), projectId, caseId, attachmentId);

        return switch (outcome) {
            case CaseAttachmentService.Outcome.NotFound ignored ->
                    ResponseEntity.notFound().build();
            // ONE sentence, where EvidenceController has two. There is no author arm to explain:
            // whoever may edit the case may remove any of its files, so "you can only remove your
            // own" would be a rule this endpoint does not have. See CaseAttachmentService's header.
            case CaseAttachmentService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiProblem.forbidden(
                            "You do not have permission to edit cases in this project."));
            case CaseAttachmentService.Outcome.Deleted ignored ->
                    ResponseEntity.noContent().build();
            // Unreachable: a delete has no input to validate and creates nothing.
            case CaseAttachmentService.Outcome.Attached ignored ->
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            case CaseAttachmentService.Outcome.Invalid ignored ->
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
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
    ResponseEntity<Object> onTooLarge(MaxUploadSizeExceededException ignored) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(new ApiProblem(
                "about:blank",
                "File too large",
                HttpStatus.PAYLOAD_TOO_LARGE.value(),
                "Attachments are limited to 10 MB. Attach a smaller file.",
                null));
    }

    private static ResponseEntity<Resource> body(CaseAttachmentService.Download download) {
        return ResponseEntity.ok()
                .contentType(contentType(download.contentType()))
                .contentLength(download.blob().size())
                // `inline`, so a reference screenshot renders in place beside the steps it belongs
                // to instead of landing in the downloads folder. The filename is the one
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
