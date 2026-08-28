package com.tcms.documentation;

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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /projects/{projectId}/cases/{caseId}/issue-links} — a case's links to tracker issues.
 *
 * <h2>It has a {@code GET}, and {@link ExternalLinkController} deliberately does not</h2>
 *
 * <p>That controller's header refuses one on the grounds that a case's links are already served on
 * the case itself, as {@code TestCase.relatedDocuments}, and a second answer from the other side of
 * a module boundary would disagree for as long as the mirror lagged. The reasoning holds and does
 * not apply here, for one reason: <strong>the client needs these rows to fetch previews</strong>.
 * Each chip is a live {@code GET …/tracker/issues/{issueId}} made with the viewer's own credential,
 * so the panel needs the ids as soon as one is linked — not after the next outbox tick refreshes a
 * mirror. The mirror still exists and still carries the columns, because the case detail render is
 * in a module that may not read this one's tables.
 *
 * <p>Gated {@code case:read} for the list and {@code case:edit} for both writes — the case's own
 * keys, for {@link ExternalLinkController}'s reason: the row lives in {@code doc_link} and the act
 * is annotating a test case.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/cases/{caseId}/issue-links")
public class IssueLinkController {

    private final IssueLinkService links;

    public IssueLinkController(IssueLinkService links) {
        this.links = links;
    }

    /**
     * The issues linked to this case — ids and hrefs, never a title, a key or a status.
     *
     * <p>Those are fetched live per viewer, so a person who cannot see the issue in the tracker sees
     * {@code Jira issue 10042 ↗} and nothing more, and a person who can sees today's key rather than
     * last year's (004 D5/D15/D17).
     */
    @GetMapping
    public ResponseEntity<Object> list(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID caseId) {

        GateResult<?> gated = links.list(caller(jwt), projectId, caseId);
        return switch (gated) {
            case GateResult.Unreachable<?> ignored -> ResponseEntity.notFound().build();
            case GateResult.Denied<?> ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problem.forbidden(
                            "You do not have permission to read this project's test cases.",
                            "case:read", Problem.Scope.PROJECT));
            case GateResult.Allowed<?> allowed -> allowed.value() == null
                    ? ResponseEntity.notFound().build()
                    : ResponseEntity.ok(allowed.value());
        };
    }

    /**
     * Links a tracker issue to this case.
     *
     * <p>No {@code Location} header on the 201, for {@link ExternalLinkController}'s reason: there
     * is no {@code GET} by link id, so a header naming one would point at a 404 (§119's standing
     * warning). The list is the readable form.
     */
    @PostMapping
    public ResponseEntity<Object> link(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID caseId,
            @RequestBody IssueLinkInput input) {

        IssueLinkService.Outcome outcome = links.link(caller(jwt), projectId, caseId, input);
        if (outcome instanceof IssueLinkService.Outcome.Linked linked) {
            return ResponseEntity.status(HttpStatus.CREATED).body(linked.view());
        }
        return render(outcome);
    }

    /** Unlinks. Removes the annotation and nothing in the tracker. */
    @DeleteMapping("/{linkId}")
    public ResponseEntity<Object> unlink(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID caseId,
            @PathVariable UUID linkId) {

        return render(links.unlink(caller(jwt), projectId, caseId, linkId));
    }

    private static UUID caller(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private static ResponseEntity<Object> render(IssueLinkService.Outcome outcome) {
        return switch (outcome) {
            case IssueLinkService.Outcome.Linked linked -> ResponseEntity.ok(linked.view());
            case IssueLinkService.Outcome.Unlinked ignored -> ResponseEntity.noContent().build();
            case IssueLinkService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            case IssueLinkService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problem.forbidden(
                            "You do not have permission to edit test cases in this project.",
                            "case:edit", Problem.Scope.PROJECT));
            case IssueLinkService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(Problem.validation(invalid.errors()));
            case IssueLinkService.Outcome.Duplicate ignored ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(Problem.conflict(
                            "Already linked",
                            "This issue is already linked to this case."));
        };
    }
}
