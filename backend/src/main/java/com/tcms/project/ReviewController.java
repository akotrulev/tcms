package com.tcms.project;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A subject's reviews, and one peer's verdict on an open one — plan §6g's reviewer apparatus over
 * HTTP.
 *
 * <p>Its own class rather than three more methods on {@link ProjectController}, for the reason §71
 * moved {@code GET /teams} off {@code AdminController}: one path family, one class. The subject is
 * a {@code (type, id)} pair in the query string rather than a nested path, because a review is not
 * owned by the case-, suite- or plan-shaped part of the URL space — the same record serves all
 * three, and three nested paths would be three routes into one service with nothing different to
 * say.
 *
 * <p>The caller comes from the token and the company from the context; neither is ever a parameter,
 * for the reasons {@link ProjectController} sets out.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/reviews")
public class ReviewController {

    private final ReviewService reviews;

    public ReviewController(ReviewService reviews) {
        this.reviews = reviews;
    }

    /**
     * Every review of one subject, newest first.
     *
     * <p>Both parameters are required and both are bound — §50's rule, from the side that matters
     * here: a declared filter no handler reads answers 200 to the wrong question, which is strictly
     * worse than an absent one. There is deliberately no "all reviews in this project" form; the
     * pending-reviews queue §6l wants is a different question (mine, assigned, overdue) and it will
     * need the subject expanded, which this endpoint refuses to do.
     */
    @GetMapping
    public ResponseEntity<Object> forSubject(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestParam String subjectType,
            @RequestParam UUID subjectId) {

        ReviewSubjectType parsed = parseSubjectType(subjectType);
        if (parsed == null) {
            return ResponseEntity.badRequest().body(validationProblem(Map.of(
                    "subjectType", List.of("Choose a subject type: CASE, SUITE or PLAN."))));
        }

        return reviews.findForSubject(UUID.fromString(jwt.getSubject()), projectId, parsed, subjectId)
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Records the caller's peer verdict on an open review.
     *
     * <p>201 with the whole review rather than 200 with the row: a verdict is only ever read in the
     * context of the trail it joins, and re-rendering it here means the panel cannot show a state
     * the server does not have. {@code Location} is deliberately absent — a participation has no URL
     * of its own, and inventing one would promise a {@code GET} nothing serves.
     */
    @PostMapping("/{reviewId}/participations")
    public ResponseEntity<Object> participate(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID reviewId,
            @RequestBody ReviewParticipationRequest request) {

        return switch (reviews.record(
                UUID.fromString(jwt.getSubject()), projectId, reviewId, request)) {

            case ReviewService.Outcome.Recorded recorded ->
                    ResponseEntity.status(HttpStatus.CREATED).body(recorded.view());
            case ReviewService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(validationProblem(invalid.errors()));
            case ReviewService.Outcome.Closed closed ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(closedProblem(closed.message()));
            case ReviewService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(forbiddenProblem());
            case ReviewService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
        };
    }

    /** Null for anything that is not a subject type — reported as a field error, never thrown. */
    private static ReviewSubjectType parseSubjectType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ReviewSubjectType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException notASubjectType) {
            return null;
        }
    }

    // The RFC-7807 envelope, built with Spring's own ProblemDetail rather than a record — the
    // arrangement ProjectController's header explains, and for the same reason: repository's
    // ApiProblem and InvitationController's ProblemBody are already two copies of one shape whose
    // right home is a `shared` module that does not exist, and this module cannot import either.

    private static ProblemDetail validationProblem(Map<String, List<String>> errors) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Validation failed");
        problem.setDetail("Check the highlighted fields.");
        problem.setProperty("errors", errors);
        return problem;
    }

    private static ProblemDetail closedProblem(String message) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Review closed");
        problem.setDetail(message);
        return problem;
    }

    private static ProblemDetail forbiddenProblem() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Not allowed");
        // What they cannot do, in their words, and never the permission key — naming it publishes
        // the catalogue to exactly the caller who is not entitled to it. One sentence for all three
        // subject types, deliberately: which of case:review, suite:review or plan:review they are
        // missing is not something the person reading this can act on differently.
        problem.setDetail("You do not have permission to review this project's work.");
        return problem;
    }
}
