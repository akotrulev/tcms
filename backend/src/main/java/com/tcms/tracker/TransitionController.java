package com.tcms.tracker;

import com.tcms.api.Problem;
import com.tcms.project.GateResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /issues/{key}/transitions} — the move, and the only path that writes a status.
 *
 * <p>Its own controller rather than a fifth method on {@link IssueController}, because it is its own
 * <em>rule</em>: the workflow's legal-move set is checked here and nowhere else, and a reader looking
 * for "where is a move refused" should find one file rather than one method inside a file about
 * fields.
 */
@RestController
@RequestMapping("/api/v1/issues/{key}/transitions")
public class TransitionController {

    private final TransitionService transitions;

    public TransitionController(TransitionService transitions) {
        this.transitions = transitions;
    }

    @PostMapping
    public ResponseEntity<Object> move(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String key,
            @RequestBody IssueTransitionInput request) {

        if (request == null || request.statusId() == null) {
            return ResponseEntity.badRequest().body(Problem.validation(Map.of(
                    "statusId", List.of("Name the status to move this issue to."))));
        }

        GateResult<TransitionService.Outcome> gate =
                transitions.move(UUID.fromString(jwt.getSubject()), key, request.statusId());

        return switch (gate) {
            case GateResult.Unreachable<TransitionService.Outcome> ignored ->
                    ResponseEntity.notFound().build();
            case GateResult.Denied<TransitionService.Outcome> ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problem.forbidden(
                            "You do not have permission to move issues in this project."));
            case GateResult.Allowed<TransitionService.Outcome> allowed -> switch (allowed.value()) {
                case TransitionService.Outcome.UnknownStatus ignored ->
                        ResponseEntity.badRequest().body(Problem.validation(Map.of(
                                "statusId",
                                List.of("This project's workflow has no such status."))));
                case TransitionService.Outcome.NotAllowed refused -> ResponseEntity
                        .status(HttpStatus.CONFLICT)
                        .body(Problem.conflict("That move isn't allowed",
                                "This project's workflow does not allow an issue to go from "
                                        + refused.from() + " to " + refused.to() + ". Someone who "
                                        + "can edit the workflow can add that step."));
                // D31, and the sentence says where the status DOES come from — an epic that read
                // "In Progress" while its stories were all done is the disagreement this refusal
                // exists to make unrepresentable.
                case TransitionService.Outcome.DerivedStatus ignored -> ResponseEntity
                        .status(HttpStatus.CONFLICT)
                        .body(Problem.conflict("An Epic's status is not set by hand",
                                "It follows its stories: not started until one of them starts, "
                                        + "and done when the last one is done."));
                // D33. A 409 like the other two refusals and not a 403: the caller HOLDS
                // issue:edit — the move is refused by the project's policy about who may close
                // their own work, which is a state conflict rather than a missing permission.
                case TransitionService.Outcome.CloseRefused refused -> ResponseEntity
                        .status(HttpStatus.CONFLICT)
                        .body(Problem.conflict("This one needs a second person",
                                refused.detail()));
                case TransitionService.Outcome.Moved moved -> ResponseEntity.ok(moved.view());
            };
        };
    }
}
