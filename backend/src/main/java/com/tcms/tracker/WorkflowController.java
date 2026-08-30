package com.tcms.tracker;

import com.tcms.api.Problem;
import com.tcms.project.GateResult;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /projects/{projectId}/workflow} — story B1's settings screen.
 *
 * <p>Project-scoped paths here, unlike {@code /issues}: a workflow belongs to exactly one project and
 * has no key of its own, so the project is the only way to name it.
 *
 * <p><strong>The GET is not gated on {@code issue:workflow}.</strong> Every screen that renders a
 * status picker needs this document, including the read-only ones, so gating the read on the key
 * that governs the write would make a Viewer's issue page unable to show what a status is called.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/workflow")
public class WorkflowController {

    private final WorkflowService workflow;

    public WorkflowController(WorkflowService workflow) {
        this.workflow = workflow;
    }

    @GetMapping
    public ResponseEntity<Object> get(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {

        return workflow.workflow(UUID.fromString(jwt.getSubject()), projectId)
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Replaces the whole workflow.
     *
     * <p>Whole-document rather than per-status, because reordering is a statement about the set: a
     * sequence of single-status writes has an intermediate state in which two statuses claim one
     * position, which is exactly what the screen's ↑ / ↓ buttons generate.
     */
    @PutMapping
    public ResponseEntity<Object> replace(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestBody WorkflowView submitted) {

        return answer(workflow.replace(UUID.fromString(jwt.getSubject()), projectId, submitted));
    }

    /**
     * Removes one status, moving the issues that hold it to the one the body names.
     *
     * <p>The destination is in the body rather than in a query parameter: it is a destructive
     * decision, and a query parameter makes one look like a filter.
     */
    @DeleteMapping("/statuses/{statusId}")
    public ResponseEntity<Object> removeStatus(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID statusId,
            @RequestBody StatusRemovalInput request) {

        return answer(workflow.removeStatus(
                UUID.fromString(jwt.getSubject()), projectId, statusId, request));
    }

    private ResponseEntity<Object> answer(GateResult<WorkflowService.Outcome> gate) {
        return switch (gate) {
            case GateResult.Unreachable<WorkflowService.Outcome> ignored ->
                    ResponseEntity.notFound().build();
            case GateResult.Denied<WorkflowService.Outcome> ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problem.forbidden(
                            "You do not have permission to change this project's workflow."));
            case GateResult.Allowed<WorkflowService.Outcome> allowed -> switch (allowed.value()) {
                case WorkflowService.Outcome.Invalid invalid ->
                        ResponseEntity.badRequest().body(Problem.validation(invalid.errors()));
                case WorkflowService.Outcome.StatusInUse held -> ResponseEntity
                        .status(HttpStatus.CONFLICT)
                        .body(Problem.conflict("That status still holds work",
                                held.count() + " "
                                        + (held.count() == 1 ? "issue is" : "issues are")
                                        + " in " + held.name() + ". Remove the status on its own "
                                        + "and say which status those issues move to."));
                // Every percentage in the product is DONE over the counted total (D8). A project
                // with no DONE-kind status has no expressible completion at all, and the screens
                // would render a percentage of nothing rather than an error.
                case WorkflowService.Outcome.LastDoneStatus ignored -> ResponseEntity
                        .status(HttpStatus.CONFLICT)
                        .body(Problem.conflict("Keep one status that means done",
                                "Every completion figure in the product counts the issues in a "
                                        + "done status, so a project needs at least one."));
                case WorkflowService.Outcome.Replaced replaced ->
                        ResponseEntity.ok(replaced.view());
                case WorkflowService.Outcome.Removed ignored -> ResponseEntity.noContent().build();
            };
        };
    }
}
