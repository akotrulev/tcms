package com.tcms.tracker.imports;

import com.tcms.api.Problem;
import com.tcms.project.GateResult;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The import family — all five operations.
 *
 * <h2>Three of the five are {@code POST} and two of those write nothing</h2>
 *
 * <p>{@code /imports/source-projects} and {@code /imports/preview} carry a credential in the body,
 * and a credential in a query string is in the access log, the proxy log and the browser history.
 * That is the whole reason for the verb on those two, and it is worth stating on the class rather
 * than leaving somebody to read {@code POST} as a write: the preview's promise is that nothing is
 * written, and {@code ImportPreviewWritesNothingTest} is what holds it.
 *
 * <h2>{@code POST /imports} answers 202, not 201</h2>
 *
 * <p>The read pass against the source takes minutes for a real project, so the run is recorded,
 * dispatched, and returned as a row to poll at {@code GET /imports/{importRunId}}. Everything that
 * can be refused without the whole snapshot is refused <em>here</em> rather than by a run that dies
 * at minute four — see {@link ImportRunService#start}.
 *
 * <h2>Every operation costs {@code issue:import}, and the 403s are not all the same refusal</h2>
 *
 * <p>Four of the five name a project or a run, so their 403 is about that project; the
 * source-project list names none and is gated on holding the key <em>somewhere</em>
 * ({@link ImportPreviewService#sourceProjects}). All five are D-e's one key for the whole family,
 * and all five report it at {@code PROJECT} scope, which is where it is granted (roles spec §A3).
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
    private final ImportRunService runs;

    public ImportController(ImportPreviewService previews, ImportRunService runs) {
        this.previews = previews;
        this.runs = runs;
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
     * {@code POST /imports} — confirmation, after a preview and a status mapping (D32).
     *
     * <p>The {@code Location} header names the run's canonical URL, which is the same address the
     * client then polls. It is set on the 202 and not on the replay: a replay is answering about a
     * run the caller already has, and re-advertising its location would read as a second creation.
     */
    @PostMapping("/imports")
    public ResponseEntity<Object> start(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody ImportInput input) {

        UUID userId = UUID.fromString(jwt.getSubject());
        return switch (runs.start(userId, input, idempotencyKey)) {
            case GateResult.Unreachable<ImportRunService.Outcome> ignored ->
                    ResponseEntity.notFound().build();
            case GateResult.Denied<ImportRunService.Outcome> ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problem.forbidden(
                            "You do not have permission to import into this project.",
                            "issue:import", Problem.Scope.PROJECT));
            case GateResult.Allowed<ImportRunService.Outcome> allowed -> answer(allowed.value());
        };
    }

    private static ResponseEntity<Object> answer(ImportRunService.Outcome outcome) {
        return switch (outcome) {
            case ImportRunService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(Problem.validation(invalid.errors()));
            case ImportRunService.Outcome.AlreadyImported refused ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(Problem.conflict(
                            "That project has already been imported",
                            // D-m: there is no delta import and no reconciliation. A second run
                            // would try to create a second PROJ-4812, which the company-wide
                            // uq_issue_key refuses anyway — this turns that into a sentence a
                            // person can read instead of a violation at minute four. Not
                            // Problem.stale, whose "reload and reapply" advice does nothing here.
                            refused.sourceProjectKey() + " has already been imported into this "
                                    + "project. There is no delta import: re-importing would try "
                                    + "to create every issue a second time."));
            case ImportRunService.Outcome.Replayed replayed ->
                    ResponseEntity.ok(replayed.view());
            case ImportRunService.Outcome.Accepted accepted ->
                    ResponseEntity.accepted()
                            .header("Location", "/api/v1/imports/" + accepted.view().id())
                            .body(accepted.view());
        };
    }

    /**
     * {@code GET /imports/{importRunId}} — the report, and the poll target while it is running.
     *
     * <p>No call leaves this server. The dropped-field list, the mapping as applied and the people
     * it matched are all on the run's own rows, which is what lets this answer a year later with
     * the source system decommissioned.
     */
    @GetMapping("/imports/{importRunId}")
    public ResponseEntity<Object> report(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID importRunId) {

        return switch (runs.report(UUID.fromString(jwt.getSubject()), importRunId)) {
            case GateResult.Unreachable<ImportRunView> ignored -> ResponseEntity.notFound().build();
            case GateResult.Denied<ImportRunView> ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problem.forbidden(
                            "You do not have permission to read this project's imports.",
                            "issue:import", Problem.Scope.PROJECT));
            case GateResult.Allowed<ImportRunView> allowed -> ResponseEntity.ok(allowed.value());
        };
    }

    /** {@code GET /imports?projectId=} — {@code settings-import.html}'s table, newest first. */
    @GetMapping("/imports")
    public ResponseEntity<Object> list(
            @AuthenticationPrincipal Jwt jwt, @RequestParam UUID projectId) {

        return switch (runs.list(UUID.fromString(jwt.getSubject()), projectId)) {
            case GateResult.Unreachable<List<ImportRunSummaryView>> ignored ->
                    ResponseEntity.notFound().build();
            case GateResult.Denied<List<ImportRunSummaryView>> ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problem.forbidden(
                            "You do not have permission to read this project's imports.",
                            "issue:import", Problem.Scope.PROJECT));
            case GateResult.Allowed<List<ImportRunSummaryView>> allowed ->
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
