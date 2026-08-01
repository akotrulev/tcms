package com.tcms.identity;

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
 * {@code GET /projects/{projectId}/members} — the people a project-scoped assignment may name.
 *
 * <h2>Why this is a project path served from {@code identity} rather than an admin path</h2>
 *
 * <p>{@link AdminController} maps {@code /api/v1} and serves {@code /users}, {@code /teams} and
 * {@code /roles} — the company directory. This is a different question with a different gate and a
 * different answer shape, so it gets its own controller on its own path rather than a sixth method
 * there. The arrangement is the codebase's norm rather than an exception: {@code CaseController},
 * {@code DashboardController} and {@code SuiteHistoryController} all map project-scoped paths from
 * modules other than {@code project}.
 *
 * <h2>404, not 200-with-an-empty-array</h2>
 *
 * <p>The admin reads answer an empty list to a caller who fails their gate, because the contract
 * declares only {@code 200} on those four paths and inventing a status a generated client does not
 * know about is the costlier divergence ({@link AdminDirectoryService}'s header). This path declares
 * {@code 404} and means it: every project-scoped read in the contract answers 404 for a project the
 * caller cannot reach, and §6t requires an unreachable project and a non-existent one to be
 * indistinguishable. An empty member list for a project you cannot see would tell you it exists.
 *
 * <p>The caller comes from the token and the company from the context — neither is a parameter, for
 * the reason every controller here repeats: a {@code companyId} parameter would let request input
 * select the tenancy discriminator (ERD §0.1 point 2).
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/members")
public class ProjectMemberController {

    private final ProjectMembershipService members;

    public ProjectMemberController(ProjectMembershipService members) {
        this.members = members;
    }

    /**
     * The project's assignable people, by display name.
     *
     * <p>{@code []} is a valid answer and is not the same as a 404: it means the project is
     * reachable and the caller is the only person staffed on it — which is the state of a project
     * created a minute ago by the company's first admin.
     */
    @GetMapping
    public ResponseEntity<List<UserRefView>> list(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {

        return members.membersOf(UUID.fromString(jwt.getSubject()), projectId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
