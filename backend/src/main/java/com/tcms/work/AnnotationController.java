package com.tcms.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.api.PatchBody;
import com.tcms.api.Problem;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Your own note, personal due date and flag on a card —
 * {@code PUT /board/annotations/{sourceType}/{sourceId}}.
 *
 * <p><strong>New in 009 module M1, on a table that predates it by a year.</strong>
 * {@code derived_item_annotation} had no endpoint at all: the board wrote it and nothing else did.
 * D11 keeps the table and says it matters <em>more</em>, not less — a board that mixes your own
 * issues with reviews other people asked of you is exactly the case a private note on a card you do
 * not own is for — so the act finally gets a door.
 *
 * <p>Keyed on the CALLER and on nobody else. Story B5's second criterion: <em>"when a teammate looks
 * at the same issue, they do not see my note"</em> — two people reading one card get different
 * values here, and that is the feature rather than a caching bug.
 *
 * <p><strong>No permission beyond seeing the card.</strong> Annotating something is not editing it,
 * which is the whole reason this is a separate table from the row.
 */
@RestController
@RequestMapping("/api/v1/board/annotations/{sourceType}/{sourceId}")
public class AnnotationController {

    private final BoardService board;
    private final ObjectMapper mapper;

    public AnnotationController(BoardService board, ObjectMapper mapper) {
        this.board = board;
        this.mapper = mapper;
    }

    /**
     * Bound as a node and then as the record, so the service can ask what was <em>sent</em>.
     *
     * <p>{@code IssueController}'s reason: Jackson gives an absent field and an explicit
     * {@code null} the same Java value, so a service that cannot ask reads both as <em>clear it</em>
     * — and every flag edit would empty the note. A body that cannot be read as the record is caught
     * here rather than by a global handler, because the answer has to be this API's {@code Problem}.
     */
    @PutMapping
    public ResponseEntity<Object> put(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String sourceType,
            @PathVariable String sourceId,
            @RequestBody JsonNode body) {

        UUID id;
        try {
            id = UUID.fromString(sourceId);
        } catch (IllegalArgumentException malformed) {
            // 404 rather than 400, for BoardService's retired `move` reason: an id that is not a
            // UUID names nothing, and it is the same answer an unknown UUID gets. Distinguishing
            // them tells a caller which ids are well-formed.
            return ResponseEntity.notFound().build();
        }

        PatchBody patch = new PatchBody(body);
        CardAnnotationInput request;
        try {
            request = patch.as(mapper, CardAnnotationInput.class);
        } catch (PatchBody.MalformedBody unreadable) {
            return ResponseEntity.badRequest().body(Problem.validation(Map.of(
                    "body", List.of("The request body could not be read as an annotation."))));
        }

        return ResponseEntity.ok(board.annotate(
                UUID.fromString(jwt.getSubject()), sourceType, id, request, patch));
    }
}
