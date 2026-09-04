package com.tcms.time;

import com.tcms.api.Problem;
import com.tcms.project.GateResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Estimate against actual — {@code /time/rollup/*}.
 *
 * <p>All three operations cost {@code issue:read} rather than a time key, because they answer a
 * question about an <em>issue</em>: the panel is on the story and the epic, not on a timesheet.
 * There is deliberately no project grain — the project figure the mockups show is a budget row,
 * which is M8's.
 *
 * <p>~~and a work-table footer the issue list already carries per row.~~ →
 * <strong>superseded 2026-09-04 (016 T5)</strong>: it does not carry it. {@code IssueSummary} has
 * {@code estimateHours} and no logged hours, so the {@code Logged} column and its footer read
 * {@link #forIssues} instead — a bulk grain over the keys already on screen, added rather than
 * widening {@code IssueSummary}, so the tracker's list query never joins this module's projection.
 */
@RestController
@RequestMapping("/api/v1")
public class RollupController {

    /** The contract's cap, and the page size a table can ask for. */
    private static final int MAX_KEYS = 200;

    private final RollupService rollups;

    public RollupController(RollupService rollups) {
        this.rollups = rollups;
    }

    @GetMapping("/time/rollup/issues/{key}")
    public ResponseEntity<Object> forIssue(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String key) {

        return answer(rollups.forIssue(UUID.fromString(jwt.getSubject()), key));
    }

    /**
     * The same figures for a page of issues at once.
     *
     * <p>Answers 200 with a possibly shorter list rather than gating: {@link RollupService#forIssues}
     * drops the keys this caller cannot read, for the reason recorded there. The cap is refused here
     * rather than passed on, so the {@code IN} list has a bound the contract states.
     */
    @GetMapping("/time/rollup/issues")
    public ResponseEntity<Object> forIssues(
            @AuthenticationPrincipal Jwt jwt, @RequestParam("key") List<String> keys) {

        if (keys.isEmpty() || keys.size() > MAX_KEYS) {
            return ResponseEntity.badRequest().body(Problem.validation(Map.of(
                    "key", List.of("Ask for between 1 and " + MAX_KEYS + " issue keys."))));
        }
        return ResponseEntity.ok(rollups.forIssues(UUID.fromString(jwt.getSubject()), keys));
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
