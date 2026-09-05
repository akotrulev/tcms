package com.tcms.crm;

import com.tcms.api.ClientRef;
import com.tcms.api.Problem;
import com.tcms.project.GateResult;
import com.tcms.project.ProjectView;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The project side of a client — the picker, and the one field that attaches a project to one.
 *
 * <h2>A {@code crm} controller on a project-scoped path, and that is not a boundary violation</h2>
 *
 * <p>The path segment does not decide which module owns the handler. This one is here because the
 * response is a list of clients, which is {@code crm}'s to serve, and {@code crm} may import
 * {@code project} to gate it — that direction is the allowed one. The reverse, a {@code project}
 * controller reaching into {@code crm}, is what {@link com.tcms.project.ClientDirectory} exists to
 * avoid.
 *
 * <h2>Why it is not a field on the settings document</h2>
 *
 * <p>{@code GET /projects/{projectId}/settings} is <strong>readable by anyone who can see the
 * project</strong>, deliberately and as its own description says, so a Viewer can find out whether
 * to render the review panel. An array of every client the company has is not that kind of fact: it
 * is a claim about the company told to somebody who may not ask (018 D-e), which is
 * {@code ApiTokenService}'s argument for refusing rather than narrowing, one noun over.
 */
@RestController
@RequestMapping("/api/v1")
public class ProjectClientController {

    private final ClientService clients;

    ProjectClientController(ClientService clients) {
        this.clients = clients;
    }

    @GetMapping("/projects/{projectId}/settings/client-options")
    public ResponseEntity<Object> options(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {

        GateResult<List<ClientRef>> gate =
                clients.pickableFor(UUID.fromString(jwt.getSubject()), projectId);

        return switch (gate) {
            case GateResult.Unreachable<List<ClientRef>> ignored ->
                    ResponseEntity.notFound().build();
            case GateResult.Denied<List<ClientRef>> ignored -> ResponseEntity
                    .status(HttpStatus.FORBIDDEN)
                    .body(Problem.forbidden(
                            "You do not have permission to change this project's settings."));
            case GateResult.Allowed<List<ClientRef>> allowed ->
                    ResponseEntity.ok(allowed.value());
        };
    }

    /**
     * {@code PUT /projects/{projectId}/client} — attach, change or detach.
     *
     * <h2>Why this is its own endpoint and not a field on the settings document</h2>
     *
     * <p>It was one, for a single commit, and 018 D-d records the reversal. A nullable id has
     * <strong>no safe reading of an absence in either direction</strong>: "leave it alone" and
     * "detach" are the same absence, and unlike a boolean it cannot be defaulted out of the
     * problem. On a full-replacement document a record cannot tell the two apart at all — Jackson
     * gives an absent field and an explicit {@code null} the same Java value, which is the whole
     * reason {@code api.PatchBody} exists — so a save from the Review screen, which does not offer
     * this field, would have silently detached the project while answering 200.
     *
     * <p>One field on its own path has no absence to read. {@code PUT /projects/{projectId}/archive}
     * is the same shape for the same family of reason, and its contract note says it: <em>"One
     * field, and it is the target state rather than a verb."</em>
     *
     * <h2>It is in {@code crm} because it validates a client</h2>
     *
     * <p>The column is {@code project}'s and the check is not: a client id belonging to another
     * company must be refused, and only this module can see the row that answers. The path segment
     * does not decide ownership — see the class comment.
     */
    @PutMapping("/projects/{projectId}/client")
    public ResponseEntity<Object> attach(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestBody ProjectClientRequest body) {

        GateResult<ClientService.Outcome<ProjectView>> gate =
                clients.attachTo(UUID.fromString(jwt.getSubject()), projectId, body);

        return switch (gate) {
            case GateResult.Unreachable<ClientService.Outcome<ProjectView>> ignored ->
                    ResponseEntity.notFound().build();
            case GateResult.Denied<ClientService.Outcome<ProjectView>> ignored -> ResponseEntity
                    .status(HttpStatus.FORBIDDEN)
                    .body(Problem.forbidden(
                            "You do not have permission to change this project's settings."));
            case GateResult.Allowed<ClientService.Outcome<ProjectView>> allowed ->
                    switch (allowed.value()) {
                        case ClientService.Outcome.Invalid<ProjectView> invalid ->
                                ResponseEntity.badRequest()
                                        .body(Problem.validation(invalid.errors()));
                        case ClientService.Outcome.Missing<ProjectView> ignored ->
                                ResponseEntity.notFound().build();
                        case ClientService.Outcome.Conflict<ProjectView> clash -> ResponseEntity
                                .status(HttpStatus.CONFLICT)
                                .body(Problem.conflict(clash.title(), clash.detail()));
                        case ClientService.Outcome.Written<ProjectView> stored ->
                                ResponseEntity.ok(stored.value());
                        case ClientService.Outcome.Removed<ProjectView> ignored ->
                                ResponseEntity.noContent().build();
                    };
        };
    }
}
