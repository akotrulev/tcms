package com.tcms.tracker.board;

import com.tcms.api.Problem;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /projects/{projectId}/backlog} — every STORY- or TASK-level issue that is not done, in
 * rank order.
 *
 * <p>An Epic is not a backlog row: D31 derives its status from its stories, so it has nothing to be
 * ranked <em>against</em>, and a list carrying both an epic and the stories under it double-counts
 * the same work in the one view whose whole job is "what is next".
 *
 * <p>Its own controller rather than a method on {@link BoardCardController}, because the path is the
 * project's rather than a board's — a backlog belongs to the project and is the same list whichever
 * board you are looking at.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/backlog")
public class BacklogController {

    private final BoardCardService cards;

    public BacklogController(BoardCardService cards) {
        this.cards = cards;
    }

    @GetMapping
    public ResponseEntity<Object> backlog(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestParam(name = "labelId", required = false) List<UUID> labelIds,
            @RequestParam(name = "assigneeId", required = false) List<UUID> assigneeIds,
            @RequestParam(name = "epicKey", required = false) String epicKey,
            @RequestParam(name = "level", required = false) String level,
            @RequestParam(name = "priority", required = false) List<String> priorities,
            @RequestParam(name = "unparented", required = false, defaultValue = "false")
            boolean unparented) {

        BoardCardController.BoardFilters.Parsed parsed = BoardCardController.BoardFilters.parse(
                labelIds, assigneeIds, epicKey, level, priorities, unparented);
        if (parsed.errors() != null) {
            return ResponseEntity.badRequest().body(Problem.validation(parsed.errors()));
        }

        return cards.backlog(UUID.fromString(jwt.getSubject()), projectId, parsed.filter())
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
