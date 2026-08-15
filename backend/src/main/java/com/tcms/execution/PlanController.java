package com.tcms.execution;

import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test plans.
 *
 * <p>The caller comes from the token and the company from the context — neither is a parameter, for
 * the reasons {@code ProjectController} sets out: a {@code userId} parameter answers "what may
 * <em>that</em> user see", and a {@code companyId} parameter lets request input select the tenancy
 * discriminator, which is the one thing {@link com.tcms.tenancy.CompanyContext} exists to prevent
 * (ERD §0.1 point 2).
 *
 * <p>404, never 403, on both reads: an absent project, one in another company, one this caller holds
 * no grant on, and a plan in a sibling project all collapse into an empty {@link java.util.Optional}
 * before this class sees them, so there is nothing here to un-collapse (§6t).
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/plans")
public class PlanController {

    private final PlanService plans;
    private final PlanCreateService creates;
    private final PlanUpdateService updates;
    private final PlanStatisticsService statistics;
    private final PlanStatusService lifecycle;
    private final PlanDeleteService deletes;
    private final PlanCloneService clones;

    public PlanController(
            PlanService plans,
            PlanCreateService creates,
            PlanUpdateService updates,
            PlanStatisticsService statistics,
            PlanStatusService lifecycle,
            PlanDeleteService deletes,
            PlanCloneService clones) {

        this.clones = clones;
        this.plans = plans;
        this.creates = creates;
        this.updates = updates;
        this.statistics = statistics;
        this.lifecycle = lifecycle;
        this.deletes = deletes;
    }

    /**
     * The project's plans, newest first.
     *
     * <p>{@code []} is a valid answer and means the project has no plans — the state of every
     * freshly provisioned project, since {@code tcms_seed_project_defaults} seeds a configuration
     * and a status vocabulary and deliberately not a plan.
     */
    @GetMapping
    public ResponseEntity<List<TestPlanView>> list(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {

        return plans.list(UUID.fromString(jwt.getSubject()), projectId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{planId}")
    public ResponseEntity<TestPlanView> detail(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID planId) {

        return plans.findOne(UUID.fromString(jwt.getSubject()), projectId, planId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Where the plan stands right now (plan §6p).
     *
     * <p>{@code aggregation} is bound as a {@code String} and parsed here rather than declared as the
     * enum. Spring's own converter would answer a bare 400 whose body names the Java type and the
     * rejected value, which publishes an internal class name and tells the caller nothing about what
     * it should have sent; the five valid policies are worth naming, since choosing between them is
     * the whole point of the parameter.
     */
    @GetMapping("/{planId}/statistics")
    public ResponseEntity<Object> statistics(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID planId,
            @RequestParam(name = "aggregation", required = false) String aggregation) {

        AggregationPolicy policy;
        try {
            policy = aggregation == null || aggregation.isBlank()
                    ? AggregationPolicy.DEFAULT
                    : AggregationPolicy.valueOf(aggregation.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return ResponseEntity.badRequest().body(unknownPolicyProblem(aggregation));
        }

        return statistics.statistics(UUID.fromString(jwt.getSubject()), projectId, planId, policy)
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Creates a plan. {@code TP-n} is allocated server-side; the caller never supplies a key.
     *
     * <p>{@code Idempotency-Key} is optional and honoured — a retry answers <strong>200 with the
     * plan it already made</strong> rather than 201 with a second one. §29 built this for cases
     * after finding that the SPA had been sending the header since Phase 1 while nothing read it;
     * here the header is read from the first day the endpoint exists, so the two sides cannot
     * diverge in the meantime.
     */
    @PostMapping
    public ResponseEntity<Object> create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody PlanWriteRequest request) {

        PlanCreateService.Outcome outcome = creates.create(
                UUID.fromString(jwt.getSubject()), projectId, request, idempotencyKey);

        return switch (outcome) {
            case PlanCreateService.Outcome.Created created -> ResponseEntity
                    .created(URI.create("/api/v1/projects/" + projectId + "/plans/"
                            + created.view().id()))
                    .body(created.view());
            // No Location: nothing was created by THIS request, and a 200 carrying one would read
            // as a create to anything following the header rather than the status.
            case PlanCreateService.Outcome.Replayed replayed ->
                    ResponseEntity.ok(replayed.view());
            case PlanCreateService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            case PlanCreateService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(forbiddenProblem());
            case PlanCreateService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(validationProblem(invalid.errors()));
        };
    }

    /**
     * Clones a plan — plan §6v, decision round 3 #59.
     *
     * <p>Unlike its two siblings this is <strong>not</strong> the create endpoint with a derived
     * body, and {@link PlanCloneService}'s header says why: {@code TestPlanInput} cannot express a
     * section, a tag arm, either prose field or either criteria list, so a clone routed through it
     * would drop most of what a plan detail consists of and report success. The clone shares the
     * create's <em>orchestration</em> instead, so the key, the {@code DRAFT} status and version 1
     * still have exactly one implementation.
     *
     * <p>The statuses are therefore the create's: 201 with a {@code Location}, 200 for a replay with
     * none, 400 carrying {@link PlanEdit}'s own field errors, and 404 for an unreachable project or
     * a plan that is not in it.
     */
    @PostMapping("/{planId}/clone")
    public ResponseEntity<Object> clonePlan(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID planId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody PlanCloneRequest request) {

        PlanCloneService.Outcome outcome = clones.clonePlan(
                UUID.fromString(jwt.getSubject()), projectId, planId, request, idempotencyKey);

        return switch (outcome) {
            case PlanCloneService.Outcome.Created created -> ResponseEntity
                    .created(URI.create("/api/v1/projects/" + projectId + "/plans/"
                            + created.view().id()))
                    .body(created.view());
            case PlanCloneService.Outcome.Replayed replayed ->
                    ResponseEntity.ok(replayed.view());
            case PlanCloneService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            case PlanCloneService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(forbiddenProblem());
            case PlanCloneService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(validationProblem(invalid.errors()));
        };
    }

    /**
     * Edits the plan's content — name, description, milestone, scope and the two criteria lists.
     *
     * <p><strong>{@code PATCH}, where the status beside it is a {@code PUT}</strong>, and the two are
     * different acts rather than an inconsistency: a status request names an absolute target state
     * the server can reach from anywhere, while this one is a delta against a plan the client read.
     * Which is also why this one takes {@code If-Match} and that one does not.
     *
     * <p>{@code If-Match} carries the {@code lockVersion} the client read.
     * {@code CaseController}'s decision, unchanged: a body field would put a concurrency token
     * inside the document being edited, where a form that round-trips its own payload would keep
     * sending a stale one for ever.
     */
    @PatchMapping("/{planId}")
    public ResponseEntity<Object> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID planId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody PlanUpdateRequest request) {

        Long expected;
        try {
            expected = parseIfMatch(ifMatch);
        } catch (NumberFormatException notANumber) {
            return ResponseEntity.badRequest().body(validationProblem(Map.of("If-Match",
                    List.of("Send the plan's lockVersion as the If-Match header, or omit it."))));
        }

        PlanUpdateService.Outcome outcome = updates.update(
                UUID.fromString(jwt.getSubject()), projectId, planId, request, expected);

        return switch (outcome) {
            case PlanUpdateService.Outcome.Updated updated -> ResponseEntity.ok(updated.view());
            case PlanUpdateService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            case PlanUpdateService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(editForbiddenProblem());
            case PlanUpdateService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(validationProblem(invalid.errors()));
            case PlanUpdateService.Outcome.Stale ignored ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(staleProblem());
        };
    }

    /**
     * Submits the plan for review, approves it, or withdraws it back to draft.
     *
     * <p><strong>{@code PUT}, not {@code PATCH}</strong>, and a sub-resource rather than a field on
     * the plan: the status <em>is</em> the resource being replaced and the request names an absolute
     * target. It mirrors {@code PUT /runs/{runId}/status} and {@code PUT /doc-pages/{pageId}/status}
     * exactly, which is the precedent to follow rather than a third shape for one act.
     *
     * <p>There is no {@code If-Match}, and that is a decision — see {@link PlanStatusService}.
     */
    @PutMapping("/{planId}/status")
    public ResponseEntity<Object> status(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID planId,
            @RequestBody PlanStatusRequest request) {

        PlanStatusService.Outcome outcome =
                lifecycle.setStatus(UUID.fromString(jwt.getSubject()), projectId, planId, request);

        return switch (outcome) {
            case PlanStatusService.Outcome.Updated updated -> ResponseEntity.ok(updated.view());
            case PlanStatusService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            case PlanStatusService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(statusForbiddenProblem());
            case PlanStatusService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(validationProblem(invalid.errors()));
            case PlanStatusService.Outcome.Refused refused ->
                    ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(refusedProblem(refused.message()));
        };
    }

    /**
     * Deletes the plan, unless runs have been executed against it.
     *
     * <p>204 with no body on success, and the 409 carries its count — see {@link PlanDeleteService}
     * for why the refusal is the interesting half.
     */
    @DeleteMapping("/{planId}")
    public ResponseEntity<Object> delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID planId) {

        PlanDeleteService.Outcome outcome =
                deletes.delete(UUID.fromString(jwt.getSubject()), projectId, planId);

        return switch (outcome) {
            case PlanDeleteService.Outcome.Deleted ignored -> ResponseEntity.noContent().build();
            case PlanDeleteService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            case PlanDeleteService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(deleteForbiddenProblem());
            case PlanDeleteService.Outcome.Refused refused ->
                    ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(refusedProblem(refused.message()));
        };
    }

    /**
     * The true race, as opposed to the stale editor {@code If-Match} catches.
     *
     * <p>{@code TestPlan.lockVersion} became a real {@code @Version} in this slice, so two saves
     * that both passed the header check and then interleaved now raise this rather than silently
     * last-write-winning. It has to be a handler rather than a {@code catch} inside the service,
     * for {@code CaseController#onWriteRace}'s reason: the transaction is rollback-only by the time
     * the exception exists, so returning a tidy outcome from inside it would fail again at commit.
     *
     * <p>It covers the status endpoint too, and that is the right answer there as well: two leads
     * approving at the same instant now get one 200 and one 409, and the second one's retry reads
     * {@code APPROVED} and answers 200 unchanged — which is the outcome §73 said they both want,
     * reached in two steps instead of one.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Object> onWriteRace(OptimisticLockingFailureException race) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(staleProblem());
    }

    /**
     * Names the five, unlike the permission refusal below.
     *
     * <p>The two are not in tension: §32's rule is that a <em>permission key</em> must not be named
     * to a caller who cannot grant it to themselves, because that publishes the catalogue. An
     * aggregation policy is public vocabulary already on every response and in the SPA's own
     * dropdown, and withholding it would leave the caller guessing at a closed set.
     */
    private static ProblemDetail unknownPolicyProblem(String rejected) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Unknown aggregation policy");
        problem.setDetail("'" + rejected + "' is not an aggregation policy. Valid values: "
                + Arrays.stream(AggregationPolicy.values())
                        .map(Enum::name)
                        .collect(Collectors.joining(", "))
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
    private static ProblemDetail forbiddenProblem() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Not allowed");
        problem.setDetail("You do not have permission to create test plans in this project.");
        return problem;
    }

    /**
     * §32 again — worded for the act and naming no key.
     *
     * <p>One sentence for both keys, and that is deliberate: the gate a caller failed depends on
     * which move they asked for, so a message that named it would have to say either "editing plans"
     * or "approving plans" — and telling a Test Engineer which of the two they are missing is how a
     * refusal becomes a map of the catalogue. The sentence names the act instead.
     */
    private static ProblemDetail statusForbiddenProblem() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Not allowed");
        problem.setDetail("You do not have permission to make this change to the plan's status.");
        return problem;
    }

    /** §32 again, worded for the act. Separate from the status refusal because the acts differ. */
    private static ProblemDetail editForbiddenProblem() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Not allowed");
        problem.setDetail("You do not have permission to edit test plans in this project.");
        return problem;
    }

    /**
     * A lost update — the {@code If-Match} the client sent is not the stored version.
     *
     * <p>Deliberately worded apart from {@link #refusedProblem}, which is also a 409:
     * {@code repository.ApiProblem} draws the same distinction and says why. "Reload and reapply"
     * is exactly the right advice here and exactly the wrong advice for a plan refused because it
     * is closed — reloading that one changes nothing.
     */
    private static ProblemDetail staleProblem() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Someone else saved first");
        problem.setDetail(
                "Reload the plan to see the newer version, then reapply your change.");
        return problem;
    }

    /**
     * ~~"Deleting a test plan needs a test lead."~~ — <strong>wrong since {@code V51}</strong>, which
     * granted {@code plan:delete} to Test Engineer, and corrected at §210 rather than at §207 where
     * it should have been. The only caller who reaches this now is one who cannot write in the
     * project at all, and sending them to ask for a lead's role names a tier that would not have
     * been the answer.
     *
     * <p>§32's rule is unchanged and is why the replacement still names no key: a permission key is
     * not actionable by someone who cannot grant it to themselves, and naming it publishes the
     * catalogue to exactly the caller not entitled to it.
     */
    private static ProblemDetail deleteForbiddenProblem() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Not allowed");
        problem.setDetail("You do not have permission to delete test plans in this project.");
        return problem;
    }

    /**
     * A move the plan's own state refuses, and the delete a live run refuses.
     *
     * <p>The sentence comes from the domain rather than being written here, for the reason
     * {@code RunController} gives: it is raised from two methods and computed in two classes, and a
     * copy per controller method is how one rule ends up explained two ways.
     */
    private static ProblemDetail refusedProblem(String message) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Not possible right now");
        problem.setDetail(message);
        return problem;
    }

    /**
     * Reads {@code If-Match}, tolerating the quoted form — {@code CaseController}'s reader, and its
     * reasoning: HTTP defines the header as carrying entity tags, which are quoted, so a client
     * library that formats it correctly is not wrong. A weak tag ({@code W/"7"}) is still refused,
     * because weak comparison is explicitly the wrong semantics for a lost-update check.
     */
    private static Long parseIfMatch(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String value = header.trim();
        if (value.length() > 1 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return Long.valueOf(value);
    }
}
