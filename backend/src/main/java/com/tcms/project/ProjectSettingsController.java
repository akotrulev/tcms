package com.tcms.project;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * One project's own policy over HTTP — plan §6g's four settings.
 *
 * <p>Its own class rather than two more methods on {@link ProjectController}, for the reason §71
 * moved {@code GET /teams} off {@code AdminController} and §76 gave reviews their own controller:
 * one path family, one class.
 *
 * <p><strong>{@code PUT}, not {@code PATCH}.</strong> The form shows six controls and submits six;
 * nothing here is a list and nothing here is clearable, so §74's absent/null/empty rules have no
 * question to answer — "leave this one alone" is not something a user of this screen can mean.
 *
 * <p>The caller comes from the token and the company from the context; neither is ever a parameter,
 * for the reasons {@link ProjectController} sets out.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/settings")
public class ProjectSettingsController {

    private final ProjectSettingsService settings;

    public ProjectSettingsController(ProjectSettingsService settings) {
        this.settings = settings;
    }

    /**
     * The project's settings.
     *
     * <p><strong>Readable by anyone who can see the project</strong>, not only by a holder of
     * {@code project:settings} — {@link ProjectSettingsService}'s header gives the reason at length,
     * and it is §6g's own requirement: "a project that never turns it on should see no extra UI at
     * all" is a decision every reader's client has to make, and none of those readers is an admin.
     */
    @GetMapping
    public ResponseEntity<Object> get(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {

        return settings.find(UUID.fromString(jwt.getSubject()), projectId)
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Replaces the project's settings. Needs {@code project:settings} on this project. */
    @PutMapping
    public ResponseEntity<Object> put(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestBody ProjectSettingsRequest request) {

        return switch (settings.update(UUID.fromString(jwt.getSubject()), projectId, request)) {
            case ProjectSettingsService.Outcome.Updated updated ->
                    ResponseEntity.ok(updated.view());
            case ProjectSettingsService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(validationProblem(invalid.errors()));
            case ProjectSettingsService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(forbiddenProblem());
            case ProjectSettingsService.Outcome.NotFound ignored ->
                    ResponseEntity.notFound().build();
        };
    }

    // The RFC-7807 envelope, built with Spring's own ProblemDetail rather than a record — the
    // arrangement ProjectController's header explains.

    private static ProblemDetail validationProblem(Map<String, List<String>> errors) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Validation failed");
        problem.setDetail("Check the highlighted fields.");
        problem.setProperty("errors", errors);
        return problem;
    }

    private static ProblemDetail forbiddenProblem() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Not allowed");
        // What they cannot do, in their words, and never the permission key — naming it publishes
        // the catalogue to exactly the caller who is not entitled to it.
        problem.setDetail("You do not have permission to change this project's settings.");
        return problem;
    }
}
