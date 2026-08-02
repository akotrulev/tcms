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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A subject's reviews, one peer's verdict on an open one, who was asked to give it, and the threads
 * that hang off it — plan §6g's reviewer apparatus over HTTP.
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

        return respond(
                reviews.record(UUID.fromString(jwt.getSubject()), projectId, reviewId, request),
                HttpStatus.CREATED,
                "You do not have permission to review this project's work.");
    }

    /**
     * Writes one remark on an open review — §6g's threads.
     *
     * <p>201 with the whole review, {@link #participate}'s decision and for its reason: a comment is
     * only ever read inside the conversation it joins. No {@code Location}, because a comment has no
     * URL of its own — the resolution route below addresses one, and a {@code GET} on it would serve
     * a fragment of a panel that never renders fragments.
     */
    @PostMapping("/{reviewId}/comments")
    public ResponseEntity<Object> comment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID reviewId,
            @RequestBody ReviewCommentRequest request) {

        return respond(
                reviews.comment(UUID.fromString(jwt.getSubject()), projectId, reviewId, request),
                HttpStatus.CREATED,
                // A different sentence from the one above, because a different key was missing and
                // the two are held by different tiers (§6g): telling a Viewer they may not review
                // when what they lack is the right to comment names the wrong thing to ask for.
                "You do not have permission to comment on this project's work.");
    }

    /**
     * Marks a thread dealt with, or re-opens it.
     *
     * <p>{@code PUT} on a sub-resource rather than two verbs, for the reason
     * {@link ReviewCommentResolutionRequest} gives, and 200 rather than 201 because nothing is
     * created — the state of an existing thread is being set to what the request names, which
     * includes setting it to what it already is.
     */
    @PutMapping("/{reviewId}/comments/{commentId}/resolution")
    public ResponseEntity<Object> resolve(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID reviewId,
            @PathVariable UUID commentId,
            @RequestBody ReviewCommentResolutionRequest request) {

        return respond(
                reviews.resolveComment(
                        UUID.fromString(jwt.getSubject()), projectId, reviewId, commentId, request),
                HttpStatus.OK,
                "You do not have permission to review this project's work.");
    }

    /**
     * Asks a person or a team to review — §6g's reviewer assignment.
     *
     * <p>201 with the whole review, {@link #participate}'s decision and for its reason. No
     * {@code Location} even though this one <em>does</em> address a resource below: the URL it would
     * name serves no {@code GET}, and a {@code Location} pointing at a route that answers 405 is
     * worse than none.
     *
     * <p>{@code POST} and not {@code PUT} on a collection: a request is added to a list, not
     * declared as the whole of it. The wholesale form would make "clear everybody" expressible,
     * which is the one bulk act nobody asked for, and it would put two clients' concurrent
     * assignments in a last-write-wins race that neither could see.
     */
    @PostMapping("/{reviewId}/assignees")
    public ResponseEntity<Object> assign(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID reviewId,
            @RequestBody ReviewAssignmentRequest request) {

        return respond(
                reviews.assign(UUID.fromString(jwt.getSubject()), projectId, reviewId, request),
                HttpStatus.CREATED,
                "You do not have permission to review this project's work.");
    }

    /**
     * Withdraws a request.
     *
     * <p>200 with the whole review rather than 204, which is the shape a delete usually takes here.
     * The panel renders a review as one thing and every other write on it re-serves that whole
     * thing; answering 204 would make this the one action after which the client's copy is stale,
     * and it would refetch immediately — a second round trip to learn what this response already
     * knows.
     */
    @DeleteMapping("/{reviewId}/assignees/{assignmentId}")
    public ResponseEntity<Object> unassign(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID reviewId,
            @PathVariable UUID assignmentId) {

        return respond(
                reviews.unassign(
                        UUID.fromString(jwt.getSubject()), projectId, reviewId, assignmentId),
                HttpStatus.OK,
                "You do not have permission to review this project's work.");
    }

    /**
     * The five write paths' one mapping from outcome to response.
     *
     * <p>Written once rather than five times: the statuses and the problem envelopes are identical
     * and only the success status and the 403's sentence differ, so five copies would be five
     * places for a refusal to drift (§58's E1). The switch stays exhaustive under {@code -Werror},
     * so a new {@link ReviewService.Outcome} is a compile error here rather than a silent 500.
     */
    private static ResponseEntity<Object> respond(
            ReviewService.Outcome outcome, HttpStatus success, String forbiddenDetail) {

        return switch (outcome) {
            case ReviewService.Outcome.Recorded recorded ->
                    ResponseEntity.status(success).body(recorded.view());
            case ReviewService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(validationProblem(invalid.errors()));
            case ReviewService.Outcome.Closed closed ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(closedProblem(closed.message()));
            case ReviewService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body(forbiddenProblem(forbiddenDetail));
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

    private static ProblemDetail forbiddenProblem(String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Not allowed");
        // What they cannot do, in their words, and never the permission key — naming it publishes
        // the catalogue to exactly the caller who is not entitled to it. One sentence for all three
        // subject types, deliberately: which of case:review, suite:review or plan:review they are
        // missing is not something the person reading this can act on differently. Commenting is a
        // different tier and therefore a different sentence — see the comment route.
        problem.setDetail(detail);
        return problem;
    }
}
