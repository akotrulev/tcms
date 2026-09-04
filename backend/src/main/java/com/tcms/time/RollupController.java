package com.tcms.time;

import com.tcms.api.Problem;
import com.tcms.project.GateResult;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Estimate against actual — {@code /time/rollup/*}.
 *
 * <p>Both operations cost {@code issue:read} rather than a time key, because they answer a question
 * about an <em>issue</em>: the panel is on the story and the epic, not on a timesheet. There is
 * deliberately no project grain — the project figure the mockups show is a budget row, which is M8's,
 * and a work-table footer the issue list already carries per row.
 */
@RestController
@RequestMapping("/api/v1")
public class RollupController {

    private final RollupService rollups;

    public RollupController(RollupService rollups) {
        this.rollups = rollups;
    }

    @GetMapping("/time/rollup/issues/{key}")
    public ResponseEntity<Object> forIssue(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String key) {

        return answer(rollups.forIssue(UUID.fromString(jwt.getSubject()), key));
    }

    @GetMapping("/time/rollup/epics/{key}")
    public ResponseEntity<Object> forEpic(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String key) {

        return answer(rollups.forEpic(UUID.fromString(jwt.getSubject()), key));
    }

    private static <T> ResponseEntity<Object> answer(GateResult<T> gate) {
        return switch (gate) {
            case GateResult.Unreachable<T> ignored -> ResponseEntity.notFound().build();
            case GateResult.Denied<T> ignored -> ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Problem.forbidden("You do not have permission to read this issue."));
            case GateResult.Allowed<T> allowed -> ResponseEntity.ok(allowed.value());
        };
    }
}
