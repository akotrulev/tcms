package com.tcms.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The suite tree — the first {@code repository}-module endpoint, and the first one whose response
 * the SPA renders as a <em>screen</em> rather than as reference data.
 *
 * <p>A separate controller from {@code ProjectController} even though the path nests under
 * {@code /projects/{projectId}}: the path expresses the scope, not the owner. Suites are ERD §4
 * and this module, and putting them on the project controller would make that class the place
 * every project-scoped feature lands.
 *
 * <h2>The caller comes from the token, and the company from the context</h2>
 *
 * <p>Neither is a parameter, for the reasons {@code ProjectController} sets out at length: a
 * {@code userId} parameter answers "what may <em>that</em> user see", and a {@code companyId}
 * parameter lets request input select the tenancy discriminator — the single thing
 * {@link com.tcms.tenancy.CompanyContext} exists to prevent (ERD §0.1 point 2).
 *
 * <h2>404, never 403</h2>
 *
 * <p>A project that does not exist, one in another company, and one in this company the caller
 * holds no grant on all answer <strong>404 with no body</strong>. A 403 would confirm the id names
 * something real, which makes the endpoint an oracle for another client's project ids (§6t,
 * handoff §3 lesson 13). {@link SuiteService#findTree} collapses the three into an empty
 * {@link java.util.Optional} so this method has nothing to un-collapse.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/suites")
public class SuiteController {

    private final SuiteService suites;

    public SuiteController(SuiteService suites) {
        this.suites = suites;
    }

    /**
     * The project's suites, as a flat list carrying {@code parentId} — the client builds the tree.
     *
     * <p>{@code []} is a valid answer and means the project has no suites, which is the state of
     * every freshly provisioned project: {@code tcms_seed_project_defaults} seeds a configuration
     * and the status vocabulary, deliberately not a folder. Inventing a "Default" suite would be
     * inventing a client's structure, which is the same mistake as seeding their Feature tree.
     */
    @GetMapping
    public ResponseEntity<List<SuiteView>> tree(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {

        return suites.findTree(UUID.fromString(jwt.getSubject()), projectId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
