package com.tcms.execution;

import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * One case's execution history (plan §6p).
 *
 * <h2>Why an {@code execution} controller sits under a {@code /cases} path</h2>
 *
 * <p>The path names a case and the contract tags the operation {@code [repository, reporting]}, but
 * every row of the answer is a {@code test_execution} joined to a {@code test_run} — this module's
 * own tables. Serving it from {@code repository} would mean that module reading {@code execution}'s
 * tables, which is a crossing that does not exist in either direction today; serving it here needs
 * only the {@link CaseFacts} seam that already exists and is already the one file allowed to name a
 * {@code repository} table. A URL is a client-facing shape, not a module boundary.
 *
 * <p>No mapping collides with {@code repository.CaseController}: it owns
 * {@code /cases}, {@code /cases/{caseId}}, {@code /versions}, and the three writes, and declares
 * nothing under {@code /history}. Two controllers may share a prefix; only an identical pattern is
 * ambiguous, and {@code ApiSurfaceTest} would fail the context if one ever became so.
 *
 * <p>404, never 403, on the read — an absent case, one in another company, one in a sibling project,
 * one soft-deleted, and a project the caller holds no grant on all collapse into an empty
 * {@link java.util.Optional} before this class sees them (§6t).
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/cases/{caseId}/history")
public class CaseHistoryController {

    private final CaseHistoryService history;

    public CaseHistoryController(CaseHistoryService history) {
        this.history = history;
    }

    /**
     * The timeline and the strip above it.
     *
     * <p>{@code scope} is bound as a {@code String} and parsed here rather than declared as the
     * enum, for {@link PlanController#statistics}'s reason: Spring's own converter answers a bare
     * 400 naming the Java type and the rejected value, which publishes an internal class name and
     * tells the caller nothing about what it should have sent.
     *
     * <p>It defaults to {@code CURRENT_VERSION} — the safe reading. Blending results across versions
     * reports a pass rate for a test whose text changed underneath it, so the narrower scope is the
     * one a caller gets by saying nothing (§6p).
     *
     * <p><strong>This parameter is the reason the slice found a defect before it wrote a line.</strong>
     * The SPA has sent it since Phase 1 and the contract never declared it, so Spring — which
     * silently ignores an unbound query parameter — would have answered the "Current version" chip
     * with every version's results, under a heading saying otherwise. The same shape as §47's
     * {@code ownerId}.
     */
    @GetMapping
    public ResponseEntity<Object> history(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID caseId,
            @RequestParam(name = "scope", required = false) String scope,
            @RequestParam(name = "caseVersionNo", required = false) Integer caseVersionNo) {

        CaseHistoryService.Scope parsed;
        try {
            parsed = scope == null || scope.isBlank()
                    ? CaseHistoryService.Scope.CURRENT_VERSION
                    : CaseHistoryService.Scope.valueOf(scope.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return ResponseEntity.badRequest().body(unknownScopeProblem(scope));
        }

        return history.history(
                        UUID.fromString(jwt.getSubject()), projectId, caseId, parsed, caseVersionNo)
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Names the two, for {@link PlanController}'s recorded reason: this is public vocabulary. */
    private static ProblemDetail unknownScopeProblem(String rejected) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Unknown history scope");
        problem.setDetail("'" + rejected + "' is not a history scope. Valid values: "
                + Arrays.stream(CaseHistoryService.Scope.values())
                        .map(Enum::name)
                        .collect(Collectors.joining(", "))
                + ".");
        return problem;
    }
}
