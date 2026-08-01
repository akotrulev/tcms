package com.tcms.repository;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /projects/{projectId}/dashboard} — the project's landing page.
 *
 * <p>Tagged {@code reporting} in the contract and served from {@code repository}, which is not a
 * discrepancy to fix: the tag names the reader's concern, the module names where the data lives.
 * Every figure on this page is a rollup of {@code test_case} and its satellites, so serving it from a
 * {@code reporting} module would mean that module reading this one's tables — the cross-module edge
 * ArchUnit refuses — to compute numbers this module already knows how to count. The endpoint moves
 * the day it needs execution results, and {@code CaseFacts} is the shape that move would take.
 *
 * <h2>Its own controller, and no query parameters at all</h2>
 *
 * <p>Separate from {@link CaseController} for the reason that one is separate from
 * {@code ProjectController}: the path expresses the scope rather than the owner. And the endpoint
 * takes nothing — no range, no filter, no grouping — because every one of those would be a second
 * way to ask a question the case list already answers better. The dashboard's job is to be the
 * fixed, glanceable starting point that <em>links</em> into that list.
 *
 * <h2>404, never 403</h2>
 *
 * <p>A project that does not exist, one in another company, and one the caller holds no grant on all
 * answer 404 with no body — {@link DashboardService#dashboard} collapses the three into an empty
 * {@link java.util.Optional}, so there is nothing here to un-collapse. A 403 would confirm the id
 * names something real, making the path an oracle for another client's project ids (§6t).
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/dashboard")
public class DashboardController {

    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    /**
     * The whole dashboard, in one response.
     *
     * <p>One request rather than a call per panel, deliberately: the panels are read together and
     * against each other — "142 drafts" beside "Checkout is 78 of them" — and six independently
     * loading regions would let a user compare two numbers computed seconds apart while cases were
     * being created between them.
     */
    @GetMapping
    public ResponseEntity<DashboardView> dashboard(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {

        return dashboard.dashboard(UUID.fromString(jwt.getSubject()), projectId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
