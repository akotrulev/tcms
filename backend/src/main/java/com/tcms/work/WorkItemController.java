package com.tcms.work;

import com.tcms.project.GateResult;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The kanban board (plan §6l).
 *
 * <p>The caller comes from the token and the company from the context — neither is a parameter, for
 * the reasons {@code ProjectController} sets out: a {@code userId} parameter answers "what may
 * <em>that</em> user see", and a {@code companyId} parameter lets request input select the tenancy
 * discriminator, which is the one thing {@link com.tcms.tenancy.CompanyContext} exists to prevent
 * (ERD §0.1 point 2).
 *
 * <h2>Instance-scoped paths, not project-scoped ones</h2>
 *
 * <p>{@code /work-items}, not {@code /projects/{projectId}/work-items} — the shape the SPA has
 * called since Phase 1 and the shape §6l's cross-project "My Work" board eventually needs. The
 * project therefore arrives as a required query parameter on the read and in the body on the create,
 * and the gate is applied to <em>that</em> project rather than to a path segment.
 *
 * <p>{@code PATCH /work-items/{id}} has no project at all: the task's own {@code project_id} is what
 * its permission is checked against, which {@code BoardService.move} does after reading the row. RLS
 * has already bounded the read to the caller's company before that.
 */
@RestController
@RequestMapping("/api/v1/work-items")
public class WorkItemController {

    private final BoardService board;

    public WorkItemController(BoardService board) {
        this.board = board;
    }

    /**
     * One project's board.
     *
     * <p>{@code []} is a valid answer and means the project is reachable with nothing on its board —
     * the state of every freshly provisioned project. 404 means the project is not reachable, and
     * the two must not be conflated: an empty board for a project you cannot see would tell you it
     * exists.
     */
    @GetMapping
    public ResponseEntity<List<WorkItemView>> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "projectId") UUID projectId,
            @RequestParam(name = "mine", required = false, defaultValue = "false") boolean mine) {

        return board.board(UUID.fromString(jwt.getSubject()), projectId, mine)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Creates a task. Always a {@code TASK} — see {@code WorkItemWriteRequest} on why {@code kind}
     * is not an input.
     *
     * <p>{@code Idempotency-Key} is optional and honoured (V17) — a retry answers <strong>200 with
     * the task it already made</strong> rather than 201 with a second card. §53 shipped this
     * endpoint without the header and wrote the reason into the contract: declaring it with no
     * column behind it would put it in the reviewed spec, in {@code schema.d.ts} and in
     * autocomplete while the server ignored it, so a client would believe its retries were
     * deduplicated when every one created another card. The declaration and the column land in the
     * same slice, which is the ordering that makes the promise true.
     */
    @PostMapping
    public ResponseEntity<Object> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody WorkItemWriteRequest request) {

        GateResult<BoardService.CreateOutcome> gate =
                board.create(UUID.fromString(jwt.getSubject()), request, idempotencyKey);

        return switch (gate) {
            case GateResult.Unreachable<BoardService.CreateOutcome> ignored ->
                    ResponseEntity.notFound().build();
            case GateResult.Denied<BoardService.CreateOutcome> ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(forbiddenProblem("create tasks"));
            case GateResult.Allowed<BoardService.CreateOutcome> allowed -> switch (allowed.value()) {
                case BoardService.CreateOutcome.Invalid invalid ->
                        ResponseEntity.badRequest().body(validationProblem(invalid.errors()));
                case BoardService.CreateOutcome.Created created -> ResponseEntity
                        .created(URI.create("/api/v1/work-items/" + created.view().id()))
                        .body(created.view());
                // No Location: nothing was created by THIS request, and a 200 carrying one would
                // read as a create to anything following the header rather than the status.
                case BoardService.CreateOutcome.Replayed replayed ->
                        ResponseEntity.ok(replayed.view());
            };
        };
    }

    /**
     * Moves a card to another column.
     *
     * <p>{@code workItemId} is a {@code String} rather than a {@code UUID}, and that is required
     * rather than lax: a derived card's id is {@code execution:{uuid}}, so binding this as a
     * {@code UUID} would make Spring answer <strong>400 Bad Request</strong> before any of this
     * module's code ran — and the SPA would report "check your input" for a card the user simply
     * cannot move. The refusal has to be reachable to be explainable, and
     * {@code BoardService.move} answers it with a 409 that names what to do instead.
     */
    @PatchMapping("/{workItemId}")
    public ResponseEntity<Object> move(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String workItemId,
            @RequestBody WorkItemStatusUpdateRequest request) {

        WorkItemStatus target;
        try {
            target = WorkItemStatus.valueOf(request.status().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException unknown) {
            return ResponseEntity.badRequest().body(unknownStatusProblem(request.status()));
        }

        GateResult<BoardService.MoveOutcome> gate =
                board.move(UUID.fromString(jwt.getSubject()), workItemId, target);

        return switch (gate) {
            case GateResult.Unreachable<BoardService.MoveOutcome> ignored ->
                    ResponseEntity.notFound().build();
            case GateResult.Denied<BoardService.MoveOutcome> ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(forbiddenProblem("move cards"));
            case GateResult.Allowed<BoardService.MoveOutcome> allowed -> switch (allowed.value()) {
                case BoardService.MoveOutcome.NotStored ignored ->
                        ResponseEntity.status(HttpStatus.CONFLICT).body(notStoredProblem());
                case BoardService.MoveOutcome.NotAllowed notAllowed ->
                        ResponseEntity.status(HttpStatus.CONFLICT).body(notAllowedProblem(notAllowed));
                case BoardService.MoveOutcome.Moved moved -> ResponseEntity.ok(moved.view());
            };
        };
    }

    /**
     * The refusal a derived card gets, and the most user-facing sentence in this module.
     *
     * <p>It names the action that <em>does</em> work rather than restating the rule. "This card
     * mirrors an execution" explains the product's design to somebody who was trying to drag a box;
     * "update the execution and the card follows" tells them where to go. §6l's whole bet is that
     * users accept the constraint once they can see it is not arbitrary, and this is where that is
     * either delivered or not.
     */
    private static ProblemDetail notStoredProblem() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("That card can't be moved here");
        problem.setDetail("This card mirrors an execution or a case rather than being a task of its "
                + "own, so its column comes from the thing it mirrors. Update that, and the card "
                + "follows.");
        return problem;
    }

    private static ProblemDetail notAllowedProblem(BoardService.MoveOutcome.NotAllowed refused) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("That move isn't allowed");
        problem.setDetail("This card can't move from " + refused.from() + " to " + refused.to()
                + ".");
        return problem;
    }

    /**
     * Names the four, for {@code PlanController#unknownPolicyProblem}'s reason.
     *
     * <p>Not in tension with §32's rule against naming permission keys: a board column is public
     * vocabulary already on every card in the response and in the SPA's own header row, and
     * withholding it would leave the caller guessing at a closed set.
     */
    private static ProblemDetail unknownStatusProblem(String rejected) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Unknown column");
        problem.setDetail("'" + rejected + "' is not a board column. Valid values: "
                + java.util.Arrays.stream(WorkItemStatus.values())
                        .map(Enum::name)
                        .collect(java.util.stream.Collectors.joining(", "))
                + ".");
        return problem;
    }

    private static ProblemDetail validationProblem(Map<String, List<String>> errors) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Validation failed");
        problem.setDetail("Check the highlighted fields.");
        problem.setProperty("errors", new HashMap<>(errors));
        return problem;
    }

    /**
     * The refusal, naming no permission key.
     *
     * <p>§32's rule: a key is not actionable by somebody who cannot grant it to themselves, and
     * naming it publishes the catalogue to exactly the caller not entitled to it. The sentence names
     * the action instead, which is what the reader was trying to do.
     */
    private static ProblemDetail forbiddenProblem(String action) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Not allowed");
        problem.setDetail("You do not have permission to " + action + " in this project.");
        return problem;
    }
}
