package com.tcms.tracker;

import com.tcms.api.Problem;
import com.tcms.project.GateResult;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /issues/{key}/comments}.
 *
 * <p>Its own controller rather than two more methods on {@link IssueController}, for
 * {@link TransitionController}'s reason: this is its own permission — {@code issue:comment}, which
 * Viewer holds and {@code issue:edit} is not — and a reader looking for "who may say something"
 * should find one file rather than one method inside a file about fields.
 */
@RestController
@RequestMapping("/api/v1/issues/{key}/comments")
public class CommentController {

    private final CommentService comments;

    public CommentController(CommentService comments) {
        this.comments = comments;
    }

    @GetMapping
    public ResponseEntity<Object> list(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        return comments.list(UUID.fromString(jwt.getSubject()), key)
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Object> add(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String key,
            @RequestBody IssueCommentInput request) {

        GateResult<CommentService.Outcome> gate =
                comments.add(UUID.fromString(jwt.getSubject()), key, request);

        return switch (gate) {
            case GateResult.Unreachable<CommentService.Outcome> ignored ->
                    ResponseEntity.notFound().build();
            case GateResult.Denied<CommentService.Outcome> ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problem.forbidden(
                            "You do not have permission to comment on issues in this project."));
            case GateResult.Allowed<CommentService.Outcome> allowed -> switch (allowed.value()) {
                case CommentService.Outcome.Invalid invalid ->
                        ResponseEntity.badRequest().body(Problem.validation(invalid.errors()));
                case CommentService.Outcome.Created created ->
                        ResponseEntity.status(HttpStatus.CREATED).body(created.view());
            };
        };
    }
}
