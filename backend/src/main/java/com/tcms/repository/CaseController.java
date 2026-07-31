package com.tcms.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /projects/{projectId}/cases} — the case list, and the biggest screen in the product.
 *
 * <p>A separate controller from {@link SuiteController} for the reason that one is separate from
 * {@code ProjectController}: the path expresses the scope, not the owner. Both are ERD §4 and this
 * module, and merging them would make one class the home of every project-scoped read.
 *
 * <h2>The caller comes from the token, and the company from the context</h2>
 *
 * <p>Neither is a parameter. A {@code userId} parameter answers "what may <em>that</em> user see",
 * and a {@code companyId} parameter lets request input select the tenancy discriminator — the one
 * thing {@link com.tcms.tenancy.CompanyContext} exists to prevent (ERD §0.1 point 2).
 *
 * <h2>404, never 403</h2>
 *
 * <p>A project that does not exist, one in another company, and one in this company the caller
 * holds no grant on all answer <strong>404 with no body</strong>. A 403 confirms the id names
 * something real, which makes the endpoint an oracle for another client's project ids (§6t, handoff
 * §3 lesson 13). {@link CaseService#search} collapses the three into an empty
 * {@link java.util.Optional} so this method has nothing to un-collapse.
 *
 * <h2>Every filter is bound explicitly, and the unbindable ones are not silently dropped</h2>
 *
 * <p>Spring binds each parameter by name, and an enum value it cannot parse is a 400 rather than a
 * silently ignored filter — which matters more here than it looks: a dropped filter returns the
 * project's <em>entire</em> case list under a heading that claims it is narrowed. That is the same
 * failure {@code releaseVersion} is handled for in {@link CaseSpecifications}, arriving through a
 * different door.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/cases")
public class CaseController {

    private final CaseService cases;

    public CaseController(CaseService cases) {
        this.cases = cases;
    }

    /**
     * A page of matching cases with the facet counts beside them.
     *
     * <p>An empty {@code items} array is a valid 200 and means "nothing matched" — the state of a
     * freshly provisioned project, and of any filter combination the client has narrowed past its
     * data. It is deliberately not the 404 the gate uses: a user who cannot load the page cannot
     * clear the filter that emptied it.
     *
     * <p>Defaults live on the annotations rather than in {@link CaseQuery}'s constructor so they are
     * visible at the edge, and are re-normalized there anyway — {@code page=-3} and
     * {@code size=100000} are bound before that record ever sees them, and clamping is its job.
     */
    @GetMapping
    public ResponseEntity<CasePageView> search(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID suiteId,
            @RequestParam(required = false) UUID featureId,
            @RequestParam(required = false) UUID testTypeId,
            @RequestParam(required = false) UUID testLevelId,
            @RequestParam(required = false) List<LifecycleStatus> lifecycleStatus,
            @RequestParam(required = false) List<AutomationStatus> automationStatus,
            @RequestParam(required = false) List<Priority> priority,
            @RequestParam(required = false) List<HealthSignal> healthSignal,
            @RequestParam(required = false) String releaseVersion,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + CaseQuery.DEFAULT_SIZE) int size,
            @RequestParam(defaultValue = CaseSort.DEFAULT) String sort) {

        CaseQuery request = new CaseQuery(
                q, suiteId, featureId, testTypeId, testLevelId,
                lifecycleStatus, automationStatus, priority, healthSignal,
                releaseVersion, page, size, sort);

        return cases.search(UUID.fromString(jwt.getSubject()), projectId, request)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
