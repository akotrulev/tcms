package com.tcms.identity;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The company's administration surface — its people, its org chart, the role catalogue, and the
 * grants that decide who can see which project.
 *
 * <p>Six endpoints under one contract tag ({@code admin}) and therefore one controller, the same
 * arrangement {@code ProjectController} explains. They are not one <em>gate</em>: the reads answer
 * to {@code company:read} and the two writes answer to a permission chosen by the scope of the
 * grant being made. Every one of those verdicts is computed in {@link AdminDirectoryService} /
 * {@link RoleGrantService} and merely <em>rendered</em> here — an endpoint that decides "may they?"
 * for itself is how two implementations diverge (plan §6b).
 *
 * <h2>The caller comes from the token and the company from the context</h2>
 *
 * <p>Neither is a parameter. A {@code userId} parameter would answer "what may <em>that</em>
 * person administer", and a {@code companyId} parameter would let request input select the tenancy
 * discriminator — the one thing {@link com.tcms.tenancy.CompanyContext} exists to prevent (ERD
 * §0.1 point 2). {@code CompanyContextFilter} has already opened the session's company before any
 * method here runs.
 *
 * <h2>Why the reads answer 200 with an empty array rather than 403</h2>
 *
 * <p>The contract declares only {@code 200} on the four read paths, and a status a generated client
 * does not know about is the divergence that costs most. The gate is real —
 * {@code company:read} — and effectively unfailable, because a caller who is not a member cannot
 * open a session on this company at all. See {@link AdminDirectoryService}'s header.
 */
@RestController
@RequestMapping("/api/v1")
public class AdminController {

    private final AdminDirectoryService directory;
    private final RoleGrantService grants;

    public AdminController(AdminDirectoryService directory, RoleGrantService grants) {
        this.directory = directory;
        this.grants = grants;
    }

    /** The people in this company, by display name. */
    @GetMapping("/users")
    public List<UserView> users(@AuthenticationPrincipal Jwt jwt) {
        return directory.listUsers(caller(jwt));
    }

    /** The org chart, with each team's people and the projects its grants reach. */
    @GetMapping("/teams")
    public List<TeamView> teams(@AuthenticationPrincipal Jwt jwt) {
        return directory.listTeams(caller(jwt));
    }

    /** The six built-in roles, with their permissions expanded. */
    @GetMapping("/roles")
    public List<RoleView> roles(@AuthenticationPrincipal Jwt jwt) {
        return directory.listRoles(caller(jwt));
    }

    /**
     * The grants this caller may administer — which is exactly the set they could revoke.
     *
     * @param projectId narrows to one project. An id naming a project they cannot administer
     *                  answers {@code []}, not 404: the contract declares only 200 here, and "you
     *                  administer nothing there" is the honest answer for both an unreachable
     *                  project and one they merely hold no {@code project:members} on.
     */
    @GetMapping("/role-assignments")
    public List<RoleAssignmentView> roleAssignments(
            @AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) UUID projectId) {

        return directory.listRoleAssignments(caller(jwt), projectId);
    }

    /**
     * Grants a role to a person or a team — <strong>the write that ends "a provisioned company plus
     * hand-written SQL" for staffing.</strong>
     *
     * <p>{@code 201} for a new grant, {@code 200} for one that already existed. The second is not a
     * consolation prize: the caller's intent is "this person holds this role here", which is
     * already true, and a 409 would make an admin screen's obvious retry look like a failure while
     * a second row would give the same person two grants to revoke.
     *
     * <h2>The race, and why it is caught here rather than in an {@code @ExceptionHandler}</h2>
     *
     * <p>Two admins granting the same role to the same person at the same instant both find no
     * existing row and one of them loses at {@code uq_role_assignment_user} /
     * {@code uq_role_assignment_team} (V13). The loser's request is not a server fault and its
     * intent is satisfied — the grant exists — so it gets the same {@code 200} the idempotent path
     * would have produced a moment earlier. Recovering that needs the <em>request</em>, to find the
     * row that won, which an exception handler does not have; and it needs a fresh transaction,
     * because the one that lost is already rollback-only. Both are true here and neither is true
     * there. Not covered by the suite — reproducing it needs two interleaved transactions — which
     * is stated rather than implied, the same limitation {@code ProjectController.onKeyRace}
     * records.
     */
    @PostMapping("/role-assignments")
    public ResponseEntity<Object> grant(
            @AuthenticationPrincipal Jwt jwt, @RequestBody RoleGrantRequest request) {

        RoleGrantService.Grant outcome;
        try {
            outcome = grants.grant(caller(jwt), request);
        } catch (DataIntegrityViolationException lostTheRace) {
            return grants.existingGrant(caller(jwt), request)
                    .<ResponseEntity<Object>>map(ResponseEntity::ok)
                    // Nothing to return means the violation was not the duplicate-grant index —
                    // a broken FK, say — and swallowing it as a 200 would report success for a
                    // write that did not happen.
                    .orElseThrow(() -> lostTheRace);
        }

        return switch (outcome) {
            case RoleGrantService.Grant.Created created -> ResponseEntity
                    .created(location(created.view().id()))
                    .body(created.view());
            case RoleGrantService.Grant.Existing existing -> ResponseEntity.ok(existing.view());
            case RoleGrantService.Grant.Invalid invalid ->
                    ResponseEntity.badRequest().body(validationProblem(invalid.errors()));
            case RoleGrantService.Grant.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(forbiddenProblem());
            case RoleGrantService.Grant.NotFound ignored -> ResponseEntity.notFound().build();
        };
    }

    /**
     * Revokes a grant. {@code 204} whether it was there or not — see {@link RoleGrantService#revoke}
     * for the four answers and which of them a caller can act on.
     */
    @DeleteMapping("/role-assignments/{assignmentId}")
    public ResponseEntity<Object> revoke(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID assignmentId) {

        return switch (grants.revoke(caller(jwt), assignmentId)) {
            case RoleGrantService.Revoke.Revoked ignored -> ResponseEntity.noContent().build();
            case RoleGrantService.Revoke.Invalid invalid ->
                    ResponseEntity.badRequest().body(validationProblem(invalid.errors()));
            case RoleGrantService.Revoke.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(forbiddenProblem());
            case RoleGrantService.Revoke.NotFound ignored -> ResponseEntity.notFound().build();
        };
    }

    /**
     * A revoke that would have left the caller unable to revoke anything — answered as a field
     * error on {@code assignmentId}, because the control that caused it is the row's own button.
     *
     * <p>Handled here rather than inside the service because the refusal <em>is</em> the rollback:
     * {@link RoleGrantService.SelfLockout} escapes the {@code @Transactional} method so Spring
     * unwinds the delete and its audit row, and a service that caught its own exception would have
     * committed the thing it is refusing.
     */
    @ExceptionHandler(RoleGrantService.SelfLockout.class)
    public ResponseEntity<Object> onSelfLockout(RoleGrantService.SelfLockout ignored) {
        return ResponseEntity.badRequest().body(validationProblem(Map.of(
                "assignmentId", List.of(
                        "This grant is what lets you manage access here, so removing it would "
                                + "lock you out. Ask another administrator to remove it."))));
    }

    private static UUID caller(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    /**
     * The grant's own URL, built from this controller's mapping rather than from a request-relative
     * builder: {@code fromCurrentRequest} would echo whatever host or proxy prefix the caller sent,
     * which is a header-controlled value in a response.
     */
    private static URI location(UUID assignmentId) {
        return URI.create("/api/v1/role-assignments/" + assignmentId);
    }

    private static ProblemDetail validationProblem(Map<String, List<String>> errors) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Validation failed");
        problem.setDetail("Check the highlighted fields.");
        problem.setProperty("errors", errors);
        return problem;
    }

    private static ProblemDetail forbiddenProblem() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Not allowed");
        // Never the permission key: a key is not actionable by someone who cannot grant it to
        // themselves, and naming it publishes the catalogue to exactly the caller just refused.
        problem.setDetail("You do not have permission to manage access here.");
        return problem;
    }
}
