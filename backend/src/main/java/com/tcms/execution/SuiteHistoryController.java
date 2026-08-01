package com.tcms.execution;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * One suite's execution history (plan §6p).
 *
 * <h2>Why an {@code execution} controller sits under a {@code /suites} path</h2>
 *
 * <p>{@link CaseHistoryController}'s argument, one entity over: the path names a suite and the
 * contract tags the operation {@code [repository, reporting]}, but every row of the answer is a
 * {@code test_execution} joined to a {@code test_run} — this module's own tables. Serving it from
 * {@code repository} would mean that module reading {@code execution}'s tables, a crossing that
 * exists in neither direction; serving it here needs only the {@link CaseFacts} seam that already
 * exists and is already the one file allowed to name a {@code repository} table.
 *
 * <p>No mapping collides with {@code repository.SuiteController}: it owns {@code /suites},
 * {@code /suites/{suiteId}} and the two writes, and declares nothing under {@code /history}. Two
 * controllers may share a prefix; only an identical pattern is ambiguous, and {@code ApiSurfaceTest}
 * would fail the context if one ever became so.
 *
 * <p><strong>No query parameters, and one was deleted from the contract to keep it that way.</strong>
 * A {@code versionNo} filter had been declared here since the first draft and nothing ever sent it;
 * §50's binding check refuses a declared parameter no handler binds, and an unimplemented filter
 * answers 200 to a narrower question than the caller asked. Re-adding it is contract-first, together
 * with the version selector that would send it.
 *
 * <p>404, never 403, on the read — an absent suite, one in another company, one in a sibling project,
 * one soft-deleted, and a project the caller holds no grant on all collapse into an empty
 * {@link java.util.Optional} before this class sees them (§6t).
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/suites/{suiteId}/history")
public class SuiteHistoryController {

    private final SuiteHistoryService history;

    public SuiteHistoryController(SuiteHistoryService history) {
        this.history = history;
    }

    @GetMapping
    public ResponseEntity<SuiteHistoryView> history(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID suiteId) {

        return history.history(UUID.fromString(jwt.getSubject()), projectId, suiteId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
