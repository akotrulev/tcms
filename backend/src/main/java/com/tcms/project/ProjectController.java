package com.tcms.project;

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
 * The project list, one project, and that project's two vocabularies — the first endpoints past
 * authentication.
 *
 * <p>Everything the SPA does after sign-in starts here: the picker calls
 * {@code GET /projects}, and every other route is scoped under a project id it chose from
 * that response.
 *
 * <p>All four are one controller because all four are one contract tag and one gate. The gate
 * itself lives in {@link ProjectService#readVisible} and is <em>called</em>, never restated — a
 * project-scoped endpoint that answers "may they?" for itself is how the two implementations
 * diverge (plan §6b).
 *
 * <h2>The caller comes from the token, and the company from the context</h2>
 *
 * <p>Neither is a parameter, and both would be convenient as one. A {@code userId} parameter
 * would answer "what may <em>that</em> user see" — the bypass {@code MeController} documents.
 * A {@code companyId} parameter would let request input select the tenancy discriminator,
 * which is the single thing {@link com.tcms.tenancy.CompanyContext} exists to prevent (ERD
 * §0.1 point 2). The company is established by {@code CompanyContextFilter} from the session's
 * {@code co} claim before this controller runs.
 *
 * <h2>404, never 403</h2>
 *
 * <p>A project that does not exist, one in another company, and one in this company the caller
 * holds no grant on all answer <strong>404 with no body</strong>. A 403 would confirm the id
 * names something real, which makes the endpoint an oracle for another client's project ids
 * (§6t, handoff §3 lesson 13). {@link ProjectService#findVisible} collapses the three cases
 * into an empty {@link java.util.Optional} so this method has nothing to un-collapse — the
 * same arrangement as {@code InvitationController}.
 */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projects;
    private final ProjectConfigurationService configuration;

    public ProjectController(ProjectService projects, ProjectConfigurationService configuration) {
        this.projects = projects;
        this.configuration = configuration;
    }

    /**
     * Projects visible to the caller — possibly none.
     *
     * <p>An empty array is a correct and expected answer, not an error: a member invited to a
     * company and granted no project yet sees exactly this. The SPA renders it as the "no
     * projects" empty state rather than as a failure.
     */
    @GetMapping
    public List<ProjectView> list(@AuthenticationPrincipal Jwt jwt) {
        return projects.listVisible(UUID.fromString(jwt.getSubject()));
    }

    @GetMapping("/{projectId}")
    public ResponseEntity<ProjectView> detail(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {

        return projects.findVisible(UUID.fromString(jwt.getSubject()), projectId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * The classification vocabularies that apply to this project (plan §6d).
     *
     * <p>Company-level and project-level dimensions in one array: Test Type, Test Level and
     * Execution Phase belong to the company so a client's projects stay comparable, Feature belongs
     * to the project. The distinction is deliberately invisible on the wire — a consumer picking
     * values has no use for it, and it is the field a future author would try to <em>send</em>.
     *
     * <p>{@code []} is a valid answer and means the project has no dimensions, which is what a
     * company provisioned before the vocabulary existed looks like. An unreachable project is a
     * 404, exactly as on {@link #detail} — same gate, same reason.
     */
    @GetMapping("/{projectId}/classification")
    public ResponseEntity<List<ClassificationDimensionView>> classification(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {

        return configuration.findClassification(UUID.fromString(jwt.getSubject()), projectId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * This project's configurable execution statuses — the six defaults, plus whatever it added.
     *
     * <p>Project-scoped, unlike the classification vocabularies above: two client products
     * legitimately differ in status vocabulary while "what counts as a Performance test" should not
     * (ERD §3). Reporting reads each status's {@code kind}, never its name.
     */
    @GetMapping("/{projectId}/statuses")
    public ResponseEntity<List<CaseStatusView>> statuses(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {

        return configuration.findStatuses(UUID.fromString(jwt.getSubject()), projectId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
