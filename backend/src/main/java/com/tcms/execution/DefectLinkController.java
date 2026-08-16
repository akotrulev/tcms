package com.tcms.execution;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A result's links out to defects — link and unlink.
 *
 * <p>The path names the execution because the row does: {@code defect_link.execution_id} is what
 * every reader of this table selects on, and every gate in this product resolves the project from
 * the path rather than from the body. {@code documentation.ExternalLinkController} is the precedent
 * and states the argument against the alternative — a collection taking the target in the body makes
 * the target an argument rather than a location, and turns an unreachable execution and an
 * unreachable project into two different status codes.
 *
 * <p><strong>No {@code GET}</strong>, and it must not grow one. A result's defect links are already
 * served on the result itself, as {@code TestExecution.defects}, and on the two history screens —
 * all three off {@link TestExecutionRepository#findDefectLinks}. A read here would be a second
 * answer to a question already answered, and the two would disagree the first time one changed.
 *
 * <p>The error envelope is Spring's {@link ProblemDetail}, following {@link RunController} and
 * {@link EvidenceController} — see the former for why this module does not use
 * {@code repository.ApiProblem}.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/runs/{runId}/executions/{executionId}/defects")
public class DefectLinkController {

    private final DefectLinkService links;

    public DefectLinkController(DefectLinkService links) {
        this.links = links;
    }

    /**
     * Links this result to a defect.
     *
     * <p>Two success codes, as on the external link and for its reason: 201 when a link was written,
     * 200 when this exact defect was already linked here.
     *
     * <p><strong>No {@code Location} header on the 201.</strong> There is no {@code GET} by design
     * (see the class header), so a header naming one would point at a 404 — which is §119's standing
     * warning, and the honest answer is the body alone.
     */
    @PostMapping
    public ResponseEntity<Object> link(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID runId,
            @PathVariable UUID executionId,
            @RequestBody DefectLinkInput input) {

        DefectLinkService.Outcome outcome = links.link(
                UUID.fromString(jwt.getSubject()), projectId, runId, executionId, input);

        return switch (outcome) {
            case DefectLinkService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            case DefectLinkService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                            forbidden("You do not have permission to link defects in this "
                                    + "project."));
            case DefectLinkService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(validationProblem(invalid.errors()));
            case DefectLinkService.Outcome.RunClosed ignored ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(runClosed());
            case DefectLinkService.Outcome.Linked linked ->
                    ResponseEntity.status(linked.created() ? HttpStatus.CREATED : HttpStatus.OK)
                            .body(linked.view());
            // Unreachable: linking removes nothing. The arm stays written because the switch is
            // exhaustive over the sealed type, which is what makes a new outcome a compile error at
            // every consumer rather than a silently unhandled case.
            case DefectLinkService.Outcome.Unlinked ignored ->
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
    }

    /**
     * Removes a link.
     *
     * <p>Charged {@code defect:create}, the key that made it — there is no {@code defect:delete} and
     * {@link DefectLinkService}'s header carries the argument for reusing rather than seeding one.
     */
    @DeleteMapping("/{linkId}")
    public ResponseEntity<Object> unlink(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID runId,
            @PathVariable UUID executionId,
            @PathVariable UUID linkId) {

        DefectLinkService.Outcome outcome = links.unlink(
                UUID.fromString(jwt.getSubject()), projectId, runId, executionId, linkId);

        return switch (outcome) {
            case DefectLinkService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            case DefectLinkService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                            forbidden("You do not have permission to unlink defects in this "
                                    + "project."));
            case DefectLinkService.Outcome.RunClosed ignored ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(runClosed());
            case DefectLinkService.Outcome.Unlinked ignored -> ResponseEntity.noContent().build();
            // Unreachable: an unlink has no body to validate and creates nothing. Written out for
            // the exhaustiveness the sealed type buys.
            case DefectLinkService.Outcome.Invalid ignored ->
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            case DefectLinkService.Outcome.Linked ignored ->
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
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
        problem.setTitle("That link cannot be saved");
        problem.setDetail("Check the highlighted fields.");
        problem.setProperty("errors", new HashMap<>(errors));
        return problem;
    }
}
