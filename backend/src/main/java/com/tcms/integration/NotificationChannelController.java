package com.tcms.integration;

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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /projects/{projectId}/notification-channels} — where one project sends its notices, over
 * HTTP.
 *
 * <h2>Both halves cost {@code integration:manage}, and the read gate is the decision</h2>
 *
 * <p>{@code ProjectSettingsController} deliberately does the opposite one path family over: its
 * {@code GET} is readable by anybody who can see the project, because §6g's "a project that never
 * turns review on should see no extra UI at all" is a decision every client makes on every
 * artifact screen for every reader, and none of those readers is an administrator.
 *
 * <p>Nothing in this product branches on whether a channel exists, so that argument has nothing to
 * buy here — and what these reads serve is the other way round. A channel carries
 * {@link NotificationChannelView#secretHint}, the tail of a live credential; it carries the
 * recipient list, which is people's addresses; and the delivery history carries whatever a remote
 * endpoint said about a failure. The settings screen's own gate is therefore the right one for the
 * reads as well as for the writes, and {@link com.tcms.project.ProjectService#readGranted} is the
 * seam that says so: visibility still answers 404, and the permission half answers 403.
 *
 * <h2>No {@code Idempotency-Key} on the create</h2>
 *
 * <p>{@code POST /projects/{projectId}/conditions}' reason: a duplicate channel is visible in the
 * list beside its twin the moment the create returns, and deleting one costs the same permission
 * that made it. There is no idempotency record behind this path, and declaring the header without
 * honouring it is worse than declaring nothing — a client that trusted it would replay a timed-out
 * create and get a second channel that delivers every notice twice.
 *
 * <h2>The caller comes from the token, the company from the context</h2>
 *
 * <p>Neither is ever a parameter, for the reasons {@code ProjectController} sets out.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/notification-channels")
public class NotificationChannelController {

    private final NotificationChannelService channels;

    public NotificationChannelController(NotificationChannelService channels) {
        this.channels = channels;
    }

    /** This project's channels. {@code integration:manage}. */
    @GetMapping
    public ResponseEntity<Object> list(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {

        return read(channels.list(callerOf(jwt), projectId));
    }

    /** One channel. {@code integration:manage}. */
    @GetMapping("/{channelId}")
    public ResponseEntity<Object> get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID channelId) {

        return read(channels.find(callerOf(jwt), projectId, channelId));
    }

    /**
     * One channel's delivery attempts, newest first. {@code integration:manage}.
     *
     * <p>Hung off the channel rather than off the project because a delivery is only ever asked
     * about as one — "is <em>this</em> webhook working" — and because the rows go when the channel
     * does. A project-level log would outlive the channels naming it and would have to answer for
     * ids that no longer resolve.
     */
    @GetMapping("/{channelId}/deliveries")
    public ResponseEntity<Object> deliveries(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID channelId) {

        return read(channels.history(callerOf(jwt), projectId, channelId));
    }

    /** Adds a channel, with its subscriptions. {@code integration:manage}. */
    @PostMapping
    public ResponseEntity<Object> create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestBody NotificationChannelInput input) {

        NotificationChannelService.Outcome outcome =
                channels.create(callerOf(jwt), projectId, input);
        if (outcome instanceof NotificationChannelService.Outcome.Saved saved) {
            return ResponseEntity.created(location(projectId, saved.view().id()))
                    .body(saved.view());
        }
        return render(outcome);
    }

    /**
     * Replaces a channel. {@code integration:manage}.
     *
     * <p>{@code If-Match} is bound as a {@link Long} so a malformed header is a 400 from the
     * binder rather than a silently skipped precondition; absent stays null, which is the
     * documented last-write-wins path.
     */
    @PutMapping("/{channelId}")
    public ResponseEntity<Object> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID channelId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) Long ifMatch,
            @RequestBody NotificationChannelInput input) {

        return render(channels.update(callerOf(jwt), projectId, channelId, ifMatch, input));
    }

    /** Removes a channel and its history. {@code integration:manage}. */
    @DeleteMapping("/{channelId}")
    public ResponseEntity<Object> delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID channelId) {

        return render(channels.delete(callerOf(jwt), projectId, channelId));
    }

    private static UUID callerOf(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    /**
     * The three read states.
     *
     * <p>A null inside {@link GateResult.Allowed} is a channel this project does not have, which
     * answers the same bodiless 404 an unreachable project does — §6t, so the refusal is not an
     * oracle for which channel ids exist elsewhere.
     */
    private static ResponseEntity<Object> read(GateResult<?> gated) {
        return switch (gated) {
            case GateResult.Unreachable<?> ignored -> ResponseEntity.notFound().build();
            case GateResult.Denied<?> ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problem.forbidden(
                            "You do not have permission to see this project's notification "
                                    + "channels."));
            case GateResult.Allowed<?> allowed -> allowed.value() == null
                    ? ResponseEntity.notFound().build()
                    : ResponseEntity.ok(allowed.value());
        };
    }

    /** One mapping for all three writers — the sealed outcome makes the switch exhaustive. */
    private static ResponseEntity<Object> render(NotificationChannelService.Outcome outcome) {
        return switch (outcome) {
            case NotificationChannelService.Outcome.Saved saved -> ResponseEntity.ok(saved.view());
            case NotificationChannelService.Outcome.Deleted ignored -> ResponseEntity.noContent().build();
            case NotificationChannelService.Outcome.NotFound ignored ->
                    ResponseEntity.notFound().build();
            case NotificationChannelService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problem.forbidden(
                            "You do not have permission to change this project's notification "
                                    + "channels."));
            case NotificationChannelService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(Problem.validation(invalid.errors()));
            case NotificationChannelService.Outcome.Conflict ignored ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(
                            Problem.stale("notification channel"));
        };
    }

    /**
     * The created channel's address.
     *
     * <p>Points at {@code /{channelId}}, which serves a {@code GET} — unlike
     * {@code ConditionController}'s, whose per-id path answers only a {@code PUT} and therefore
     * could not be dereferenced by a client that followed the header.
     */
    private static URI location(UUID projectId, UUID channelId) {
        return URI.create(
                "/api/v1/projects/" + projectId + "/notification-channels/" + channelId);
    }
}
