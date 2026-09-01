package com.tcms.tracker.release;

import com.tcms.api.Problem;
import com.tcms.project.GateResult;
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
 * The QA link, in both directions — {@code /issues/{key}/test-cases} and
 * {@code /projects/{projectId}/case-verifications}.
 *
 * <p>Two paths and one controller because they are one fact read from two ends, which is what
 * {@code test-cases.html}'s own panel foot says out loud. The permissions differ by verb rather than
 * by path: reading either is {@code issue:read} and writing the set is {@code issue:edit}, because
 * linking a case to a story edits the story and there is no {@code link:*} key.
 */
@RestController
@RequestMapping("/api/v1")
public class VerificationController {

    private static final String CANNOT_READ =
            "You do not have permission to read this project's issues.";
    private static final String CANNOT_EDIT =
            "You do not have permission to edit issues in this project.";

    private final VerificationService verifications;

    VerificationController(VerificationService verifications) {
        this.verifications = verifications;
    }

    @GetMapping("/issues/{key}/test-cases")
    public ResponseEntity<Object> list(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String key) {

        return read(verifications.list(UUID.fromString(jwt.getSubject()), key));
    }

    @GetMapping("/projects/{projectId}/case-verifications")
    public ResponseEntity<Object> verificationsIn(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {

        return read(verifications.verificationsIn(UUID.fromString(jwt.getSubject()), projectId));
    }

    @PutMapping("/issues/{key}/test-cases")
    public ResponseEntity<Object> replace(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String key,
            @RequestBody IssueTestCasesInput submitted) {

        GateResult<VerificationService.Outcome> gate =
                verifications.replace(UUID.fromString(jwt.getSubject()), key, submitted);

        return switch (gate) {
            case GateResult.Unreachable<VerificationService.Outcome> ignored ->
                    ResponseEntity.notFound().build();
            case GateResult.Denied<VerificationService.Outcome> ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problem.forbidden(CANNOT_EDIT));
            case GateResult.Allowed<VerificationService.Outcome> allowed -> switch (allowed.value()) {
                case VerificationService.Outcome.Invalid invalid ->
                        ResponseEntity.badRequest().body(Problem.validation(invalid.errors()));
                case VerificationService.Outcome.Replaced replaced ->
                        ResponseEntity.ok(replaced.views());
            };
        };
    }

    private <T extends List<?>> ResponseEntity<Object> read(GateResult<T> gate) {
        return switch (gate) {
            case GateResult.Unreachable<T> ignored -> ResponseEntity.notFound().build();
            case GateResult.Denied<T> ignored -> ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Problem.forbidden(CANNOT_READ));
            case GateResult.Allowed<T> allowed -> ResponseEntity.ok(allowed.value());
        };
    }
}
