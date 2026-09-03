package com.tcms.tracker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.api.PatchBody;
import com.tcms.api.Problem;
import com.tcms.project.GateResult;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * {@code /issues} — the tracker's own surface.
 *
 * <p>The caller comes from the token and the company from the context; neither is a parameter, for
 * {@code ProjectController}'s reason — a {@code companyId} parameter lets request input select the
 * tenancy discriminator, which is the one thing {@link com.tcms.tenancy.CompanyContext} exists to
 * prevent (ERD §0.1 point 2).
 *
 * <h2>Instance-scoped paths, addressed by key</h2>
 *
 * <p>{@code /issues/{key}}, not {@code /projects/{projectId}/issues/{key}}. The key is what a person
 * types into a commit message and what every screen shows, and it is unique per <em>company</em> —
 * which is the only scope RLS lets a caller see into anyway. The gate is therefore applied to the
 * issue's own {@code project_id} after the row is read, exactly as {@code PATCH /work-items/{id}}
 * does, and an issue in an unreachable project answers 404 like a key that names nothing.
 *
 * <p>{@code POST} takes the project in the body instead, because there is no issue yet to read one
 * from.
 */
@RestController
@RequestMapping("/api/v1/issues")
public class IssueController {

    private final IssueService issues;
    private final ObjectMapper mapper;

    public IssueController(IssueService issues, ObjectMapper mapper) {
        this.issues = issues;
        this.mapper = mapper;
    }

    /**
     * One project's issues — the work table.
     *
     * <p>{@code []} is a valid answer and means the project is reachable with nothing in it, which is
     * the state of every freshly created project. 404 means the project is not reachable, and the two
     * must not be conflated: an empty list for a project you cannot see would tell you it exists.
     */
    @GetMapping
    public ResponseEntity<Object> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "projectId") UUID projectId,
            @RequestParam(name = "level", required = false) String level,
            @RequestParam(name = "epicKey", required = false) String epicKey,
            @RequestParam(name = "parentKey", required = false) String parentKey,
            @RequestParam(name = "statusId", required = false) List<UUID> statusIds,
            @RequestParam(name = "assigneeId", required = false) List<UUID> assigneeIds,
            @RequestParam(name = "unparented", required = false, defaultValue = "false")
            boolean unparented,
            @RequestParam(name = "agentEligible", required = false) Boolean agentEligible,
            @RequestParam(name = "sort", required = false) String sort) {

        IssueLevel parsed;
        try {
            parsed = level == null ? null : IssueLevel.valueOf(level.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            // Named rather than withheld: a level is public vocabulary already on every card in the
            // response, and a closed set the caller has to guess at is worse than one it can read.
            return ResponseEntity.badRequest().body(Problem.validation(Map.of("level", List.of(
                    "'" + level + "' is not a level. Valid values: EPIC, STORY, TASK."))));
        }

        IssueService.Filter filter = new IssueService.Filter(
                parsed, epicKey, parentKey, statusIds, assigneeIds, unparented, agentEligible,
                sort);

        return issues.list(UUID.fromString(jwt.getSubject()), projectId, filter)
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{key}")
    public ResponseEntity<Object> get(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        return issues.find(UUID.fromString(jwt.getSubject()), key)
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Creates an issue. The key is allocated server-side and is never a field on the request.
     *
     * <p>{@code Idempotency-Key} is optional and honoured: a retry answers <strong>200 with the
     * issue it already made</strong> rather than 201 with a second one — and no key is burned on the
     * retry, so a project with a flaky client does not grow gaps in its numbering that look like
     * deleted work.
     */
    @PostMapping
    public ResponseEntity<Object> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody IssueInput request) {

        GateResult<IssueService.Outcome> gate =
                issues.create(UUID.fromString(jwt.getSubject()), request, idempotencyKey);

        return switch (gate) {
            case GateResult.Unreachable<IssueService.Outcome> ignored ->
                    ResponseEntity.notFound().build();
            case GateResult.Denied<IssueService.Outcome> ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body(Problem.forbidden(
                                    "You do not have permission to create issues in this project."));
            case GateResult.Allowed<IssueService.Outcome> allowed -> answer(allowed.value());
        };
    }

    /**
     * Edits an issue's fields.
     *
     * <p>Bound as a node and then as the record, so the service can ask what was <em>sent</em>: the
     * record alone cannot answer it, which is why a delta edit against an endpoint without this used
     * to empty four columns and answer 200 (B1). A body that cannot be read as the record is caught
     * here rather than by a global handler, because the answer has to be this API's {@code Problem} —
     * a 400 whose body is a different shape is one no client parses.
     */
    @PatchMapping("/{key}")
    public ResponseEntity<Object> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String key,
            @RequestBody JsonNode body) {

        PatchBody patch = new PatchBody(body);
        IssueUpdate request;
        try {
            request = patch.as(mapper, IssueUpdate.class);
        } catch (PatchBody.MalformedBody unreadable) {
            return ResponseEntity.badRequest().body(Problem.validation(Map.of(
                    "body", List.of("The request body could not be read as an issue."))));
        }

        GateResult<IssueService.Outcome> gate =
                issues.update(UUID.fromString(jwt.getSubject()), key, request, patch);

        return switch (gate) {
            case GateResult.Unreachable<IssueService.Outcome> ignored ->
                    ResponseEntity.notFound().build();
            case GateResult.Denied<IssueService.Outcome> ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body(Problem.forbidden(
                                    "You do not have permission to edit issues in this project."));
            case GateResult.Allowed<IssueService.Outcome> allowed -> answer(allowed.value());
        };
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<Object> delete(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String key) {

        GateResult<IssueService.Outcome> gate =
                issues.delete(UUID.fromString(jwt.getSubject()), key);

        return switch (gate) {
            case GateResult.Unreachable<IssueService.Outcome> ignored ->
                    ResponseEntity.notFound().build();
            case GateResult.Denied<IssueService.Outcome> ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body(Problem.forbidden(
                                    "You do not have permission to delete issues in this project."));
            case GateResult.Allowed<IssueService.Outcome> allowed -> answer(allowed.value());
        };
    }

    /**
     * One mapping for all four writes, because the outcomes are one sealed set.
     *
     * <p>The exhaustive switch is what makes a new outcome a compile error here rather than a silent
     * fall-through to a 200 with the wrong body.
     */
    private ResponseEntity<Object> answer(IssueService.Outcome outcome) {
        return switch (outcome) {
            case IssueService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(Problem.validation(invalid.errors()));
            case IssueService.Outcome.Created created -> ResponseEntity
                    .created(URI.create("/api/v1/issues/" + created.view().key()))
                    .body(created.view());
            // No Location: nothing was created by THIS request, and a 200 carrying one reads as a
            // create to anything following the header rather than the status.
            case IssueService.Outcome.Replayed replayed -> ResponseEntity.ok(replayed.view());
            case IssueService.Outcome.Updated updated -> ResponseEntity.ok(updated.view());
            case IssueService.Outcome.Deleted ignored -> ResponseEntity.noContent().build();
            case IssueService.Outcome.HasChildren children -> ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(Problem.conflict("This issue has children",
                            "It holds " + children.count() + " other "
                                    + (children.count() == 1 ? "issue" : "issues")
                                    + ". Move or delete them first — deleting this one would "
                                    + "either take them with it or leave them without a parent, "
                                    + "and neither is what anybody asked for."));
        };
    }
}
