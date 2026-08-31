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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Labels — {@code /projects/{projectId}/labels}, {@code /labels/{labelId}} and
 * {@code /issues/{key}/labels}.
 *
 * <p>Three paths and one controller because they are one concept with one service behind them. The
 * two permissions differ and the difference is D-e's: <strong>configuring</strong> a project's
 * labels is {@code board:configure} and <strong>putting one on an issue</strong> is
 * {@code issue:edit}, so the two forbidden sentences say different things.
 */
@RestController
@RequestMapping("/api/v1")
public class LabelController {

    private final LabelService labels;

    public LabelController(LabelService labels) {
        this.labels = labels;
    }

    @GetMapping("/projects/{projectId}/labels")
    public ResponseEntity<Object> list(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {

        return labels.list(UUID.fromString(jwt.getSubject()), projectId)
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/projects/{projectId}/labels")
    public ResponseEntity<Object> create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestBody LabelInput submitted) {

        return answer(
                labels.create(UUID.fromString(jwt.getSubject()), projectId, submitted),
                HttpStatus.CREATED,
                "You do not have permission to configure this project's labels.");
    }

    @PatchMapping("/labels/{labelId}")
    public ResponseEntity<Object> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID labelId,
            @RequestBody LabelInput submitted) {

        return answer(
                labels.update(UUID.fromString(jwt.getSubject()), labelId, submitted),
                HttpStatus.OK,
                "You do not have permission to configure this project's labels.");
    }

    @DeleteMapping("/labels/{labelId}")
    public ResponseEntity<Object> delete(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID labelId) {

        return answer(
                labels.delete(UUID.fromString(jwt.getSubject()), labelId),
                HttpStatus.NO_CONTENT,
                "You do not have permission to configure this project's labels.");
    }

    @PutMapping("/issues/{key}/labels")
    public ResponseEntity<Object> putIssueLabels(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String key,
            @RequestBody IssueLabelsInput submitted) {

        return answer(
                labels.putIssueLabels(UUID.fromString(jwt.getSubject()), key, submitted),
                HttpStatus.OK,
                "You do not have permission to edit issues in this project.");
    }

    /**
     * One mapping for all four writes, because the outcomes are one sealed set.
     *
     * <p>The exhaustive switch is what makes a new outcome a compile error here rather than a silent
     * fall-through to a 200 with the wrong body.
     */
    private ResponseEntity<Object> answer(
            GateResult<LabelService.Outcome> gate, HttpStatus written, String forbidden) {

        return switch (gate) {
            case GateResult.Unreachable<LabelService.Outcome> ignored ->
                    ResponseEntity.notFound().build();
            case GateResult.Denied<LabelService.Outcome> ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problem.forbidden(forbidden));
            case GateResult.Allowed<LabelService.Outcome> allowed -> switch (allowed.value()) {
                case LabelService.Outcome.Invalid invalid ->
                        ResponseEntity.badRequest().body(Problem.validation(invalid.errors()));
                case LabelService.Outcome.Duplicate taken -> ResponseEntity
                        .status(HttpStatus.CONFLICT)
                        .body(Problem.conflict("That label already exists",
                                "This project already has a label called " + taken.name()
                                        + ". Labels are matched without regard to case, so two "
                                        + "that differ only in capitals would be one chip twice."));
                case LabelService.Outcome.Written stored ->
                        ResponseEntity.status(written).body(stored.view());
                case LabelService.Outcome.Deleted ignored -> ResponseEntity.noContent().build();
                case LabelService.Outcome.Replaced replaced ->
                        ResponseEntity.ok(replaced.views());
            };
        };
    }
}
