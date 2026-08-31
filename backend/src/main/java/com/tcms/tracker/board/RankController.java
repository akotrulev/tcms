package com.tcms.tracker.board;

import com.tcms.api.Problem;
import com.tcms.project.GateResult;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code PUT /issues/{key}/rank} — where an issue sits in its project's order.
 *
 * <p>{@code PUT} rather than {@code PATCH} because it is idempotent in the sense that matters:
 * sending the same neighbour twice lands the issue in the same place, whatever its rank was before.
 * The order persists for everyone (story B4) — this is not a per-viewer sort.
 */
@RestController
@RequestMapping("/api/v1/issues/{key}/rank")
public class RankController {

    private final RankService ranks;

    public RankController(RankService ranks) {
        this.ranks = ranks;
    }

    @PutMapping
    public ResponseEntity<Object> rank(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String key,
            @RequestBody RankInput request) {

        GateResult<RankService.Outcome> gate =
                ranks.place(UUID.fromString(jwt.getSubject()), key, request);

        return switch (gate) {
            case GateResult.Unreachable<RankService.Outcome> ignored ->
                    ResponseEntity.notFound().build();
            case GateResult.Denied<RankService.Outcome> ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problem.forbidden(
                            "You do not have permission to reorder this project's backlog."));
            case GateResult.Allowed<RankService.Outcome> allowed -> switch (allowed.value()) {
                case RankService.Outcome.Invalid invalid ->
                        ResponseEntity.badRequest().body(Problem.validation(invalid.errors()));
                case RankService.Outcome.Placed placed -> ResponseEntity.ok(placed.result());
            };
        };
    }
}
