package com.tcms.project;

import com.tcms.api.Problem;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /projects/{projectId}/repositories} — the repositories a project spans (D16).
 *
 * <p>Its own class rather than more methods on {@link ProjectSettingsController}: one path family,
 * one class, which is the arrangement §71 and §76 settled on.
 *
 * <p>Two forbidden messages rather than one, because the two gates are different keys — a reader
 * refused the list is missing {@code project:read}, and a writer refused an edit is missing
 * {@code project:settings}. One shared sentence would name the wrong key on one of the two paths.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/repositories")
public class ProjectRepoController {

    private final ProjectRepoService repositories;

    public ProjectRepoController(ProjectRepoService repositories) {
        this.repositories = repositories;
    }

    @GetMapping
    public ResponseEntity<Object> list(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {

        return switch (repositories.list(caller(jwt), projectId)) {
            case GateResult.Allowed<List<ProjectRepoView>> allowed ->
                    ResponseEntity.ok(allowed.value());
            case GateResult.Denied<List<ProjectRepoView>> ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problem.forbiddenDetail(
                            "You do not have permission to read this project.",
                            "project:read", Problem.Scope.PROJECT));
            case GateResult.Unreachable<List<ProjectRepoView>> ignored ->
                    ResponseEntity.notFound().build();
        };
    }

    @PostMapping
    public ResponseEntity<Object> add(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestBody ProjectRepoInput input) {

        GateResult<ProjectRepoService.Outcome> gate =
                repositories.add(caller(jwt), projectId, input);

        if (gate instanceof GateResult.Allowed<ProjectRepoService.Outcome> allowed
                && allowed.value() instanceof ProjectRepoService.Outcome.Written written) {

            return ResponseEntity
                    .created(URI.create("/api/v1/projects/" + projectId + "/repositories/"
                            + written.view().id()))
                    .body(written.view());
        }
        return respond(gate);
    }

    @PatchMapping("/{repositoryId}")
    public ResponseEntity<Object> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @RequestBody ProjectRepoInput input) {

        return respond(repositories.update(caller(jwt), projectId, repositoryId, input));
    }

    @DeleteMapping("/{repositoryId}")
    public ResponseEntity<Object> delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId) {

        return respond(repositories.delete(caller(jwt), projectId, repositoryId));
    }

    /**
     * One mapping for the three writes, exhaustive over both sealed types — so a new gate result or
     * a new outcome is a compile error here rather than a 200 with the wrong body.
     */
    private static ResponseEntity<Object> respond(GateResult<ProjectRepoService.Outcome> gate) {
        return switch (gate) {
            case GateResult.Unreachable<ProjectRepoService.Outcome> ignored ->
                    ResponseEntity.notFound().build();
            case GateResult.Denied<ProjectRepoService.Outcome> ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problem.forbiddenDetail(
                            "You do not have permission to change this project's settings.",
                            "project:settings", Problem.Scope.PROJECT));
            case GateResult.Allowed<ProjectRepoService.Outcome> allowed -> switch (allowed.value()) {
                case ProjectRepoService.Outcome.NotFound ignored ->
                        ResponseEntity.notFound().build();
                case ProjectRepoService.Outcome.Invalid invalid ->
                        ResponseEntity.badRequest().body(validationProblem(invalid.errors()));
                case ProjectRepoService.Outcome.Written written ->
                        ResponseEntity.ok(written.view());
                case ProjectRepoService.Outcome.Deleted ignored ->
                        ResponseEntity.noContent().build();
            };
        };
    }

    private static UUID caller(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private static ProblemDetail validationProblem(Map<String, List<String>> errors) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Validation failed");
        problem.setDetail("Check the highlighted fields.");
        problem.setProperty("errors", errors);
        return problem;
    }
}
