package com.tcms.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The saved-report surface: list mine, save one, delete one.
 *
 * <h2>Why this is not three more methods on {@link ReportController}</h2>
 *
 * <p>That class's header says what makes it one controller: its three operations are <em>one
 * conversation</em> — ask for a report, watch it, take it away — held by one client about one job.
 * These three are a different conversation with a different lifetime: a definition is written once
 * and read for releases afterwards, and nothing here produces or serves bytes. Folding them in would
 * make the binary download's response handling sit beside a JSON CRUD surface, which is exactly the
 * split {@code EvidenceController} was extracted from {@code RunController} for.
 *
 * <p>Both controllers are mapped under {@code /projects/{projectId}/reports} and that is not a
 * collision: every route below is either the collection itself or a {@code definitionId} segment,
 * while the pipeline's are literal ({@code /generate}, {@code /jobs/…}, {@code /artifacts/…}) and
 * Spring resolves a literal segment ahead of a template. The ids are UUIDs, so no definition can
 * ever be named {@code generate}.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/reports")
class ReportDefinitionController {

    private final ReportDefinitionService definitions;

    ReportDefinitionController(ReportDefinitionService definitions) {
        this.definitions = definitions;
    }

    /**
     * The request body.
     *
     * @param parameters free-form JSON like {@code generate}'s, re-serialized rather than bound —
     *     {@link ReportController}'s header carries the argument, and it is the same one: a Java
     *     type here would be a second definition of every report's inputs that has to move whenever
     *     the first does.
     */
    record SaveRequest(String type, String name, JsonNode parameters) {
    }

    @GetMapping
    ResponseEntity<Object> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {
        return respond(definitions.list(UUID.fromString(jwt.getSubject()), projectId),
                ResponseEntity::ok);
    }

    @PostMapping
    ResponseEntity<Object> save(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestBody SaveRequest request) {

        ReportDefinitionService.Outcome<ReportDefinitionView> outcome = definitions.create(
                UUID.fromString(jwt.getSubject()),
                projectId,
                new ReportDefinitionService.Input(
                        request.type(),
                        request.name(),
                        request.parameters() == null ? null : request.parameters().toString()));

        return respond(outcome, view -> ResponseEntity.status(HttpStatus.CREATED).body(view));
    }

    @DeleteMapping("/{definitionId}")
    ResponseEntity<Object> delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID definitionId) {

        return respond(
                definitions.delete(UUID.fromString(jwt.getSubject()), projectId, definitionId),
                ignored -> ResponseEntity.noContent().build());
    }

    /**
     * The four outcomes, worded once.
     *
     * <p>Only the success arm differs between the three operations — 200 with a list, 201 with the
     * row, 204 with nothing — so the refusals are written here rather than three times, where the
     * third copy is the one that says something slightly different.
     */
    private static <T> ResponseEntity<Object> respond(
            ReportDefinitionService.Outcome<T> outcome,
            Function<T, ResponseEntity<Object>> found) {

        return switch (outcome) {
            case ReportDefinitionService.Outcome.NotFound<T> ignored ->
                    ResponseEntity.notFound().build();
            case ReportDefinitionService.Outcome.Forbidden<T> ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(forbidden(
                            "You do not have permission to view reports in this project."));
            case ReportDefinitionService.Outcome.Invalid<T> invalid ->
                    ResponseEntity.badRequest().body(validationProblem(invalid.errors()));
            case ReportDefinitionService.Outcome.Done<T> done -> found.apply(done.value());
        };
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
        // The product's one sentence for this, as in ReportController. Two wordings is how one
        // product starts sounding like two.
        problem.setDetail("Check the highlighted fields.");
        problem.setProperty("errors", new HashMap<>(errors));
        return problem;
    }
}
