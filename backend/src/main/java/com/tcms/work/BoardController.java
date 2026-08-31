package com.tcms.work;

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
 * The kanban board (plan §6l) — {@code GET /board}, and nothing else.
 *
 * <p>~~{@code WorkItemController}, at {@code /work-items}, with a {@code POST} that created a task
 * and a {@code PATCH} that moved a card.~~ <strong>→ superseded by 009 module M1 (D-a).</strong> The
 * path named a table that no longer exists, and creating and moving are {@code POST /issues} and
 * {@code POST /issues/{key}/transitions} — the tracker owns the row, so it owns the writes. The
 * <em>read</em> is unchanged in shape and in every one of its three board semantics, which is the
 * whole of D-a: {@code GET /work-items} was never work-item CRUD, it was the one operation serving
 * all three boards.
 *
 * <p>The caller comes from the token and the company from the context — neither is a parameter, for
 * the reasons {@code ProjectController} sets out: a {@code userId} parameter answers "what may
 * <em>that</em> user see", and a {@code companyId} parameter lets request input select the tenancy
 * discriminator, which is the one thing {@link com.tcms.tenancy.CompanyContext} exists to prevent
 * (ERD §0.1 point 2).
 *
 * <h2>Instance-scoped paths, not project-scoped ones</h2>
 *
 * <p>{@code /board}, not {@code /projects/{projectId}/board} — the shape the SPA has called since
 * Phase 1 (as {@code /work-items}) and the shape §6l's cross-project "My Work" board needs, which is
 * why it was chosen before that board existed. <strong>§83 is where the decision paid</strong>: the personal
 * board is this same operation with the parameter left off, and no path had to change. The project
 * therefore arrives as an <em>optional</em> query parameter on the read and in the body on the
 * create, and the gate is applied to <em>that</em> project rather than to a path segment.
 * <strong>§84 is where it paid a second time</strong>: §6l's team board is this same operation with
 * a {@code teamId}, so all three of §6l's boards are one route, one response shape and one sort.
 *
 * <p>~~{@code PATCH /work-items/{id}} has no project at all: the task's own {@code project_id} is
 * what its permission is checked against.~~ <strong>→ gone with the operation.</strong> The rule
 * survives one module over — {@code TransitionService.move} reads the issue by key and gates on
 * <em>its</em> project — and what did not survive is the 409 this controller answered for a derived
 * card, which named the action that does work. A synthetic {@code execution:{uuid}} is not an issue
 * key, so it is now a 404. That is a regression, it is recorded in the plan's built-and-unverified
 * list, and M2's board — where a drag actually happens — restores it.
 */
@RestController
@RequestMapping("/api/v1/board")
public class BoardController {

    private final BoardService board;

    public BoardController(BoardService board) {
        this.board = board;
    }

    /**
     * One project's board — or, with no {@code projectId}, §6l's cross-project "My Work" board.
     *
     * <p>{@code []} is a valid answer and means the project is reachable with nothing on its board —
     * the state of every freshly provisioned project. 404 means the project is not reachable, and
     * the two must not be conflated: an empty board for a project you cannot see would tell you it
     * exists.
     *
     * <h2>Omitting {@code projectId} requires {@code mine=true}, and the 400 is the feature</h2>
     *
     * <p>§6l describes three boards — personal, team and project — and the cross-project one is the
     * <em>personal</em> one: "everything assigned to me across all projects I can see". There is no
     * board that is every project's work unfiltered, and answering that request anyway would be the
     * one <em>response</em> in the product whose size is the company's rather than a project's or a
     * person's. It bounds the answer and not the work: {@code BoardService.personalBoard} assembles
     * the same rows either way and filters afterwards, which its own header records as the cost.
     *
     * <p>So it is refused rather than <em>narrowed</em>: forcing {@code mine} on silently would hand
     * back a list that is not what was asked for, and the client would have no way to tell that from
     * a company with only its caller's work in it. The sentence names the two requests that do work
     * (§53's rule for {@code unknownStatusProblem} one method down).
     *
     * <h2>{@code teamId} is §6l's third board, and it is a third SELECTOR rather than a third
     * route (§84)</h2>
     *
     * <p>§6l describes three boards and this operation now serves all three, which is what §83
     * demonstrated by removing a parameter rather than adding a path: they differ in <em>which cards
     * are kept</em> and in nothing else — same assembly, same shape, same sort. A
     * {@code /teams/{id}/work-items} route would be a second door onto one read model, and the
     * difference between the two would have to be maintained in two places for ever.
     *
     * <p><strong>{@code teamId} with {@code mine=true} is a 400.</strong> They select two different
     * boards — "this team's work" and "my work" — and answering the intersection would silently
     * hand back a fourth board nobody asked for: my own work, minus anything I hold that the team
     * does not, which is my own work again unless I am not on the team, in which case it is always
     * empty. Neither reading is useful and the client cannot tell which it got. Same family as the
     * refusal above, and for the same reason: the two requests that <em>do</em> work are named.
     *
     * <p><strong>{@code teamId} with {@code projectId} is legal</strong>, and it is not the same
     * mistake. §6l asks for a team board with "swimlanes by member", which is a team's work inside
     * one project — the two parameters narrow along different axes (who, and where) rather than
     * naming two boards, so their conjunction is a coherent request with an obvious answer. The
     * project's own 404 still applies, unchanged.
     */
    @GetMapping
    public ResponseEntity<Object> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "projectId", required = false) UUID projectId,
            @RequestParam(name = "teamId", required = false) UUID teamId,
            @RequestParam(name = "mine", required = false, defaultValue = "false") boolean mine) {

        UUID userId = UUID.fromString(jwt.getSubject());

        if (teamId != null) {
            if (mine) {
                return ResponseEntity.badRequest().body(twoBoardsProblem());
            }
            return board.teamBoard(userId, teamId, projectId)
                    .<ResponseEntity<Object>>map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        }

        if (projectId == null) {
            if (!mine) {
                return ResponseEntity.badRequest().body(unscopedBoardProblem());
            }
            return ResponseEntity.ok(board.personalBoard(userId));
        }

        return board.board(userId, projectId, mine)
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * The refusal for a board request that names neither a project nor a person.
     *
     * <p>400 rather than 404: nothing failed to resolve, and the caller is entitled to both of the
     * requests this sentence names — what they sent is simply not one of them.
     */
    private static ProblemDetail unscopedBoardProblem() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Choose a board");
        problem.setDetail("Ask for a project's board with projectId, or your own work across every "
                + "project with mine=true.");
        return problem;
    }

    /**
     * The refusal for a request that names two boards at once (§84).
     *
     * <p>{@link #unscopedBoardProblem}'s sibling and its exact inverse: that one is a request that
     * selects <em>no</em> board, this one selects two. Both are 400 and neither is narrowed to
     * whichever is cheaper to answer, because the client would not be able to tell which board it
     * received — a team board that happens to hold only the caller's cards and a personal board
     * restricted to a team are the same JSON.
     */
    private static ProblemDetail twoBoardsProblem() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Choose one board");
        problem.setDetail("Ask for a team's board with teamId, or your own work with mine=true — "
                + "not both. Add projectId to either one to narrow it to a single project.");
        return problem;
    }
}
