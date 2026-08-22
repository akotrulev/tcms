package com.tcms.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/cases/search} — find a case across every project the caller can see (§249).
 *
 * <p>A controller of its own rather than a method on {@code CaseController}, because that class maps
 * {@code /projects/{projectId}/cases} and every one of its operations reads a project from the path.
 * This read has no project segment; hanging it off that prefix would need a second, projectless
 * mapping on a class whose whole shape is "one project's cases".
 */
@RestController
@RequestMapping("/api/v1/cases")
class CrossProjectSearchController {

    private final CrossProjectSearchService search;

    CrossProjectSearchController(CrossProjectSearchService search) {
        this.search = search;
    }

    /**
     * The search.
     *
     * <p>Parameters are bound one by one rather than onto the record, which is how
     * {@code CaseController} binds {@link CaseQuery}: the defaults are visible at the edge, and the
     * record's compact constructor re-normalizes anyway, so {@code page=-3} and {@code size=100000}
     * are clamped after arriving rather than trusted.
     *
     * <p>The caller's id comes from the token and never from a parameter — the one rule
     * {@code CompanyContext} and this whole isolation model rest on.
     */
    @GetMapping("/search")
    public ResponseEntity<Object> search(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String q,
            @RequestParam(name = "projectId", required = false) List<UUID> projectIds,
            @RequestParam(required = false) UUID ownerId,
            @RequestParam(name = "tag", required = false) List<String> tags,
            @RequestParam(required = false) List<LifecycleStatus> lifecycleStatus,
            @RequestParam(required = false) List<AutomationStatus> automationStatus,
            @RequestParam(required = false) List<Priority> priority,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + CaseQuery.DEFAULT_SIZE) int size,
            @RequestParam(defaultValue = CaseSort.DEFAULT) String sort) {

        CrossProjectSearchQuery request = new CrossProjectSearchQuery(
                q, projectIds, ownerId, tags,
                lifecycleStatus, automationStatus, priority,
                page, size, sort);

        // Refused before the gate runs: no permission needs evaluating for a request that cannot be
        // answered whatever the caller holds.
        if (!request.isBounded()) {
            return ResponseEntity.badRequest().body(unboundedSearchProblem());
        }
        if (request.queryTooShort()) {
            return ResponseEntity.badRequest().body(shortQueryProblem());
        }

        return ResponseEntity.ok(
                search.search(UUID.fromString(jwt.getSubject()), request));
    }

    /**
     * The refusal for a search that names nothing to search for.
     *
     * <p>400 rather than 404: nothing failed to resolve, and the caller is entitled to every request
     * this sentence names — what they sent is simply not one of them. {@code /work-items}'
     * {@code unscopedBoardProblem} is the precedent and the argument is the same: answering
     * "every case in every project I can see" would hand back the one response in the product whose
     * size is the company's, and silently forcing a filter on would answer a request nobody made.
     */
    private static ProblemDetail unboundedSearchProblem() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Name something to search for");
        problem.setDetail("Search by text with q, by owner with ownerId, or by tag. There is no "
                + "search that is every case in every project.");
        return problem;
    }

    /** One character over a whole company is the unbounded read wearing a filter. */
    private static ProblemDetail shortQueryProblem() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Search for at least two characters");
        problem.setDetail("q needs " + CrossProjectSearchQuery.MIN_QUERY + " characters or more. A "
                + "case key such as TC-412 is always enough on its own.");
        return problem;
    }
}
