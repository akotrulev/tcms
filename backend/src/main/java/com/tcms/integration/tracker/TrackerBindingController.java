package com.tcms.integration.tracker;

import com.tcms.api.Problem;
import com.tcms.project.GateResult;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /projects/{projectId}/tracker/bindings} and the connected-members list beside it.
 *
 * <h2>Both halves cost {@code integration:manage}, reads included</h2>
 *
 * <p>{@code NotificationChannelController}'s argument, and it lands the same way: nothing in this
 * product branches on whether a binding exists, so {@code ProjectSettingsController}'s "every client
 * has a decision to make from this" does not apply. What the reads serve is configuration and, next
 * door, a list of who on the team holds a tracker account — so the settings screen's own gate is the
 * right one for the reads as well as the writes.
 *
 * <p>The caller comes from the token and the company from the context. Neither is ever a parameter:
 * a {@code companyId} parameter would let request input select the tenancy discriminator (ERD §0.1
 * point 2).
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/tracker")
public class TrackerBindingController {

    private final TrackerBindingService bindings;

    public TrackerBindingController(TrackerBindingService bindings) {
        this.bindings = bindings;
    }

    /** This project's bindings, default first. {@code integration:manage}. */
    @GetMapping("/bindings")
    public ResponseEntity<Object> list(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {

        return read(bindings.list(caller(jwt), projectId));
    }

    /** One binding. {@code integration:manage}. */
    @GetMapping("/bindings/{bindingId}")
    public ResponseEntity<Object> get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID bindingId) {

        return read(bindings.find(caller(jwt), projectId, bindingId));
    }

    /**
     * Who on this project has connected to the trackers it is bound to (004 D19).
     *
     * <p>A state per person per host and nothing else — see {@link TrackerConnectedMemberView} for
     * the five fields this response must never grow and why the test closes its key set rather than
     * naming them.
     */
    @GetMapping("/connected-members")
    public ResponseEntity<Object> connectedMembers(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {

        return read(bindings.connectedMembers(caller(jwt), projectId));
    }

    /** Binds this project to a tracker project. {@code integration:manage}. */
    @PostMapping("/bindings")
    public ResponseEntity<Object> create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestBody TrackerBindingInput input) {

        TrackerBindingService.Outcome outcome = bindings.create(caller(jwt), projectId, input);
        if (outcome instanceof TrackerBindingService.Outcome.Saved saved) {
            return ResponseEntity.created(location(projectId, saved.view().id())).body(saved.view());
        }
        return render(outcome);
    }

    /**
     * Changes a binding's mapping, default flag or enabled state. {@code integration:manage}.
     *
     * <p>{@code If-Match} is bound as a {@link Long} so a malformed header is a 400 from the binder
     * rather than a silently skipped precondition; absent stays null, which is the documented
     * last-write-wins path — {@code NotificationChannelController}'s reasoning, unchanged.
     *
     * <p>There is no {@code provider} and no {@code baseUrl} on the patch, and
     * {@link TrackerBindingPatch} says why.
     */
    @PatchMapping("/bindings/{bindingId}")
    public ResponseEntity<Object> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID bindingId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) Long ifMatch,
            @RequestBody TrackerBindingPatch patch) {

        return render(bindings.update(caller(jwt), projectId, bindingId, ifMatch, patch));
    }

    /** Unbinds. Issue links made through this binding survive — 004 D18. */
    @DeleteMapping("/bindings/{bindingId}")
    public ResponseEntity<Object> delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID bindingId) {

        return render(bindings.delete(caller(jwt), projectId, bindingId));
    }

    private static UUID caller(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    /**
     * The three read states.
     *
     * <p>A null inside {@link GateResult.Allowed} is a binding this project does not have, which
     * answers the same bodiless 404 an unreachable project does (§6t), so the refusal is not an
     * oracle for which binding ids exist elsewhere.
     */
    private static ResponseEntity<Object> read(GateResult<?> gated) {
        return switch (gated) {
            case GateResult.Unreachable<?> ignored -> ResponseEntity.notFound().build();
            case GateResult.Denied<?> ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problem.forbidden(
                            "You do not have permission to see this project's tracker settings.",
                            TrackerBindingService.MANAGE, Problem.Scope.PROJECT));
            case GateResult.Allowed<?> allowed -> allowed.value() == null
                    ? ResponseEntity.notFound().build()
                    : ResponseEntity.ok(allowed.value());
        };
    }

    /** One mapping for all three writers — the sealed outcome makes the switch exhaustive. */
    private static ResponseEntity<Object> render(TrackerBindingService.Outcome outcome) {
        return switch (outcome) {
            case TrackerBindingService.Outcome.Saved saved -> ResponseEntity.ok(saved.view());
            case TrackerBindingService.Outcome.Deleted ignored ->
                    ResponseEntity.noContent().build();
            case TrackerBindingService.Outcome.NotFound ignored ->
                    ResponseEntity.notFound().build();
            case TrackerBindingService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problem.forbidden(
                            "You do not have permission to change this project's tracker settings.",
                            TrackerBindingService.MANAGE, Problem.Scope.PROJECT));
            case TrackerBindingService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(Problem.validation(invalid.errors()));
            case TrackerBindingService.Outcome.Stale ignored ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(
                            Problem.stale("tracker binding"));
            case TrackerBindingService.Outcome.Duplicate ignored ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(Problem.conflict(
                            "Already bound",
                            "This project is already bound to that tracker project on that host."));
        };
    }

    private static URI location(UUID projectId, UUID bindingId) {
        return URI.create("/api/v1/projects/" + projectId + "/tracker/bindings/" + bindingId);
    }
}
