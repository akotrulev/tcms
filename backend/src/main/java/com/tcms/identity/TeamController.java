package com.tcms.identity;

import com.tcms.api.Problem;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The org chart — {@code /teams} and everything under it.
 *
 * <h2>Why the read moved here out of {@link AdminController}</h2>
 *
 * <p>{@code GET /teams} was that controller's fifth method, alongside the company's people, roles
 * and grants. Once the path family grew a create, an edit, a delete and two membership writes,
 * leaving the read behind would have put one URL's methods in two classes — the arrangement
 * {@code ProjectMemberController}'s header rejects in the other direction. One path family, one
 * controller; {@link AdminController} keeps the company directory.
 *
 * <p>The service is unchanged: the read is still {@link AdminDirectoryService}'s, so the org chart
 * has exactly one assembler and a write's response body cannot drift from the list the client
 * refetches a moment later.
 *
 * <h2>The read answers 200-with-an-empty-array and the writes answer 403</h2>
 *
 * <p>Not an inconsistency. The contract declares only {@code 200} on the read, and inventing a
 * status a generated client does not know about is the costlier divergence
 * ({@link AdminDirectoryService}'s header); it declares {@code 403} on all five writes, where a
 * caller who may not administer teams needs to be told so rather than shown an empty result.
 *
 * <p>The caller comes from the token and the company from the context — neither is a parameter, for
 * the reason every controller here repeats: a {@code companyId} parameter would let request input
 * select the tenancy discriminator (ERD §0.1 point 2).
 */
@RestController
@RequestMapping("/api/v1/teams")
public class TeamController {

    private final AdminDirectoryService directory;
    private final TeamWriteService writes;

    public TeamController(AdminDirectoryService directory, TeamWriteService writes) {
        this.directory = directory;
        this.writes = writes;
    }

    /** The org chart, with each team's people and the projects its grants reach. */
    @GetMapping
    public List<TeamView> teams(@AuthenticationPrincipal Jwt jwt) {
        return directory.listTeams(caller(jwt));
    }

    /**
     * Creates a team — <strong>the org chart's first writer</strong>, and
     * {@code company:teams}' first consumer in Java.
     */
    @PostMapping
    public ResponseEntity<Object> create(
            @AuthenticationPrincipal Jwt jwt, @RequestBody TeamInput request) {

        TeamWriteService.Outcome outcome = writes.create(caller(jwt), request);
        return switch (outcome) {
            case TeamWriteService.Outcome.Created created -> ResponseEntity
                    .created(URI.create("/api/v1/teams/" + created.view().id()))
                    .body(created.view());
            default -> nonCreating(outcome);
        };
    }

    /** Renames, re-describes or moves a team. A move is allowed here — see {@link TeamEdit}. */
    @PutMapping("/{teamId}")
    public ResponseEntity<Object> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID teamId,
            @RequestBody TeamInput request) {

        return nonCreating(writes.update(caller(jwt), teamId, request));
    }

    /** Deletes a team. 409 while it still holds grants or has children — see the write service. */
    @DeleteMapping("/{teamId}")
    public ResponseEntity<Object> delete(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID teamId) {

        return nonCreating(writes.delete(caller(jwt), teamId));
    }

    /** Puts somebody in a team, or changes whether they lead it. Idempotent — the URL is the state. */
    @PutMapping("/{teamId}/members/{userId}")
    public ResponseEntity<Object> putMember(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID teamId,
            @PathVariable UUID userId,
            @RequestBody TeamMemberInput request) {

        return nonCreating(writes.putMember(caller(jwt), teamId, userId, request));
    }

    /** Takes somebody out of a team. 204 whether they were in it or not. */
    @DeleteMapping("/{teamId}/members/{userId}")
    public ResponseEntity<Object> removeMember(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID teamId,
            @PathVariable UUID userId) {

        return nonCreating(writes.removeMember(caller(jwt), teamId, userId));
    }

    /**
     * Every outcome except {@code Created}, which is the one needing a {@code Location} the caller
     * of this method does not have.
     *
     * <p>Written as one mapping rather than five, because all five endpoints agree on what each
     * outcome means — and a per-endpoint copy is how one of them ends up answering 200 to a refusal.
     * The switch is exhaustive over a sealed type, so a new outcome is a compile error at this one
     * place.
     */
    private static ResponseEntity<Object> nonCreating(TeamWriteService.Outcome outcome) {
        return switch (outcome) {
            case TeamWriteService.Outcome.Created created -> ResponseEntity.ok(created.view());
            case TeamWriteService.Outcome.Updated updated -> ResponseEntity.ok(updated.view());
            case TeamWriteService.Outcome.Deleted ignored -> ResponseEntity.noContent().build();
            case TeamWriteService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            case TeamWriteService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(forbiddenProblem());
            case TeamWriteService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(validationProblem(invalid.errors()));
            case TeamWriteService.Outcome.StillInUse inUse ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(stillInUseProblem(inUse));
        };
    }

    private static UUID caller(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private static ProblemDetail validationProblem(Map<String, List<String>> errors) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Validation failed");
        problem.setDetail("Some of the details need correcting.");
        problem.setProperty("errors", errors);
        return problem;
    }

    private static ProblemDetail forbiddenProblem() {
        return Problem.forbiddenDetail(
                "You do not have permission to manage this company's teams.",
                "company:teams",
                Problem.Scope.COMPANY);
    }

    /**
     * The 409, carrying both counts.
     *
     * <p>The counts are the message: "it is in use" leaves an administrator clicking through screens
     * to find what, while "3 grants and 1 child team" says what to clear. The same shape the
     * classification value's live-children refusal uses.
     */
    private static ProblemDetail stillInUseProblem(TeamWriteService.Outcome.StillInUse inUse) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Team is still in use");
        problem.setDetail(
                "Clear what still points at this team before deleting it.");

        Map<String, List<String>> errors = new LinkedHashMap<>();
        if (inUse.grants() > 0) {
            errors.put("grants", List.of(
                    inUse.grants() == 1
                            ? "1 role grant is made through this team. Revoke it first — deleting "
                                    + "the team would revoke it silently."
                            : inUse.grants() + " role grants are made through this team. Revoke "
                                    + "them first — deleting the team would revoke them silently."));
        }
        if (inUse.children() > 0) {
            errors.put("children", List.of(
                    inUse.children() == 1
                            ? "1 team sits below this one. Move or delete it first."
                            : inUse.children() + " teams sit below this one. Move or delete them "
                                    + "first."));
        }
        problem.setProperty("errors", errors);
        return problem;
    }
}
