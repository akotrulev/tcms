package com.tcms.tracker.release;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.api.PatchBody;
import com.tcms.api.Problem;
import com.tcms.project.GateResult;
import java.net.URI;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Releases — {@code /projects/{projectId}/releases}, {@code /releases/{releaseId}} and
 * {@code /releases/{releaseId}/publish}.
 *
 * <p>Three paths and one controller because they are one concept with one service behind them. The
 * forbidden sentences differ, and the difference is the roles spec's: <strong>managing</strong> a
 * project's releases is {@code release:manage} and <strong>marking one released</strong> is
 * {@code release:publish}, so a caller granted one and not the other is told which act they cannot
 * perform rather than a single sentence covering both.
 */
@RestController
@RequestMapping("/api/v1")
public class ReleaseController {

    private static final String CANNOT_MANAGE =
            "You do not have permission to manage this project's releases.";
    private static final String CANNOT_PUBLISH =
            "You do not have permission to mark a release released.";

    private final ReleaseService releases;
    private final ObjectMapper mapper;

    ReleaseController(ReleaseService releases, ObjectMapper mapper) {
        this.releases = releases;
        this.mapper = mapper;
    }

    @GetMapping("/projects/{projectId}/releases")
    public ResponseEntity<Object> list(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {

        return read(releases.list(UUID.fromString(jwt.getSubject()), projectId));
    }

    @GetMapping("/releases/{releaseId}")
    public ResponseEntity<Object> get(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID releaseId) {

        return read(releases.get(UUID.fromString(jwt.getSubject()), releaseId));
    }

    @PostMapping("/projects/{projectId}/releases")
    public ResponseEntity<Object> create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestBody ReleaseInput submitted) {

        GateResult<ReleaseService.Outcome> gate =
                releases.create(UUID.fromString(jwt.getSubject()), projectId, submitted);

        if (gate instanceof GateResult.Allowed<ReleaseService.Outcome> allowed
                && allowed.value() instanceof ReleaseService.Outcome.Written stored) {
            // Location, and it is the release's own canonical URL rather than the project-scoped
            // list it was posted to — the id is what every later call takes.
            return ResponseEntity.created(URI.create("/api/v1/releases/" + stored.view().id()))
                    .body(stored.view());
        }
        return answer(gate, HttpStatus.CREATED, CANNOT_MANAGE);
    }

    @PatchMapping("/releases/{releaseId}")
    public ResponseEntity<Object> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID releaseId,
            @RequestBody JsonNode body) {

        PatchBody patch = new PatchBody(body);
        ReleaseUpdate request;
        try {
            request = patch.as(mapper, ReleaseUpdate.class);
        } catch (PatchBody.MalformedBody unreadable) {
            return ResponseEntity.badRequest().body(Problem.validation(Map.of(
                    "body", List.of("The request body could not be read as a release."))));
        }
        return answer(
                releases.update(UUID.fromString(jwt.getSubject()), releaseId, request, patch),
                HttpStatus.OK, CANNOT_MANAGE);
    }

    @DeleteMapping("/releases/{releaseId}")
    public ResponseEntity<Object> delete(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID releaseId) {

        return answer(
                releases.delete(UUID.fromString(jwt.getSubject()), releaseId),
                HttpStatus.NO_CONTENT, CANNOT_MANAGE);
    }

    @PostMapping("/releases/{releaseId}/publish")
    public ResponseEntity<Object> publish(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID releaseId) {

        return answer(
                releases.publish(UUID.fromString(jwt.getSubject()), releaseId),
                HttpStatus.OK, CANNOT_PUBLISH);
    }

    /**
     * A gated <em>read</em>, whose {@link GateResult.Denied} is a 403 rather than a 404.
     *
     * <p>{@code release:read} is the first gate on this surface an Automation Service credential
     * does not hold, so the denied branch is reachable — and the caller can see the project, which
     * is what makes 403 the honest answer rather than a concealment question.
     */
    private <T> ResponseEntity<Object> read(GateResult<T> gate) {
        return switch (gate) {
            case GateResult.Unreachable<T> ignored -> ResponseEntity.notFound().build();
            case GateResult.Denied<T> ignored -> ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Problem.forbidden("You do not have permission to read this project's "
                            + "releases."));
            case GateResult.Allowed<T> allowed -> ResponseEntity.ok(allowed.value());
        };
    }

    /**
     * One mapping for all four writes, because the outcomes are one sealed set.
     *
     * <p>The exhaustive switch is what makes a fifth outcome a compile error here rather than a
     * silent fall-through to a 200 with the wrong body.
     */
    private ResponseEntity<Object> answer(
            GateResult<ReleaseService.Outcome> gate, HttpStatus written, String forbidden) {

        return switch (gate) {
            case GateResult.Unreachable<ReleaseService.Outcome> ignored ->
                    ResponseEntity.notFound().build();
            case GateResult.Denied<ReleaseService.Outcome> ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problem.forbidden(forbidden));
            case GateResult.Allowed<ReleaseService.Outcome> allowed -> switch (allowed.value()) {
                case ReleaseService.Outcome.Invalid invalid ->
                        ResponseEntity.badRequest().body(Problem.validation(invalid.errors()));
                case ReleaseService.Outcome.Duplicate taken -> ResponseEntity
                        .status(HttpStatus.CONFLICT)
                        .body(Problem.conflict("That release already exists",
                                "This project already has a release called " + taken.version()
                                        + ". Versions are matched without regard to case, so two "
                                        + "that differ only in capitals would be one version twice."
                                        + " A version shipped from another project is a separate "
                                        + "release and is not affected."));
                case ReleaseService.Outcome.Written stored ->
                        ResponseEntity.status(written).body(stored.view());
                case ReleaseService.Outcome.Deleted ignored -> ResponseEntity.noContent().build();
            };
        };
    }
}
