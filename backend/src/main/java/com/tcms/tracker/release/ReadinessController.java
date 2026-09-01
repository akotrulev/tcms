package com.tcms.tracker.release;

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
 * {@code GET /releases/{releaseId}/readiness} and {@code GET /projects/{projectId}/roadmap} — the
 * two reads that answer <em>what ships when</em> and <em>can we ship it</em>.
 *
 * <p>One controller for two services because they are one screen's two halves behind one gate:
 * {@code release:read}, which Automation Service does not hold (§A3). The denied branch is therefore
 * reachable and answers <strong>403</strong>, not 404 — the caller can see the project, so
 * concealing the endpoint would tell them their release had been deleted.
 */
@RestController
@RequestMapping("/api/v1")
public class ReadinessController {

    private static final String FORBIDDEN =
            "You do not have permission to read this project's releases.";

    private final ReadinessService readiness;
    private final RoadmapService roadmap;

    ReadinessController(ReadinessService readiness, RoadmapService roadmap) {
        this.readiness = readiness;
        this.roadmap = roadmap;
    }

    @GetMapping("/releases/{releaseId}/readiness")
    public ResponseEntity<Object> readiness(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID releaseId) {

        return answer(readiness.readiness(UUID.fromString(jwt.getSubject()), releaseId));
    }

    @GetMapping("/projects/{projectId}/roadmap")
    public ResponseEntity<Object> roadmap(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {

        return answer(roadmap.roadmap(UUID.fromString(jwt.getSubject()), projectId));
    }

    private <T> ResponseEntity<Object> answer(GateResult<T> gate) {
        return switch (gate) {
            case GateResult.Unreachable<T> ignored -> ResponseEntity.notFound().build();
            case GateResult.Denied<T> ignored -> ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Problem.forbidden(FORBIDDEN));
            case GateResult.Allowed<T> allowed -> ResponseEntity.ok(allowed.value());
        };
    }
}
