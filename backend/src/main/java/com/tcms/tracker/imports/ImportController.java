package com.tcms.tracker.imports;

import com.tcms.api.Problem;
import com.tcms.project.GateResult;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The import family's read operations — {@code POST /imports/source-projects} and
 * {@code POST /imports/preview}. The run, its report and the list are T4's.
 *
 * <h2>Both are {@code POST} and neither writes</h2>
 *
 * <p>The body carries a credential, and a credential in a query string is in the access log, the
 * proxy log and the browser history. That is the whole reason for the verb, and it is worth stating
 * on the class rather than leaving somebody to read {@code POST} as a write: the preview's promise
 * is that nothing is written, and {@code ImportPreviewWritesNothingTest} is what holds it.
 *
 * <h2>Both cost {@code issue:import}, and the two 403s are not the same refusal</h2>
 *
 * <p>{@code /preview} names a project, so its 403 is about that project; the source-project list
 * names none and is gated on holding the key <em>somewhere</em>
 * ({@link ImportPreviewService#sourceProjects}). Both are D-e's one key for the whole family, and
 * both report it at {@code PROJECT} scope, which is where it is granted (roles spec §A3).
 *
 * <h2>The two exception arms are different answers to different people</h2>
 *
 * <p>{@link ImportUrlRefusedException} is a <strong>400</strong>: the address is wrong and the
 * person who typed it can fix it. {@link ImportSourceUnavailableException} is a
 * <strong>502</strong>: the address was fine and the far end did not co-operate. Folding them into
 * one status would tell somebody with a typo that Jira is down, and telling the 502's own causes
 * apart would make the endpoint an oracle for what exists in a Jira the caller cannot read — D16's
 * argument one module over.
 */
@RestController
@RequestMapping("/api/v1")
public class ImportController {

    private final ImportPreviewService previews;

    public ImportController(ImportPreviewService previews) {
        this.previews = previews;
    }

    @PostMapping("/imports/source-projects")
    public ResponseEntity<Object> sourceProjects(
            @AuthenticationPrincipal Jwt jwt, @RequestBody ImportConnection connection) {

        return switch (previews.sourceProjects(UUID.fromString(jwt.getSubject()), connection)) {
            case GateResult.Unreachable<SourceProjectListView> ignored ->
                    // Unreachable in both senses: this operation names no project, so the gate
                    // cannot produce this state. The arm exists because the switch is exhaustive
                    // over the sealed type, and answering 404 is the safer of the two things a
                    // never-taken branch could do.
                    ResponseEntity.notFound().build();
            case GateResult.Denied<SourceProjectListView> ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problem.forbidden(
                            "You do not have permission to import from an external tracker.",
                            "issue:import", Problem.Scope.PROJECT));
            case GateResult.Allowed<SourceProjectListView> allowed ->
                    ResponseEntity.ok(allowed.value());
        };
    }

    @PostMapping("/imports/preview")
    public ResponseEntity<Object> preview(
            @AuthenticationPrincipal Jwt jwt, @RequestBody ImportPreviewInput input) {

        return switch (previews.preview(UUID.fromString(jwt.getSubject()), input)) {
            case GateResult.Unreachable<ImportPreviewView> ignored ->
                    ResponseEntity.notFound().build();
            case GateResult.Denied<ImportPreviewView> ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problem.forbidden(
                            "You do not have permission to import into this project.",
                            "issue:import", Problem.Scope.PROJECT));
            case GateResult.Allowed<ImportPreviewView> allowed ->
                    ResponseEntity.ok(allowed.value());
        };
    }

    /**
     * The guard refused the site before it was dialled.
     *
     * <p>The message is the guard's own sentence per verdict, and it names the host and never the
     * path — a refusal that echoed what somebody pasted would put a credential in a response body
     * on the one endpoint where the body is a credential.
     */
    @ExceptionHandler(ImportUrlRefusedException.class)
    public ResponseEntity<Object> refused(ImportUrlRefusedException refusal) {
        return ResponseEntity.badRequest().body(new Problem(
                "import-url-refused",
                "That site address cannot be used",
                HttpStatus.BAD_REQUEST.value(),
                refusal.getMessage(),
                null));
    }

    /** The source rejected the credential, refused the account, or did not answer. */
    @ExceptionHandler(ImportSourceUnavailableException.class)
    public ResponseEntity<Object> unavailable(ImportSourceUnavailableException failure) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new Problem(
                "import-source-unavailable",
                "The source system did not answer",
                HttpStatus.BAD_GATEWAY.value(),
                failure.getMessage(),
                null));
    }
}
