package com.tcms.tracker.board;

import com.tcms.api.Problem;
import com.tcms.project.GateResult;
import com.tcms.tracker.IssueLevel;
import com.tcms.tracker.Priority;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The cards on a board, and the drag — {@code /boards/{boardId}/cards}.
 *
 * <p>The read is gated on visibility alone, exactly as the board's configuration read is:
 * {@code board:read} is held by every one of the six project roles. The move costs
 * {@code issue:edit} and there is <strong>no {@code board:move}</strong> (§A3 line 260).
 */
@RestController
@RequestMapping("/api/v1/boards/{boardId}/cards")
public class BoardCardController {

    private final BoardCardService cards;
    private final BoardMoveService moves;

    public BoardCardController(BoardCardService cards, BoardMoveService moves) {
        this.cards = cards;
        this.moves = moves;
    }

    @GetMapping
    public ResponseEntity<Object> cards(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID boardId,
            @RequestParam(name = "labelId", required = false) List<UUID> labelIds,
            @RequestParam(name = "assigneeId", required = false) List<UUID> assigneeIds,
            @RequestParam(name = "epicKey", required = false) String epicKey,
            @RequestParam(name = "level", required = false) String level,
            @RequestParam(name = "priority", required = false) List<String> priorities,
            @RequestParam(name = "unparented", required = false, defaultValue = "false")
            boolean unparented) {

        BoardFilters.Parsed parsed = BoardFilters.parse(
                labelIds, assigneeIds, epicKey, level, priorities, unparented);
        if (parsed.errors() != null) {
            return ResponseEntity.badRequest().body(Problem.validation(parsed.errors()));
        }

        return cards.contents(UUID.fromString(jwt.getSubject()), boardId, parsed.filter())
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Drops a card into a column.
     *
     * <p>A breach in the body is a fact about the column, not a refusal — story B3: "the drop is
     * accepted and the column header shows the breach".
     */
    @PostMapping("/{key}/move")
    public ResponseEntity<Object> move(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID boardId,
            @PathVariable String key,
            @RequestBody CardMoveInput request) {

        GateResult<BoardMoveService.Outcome> gate = moves.move(
                UUID.fromString(jwt.getSubject()), boardId, key,
                request == null ? null : request.toColumnId());

        return switch (gate) {
            case GateResult.Unreachable<BoardMoveService.Outcome> ignored ->
                    ResponseEntity.notFound().build();
            case GateResult.Denied<BoardMoveService.Outcome> ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problem.forbidden(
                            "You do not have permission to move issues in this project."));
            case GateResult.Allowed<BoardMoveService.Outcome> allowed -> switch (allowed.value()) {
                case BoardMoveService.Outcome.NotAllowed refused -> ResponseEntity
                        .status(HttpStatus.CONFLICT)
                        .body(Problem.conflict("That move isn't allowed",
                                "This project's workflow does not allow an issue to go from "
                                        + refused.from() + " to " + refused.to() + ". Someone who "
                                        + "can edit the workflow can add that step."));
                case BoardMoveService.Outcome.DerivedStatus ignored -> ResponseEntity
                        .status(HttpStatus.CONFLICT)
                        .body(Problem.conflict("An Epic's status is not set by hand",
                                "It follows its stories: not started until one of them starts, "
                                        + "and done when the last one is done."));
                case BoardMoveService.Outcome.Moved moved -> ResponseEntity.ok(moved.result());
            };
        };
    }

    /**
     * The board's query string and the backlog's, parsed once.
     *
     * <p>The two endpoints take the <em>same</em> filter (§12.2 — one filter, five views), so a
     * second parser is a second reading of what {@code level=story} means.
     */
    static final class BoardFilters {

        private BoardFilters() {
        }

        record Parsed(BoardCardService.Filter filter, Map<String, List<String>> errors) {
        }

        static Parsed parse(
                List<UUID> labelIds,
                List<UUID> assigneeIds,
                String epicKey,
                String level,
                List<String> priorities,
                boolean unparented) {

            IssueLevel parsedLevel;
            try {
                parsedLevel = level == null
                        ? null : IssueLevel.valueOf(level.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                // Named rather than withheld: a level is public vocabulary already on every card in
                // the response, and a closed set the caller has to guess at is worse than one it
                // can read.
                return new Parsed(null, Map.of("level", List.of(
                        "'" + level + "' is not a level. Valid values: EPIC, STORY, TASK.")));
            }

            List<Priority> parsedPriorities;
            try {
                parsedPriorities = priorities == null ? List.of() : priorities.stream()
                        .map(value -> Priority.valueOf(value.trim().toUpperCase(Locale.ROOT)))
                        .toList();
            } catch (IllegalArgumentException unknown) {
                return new Parsed(null, Map.of("priority", List.of(
                        "Not a priority. Valid values: CRITICAL, HIGH, MEDIUM, LOW.")));
            }

            return new Parsed(new BoardCardService.Filter(
                    labelIds == null ? List.of() : labelIds,
                    assigneeIds == null ? List.of() : assigneeIds,
                    epicKey,
                    parsedLevel,
                    parsedPriorities,
                    unparented), null);
        }
    }
}
