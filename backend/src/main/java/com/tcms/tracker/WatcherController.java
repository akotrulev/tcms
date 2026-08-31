package com.tcms.tracker;

import com.tcms.api.Problem;
import com.tcms.api.UserRef;
import com.tcms.project.GateResult;
import java.util.List;
import java.util.Map;
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

/** {@code PUT /issues/{key}/watchers} — the star, in both directions. */
@RestController
@RequestMapping("/api/v1/issues/{key}/watchers")
public class WatcherController {

    private final WatcherService watchers;

    public WatcherController(WatcherService watchers) {
        this.watchers = watchers;
    }

    @PutMapping
    public ResponseEntity<Object> put(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String key,
            @RequestBody IssueWatchInput request) {

        if (request == null || request.watching() == null) {
            // Named rather than defaulted: an absent field defaulted to false would silently
            // unfollow the issue the caller was trying to follow.
            return ResponseEntity.badRequest().body(Problem.validation(Map.of(
                    "watching", List.of("Say whether you want to follow this issue."))));
        }

        GateResult<List<UserRef>> gate = watchers.setWatching(
                UUID.fromString(jwt.getSubject()), key, request.watching());

        return switch (gate) {
            case GateResult.Unreachable<List<UserRef>> ignored -> ResponseEntity.notFound().build();
            // Unreachable in practice — all six roles hold issue:read (§A3) — and present because
            // the gate has three answers and a mapping that guessed at one of them would be the
            // thing that broke on the day a role stopped holding it.
            case GateResult.Denied<List<UserRef>> ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problem.forbidden(
                            "You do not have permission to see issues in this project."));
            case GateResult.Allowed<List<UserRef>> allowed -> ResponseEntity.ok(allowed.value());
        };
    }
}
