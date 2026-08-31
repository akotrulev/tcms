package com.tcms.tracker.board;

import com.tcms.api.Problem;
import com.tcms.project.GateResult;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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
 * Board configuration — {@code /projects/{projectId}/boards} and {@code /boards/{boardId}}.
 *
 * <p><strong>The two reads are gated on visibility alone</strong>, exactly as the workflow document
 * is: {@code board:read} is held by every one of the six project roles, so a project grant already
 * implies it and a 403 arm here would be unreachable. The three writes are {@code board:configure},
 * and there is no {@code board:move} to add (§A3 line 260).
 *
 * <p>{@code getBoardConfig}, not {@code getBoard}: {@code GET /board} — {@code work}'s composite
 * board across projects — already owns that operationId, and a duplicate is a generated client that
 * cannot name one of them.
 */
@RestController
@RequestMapping("/api/v1")
public class BoardConfigController {

    private final BoardConfigService boards;

    public BoardConfigController(BoardConfigService boards) {
        this.boards = boards;
    }

    @GetMapping("/projects/{projectId}/boards")
    public ResponseEntity<Object> list(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {

        return boards.list(UUID.fromString(jwt.getSubject()), projectId)
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/projects/{projectId}/boards")
    public ResponseEntity<Object> create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestBody BoardInput submitted) {

        return answer(
                boards.create(UUID.fromString(jwt.getSubject()), projectId, submitted),
                HttpStatus.CREATED);
    }

    @GetMapping("/boards/{boardId}")
    public ResponseEntity<Object> get(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID boardId) {

        return boards.board(UUID.fromString(jwt.getSubject()), boardId)
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Rewrites the board's columns whole.
     *
     * <p>{@code PATCH} rather than {@code PUT} because the board's own name and swimlane axis travel
     * with it, but {@code columns} is still a replacement: a column with an id is kept, one without
     * is created, one whose id is absent is deleted, and the array index is the position.
     */
    @PatchMapping("/boards/{boardId}")
    public ResponseEntity<Object> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID boardId,
            @RequestBody BoardInput submitted) {

        return answer(
                boards.update(UUID.fromString(jwt.getSubject()), boardId, submitted),
                HttpStatus.OK);
    }

    @DeleteMapping("/boards/{boardId}")
    public ResponseEntity<Object> delete(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID boardId) {

        return answer(boards.delete(UUID.fromString(jwt.getSubject()), boardId), HttpStatus.OK);
    }

    private ResponseEntity<Object> answer(
            GateResult<BoardConfigService.Outcome> gate, HttpStatus written) {

        return switch (gate) {
            case GateResult.Unreachable<BoardConfigService.Outcome> ignored ->
                    ResponseEntity.notFound().build();
            case GateResult.Denied<BoardConfigService.Outcome> ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problem.forbidden(
                            "You do not have permission to configure this project's boards."));
            case GateResult.Allowed<BoardConfigService.Outcome> allowed -> switch (allowed.value()) {
                case BoardConfigService.Outcome.Unprocessable refused ->
                        ResponseEntity.unprocessableEntity()
                                .body(Problem.unprocessable(refused.title(), refused.detail()));
                case BoardConfigService.Outcome.Written stored ->
                        ResponseEntity.status(written).body(stored.view());
                case BoardConfigService.Outcome.Deleted ignored ->
                        ResponseEntity.noContent().build();
            };
        };
    }
}
