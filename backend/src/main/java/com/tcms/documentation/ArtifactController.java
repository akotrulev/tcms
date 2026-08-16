package com.tcms.documentation;

import java.net.URI;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The artifact register (plan §6n) — the {@code documentation} module's second controller.
 *
 * <p>The caller comes from the token and the company from the context, neither as a parameter, for
 * the reasons {@code ProjectController} sets out: a {@code userId} parameter answers "what may
 * <em>that</em> user see", and a {@code companyId} parameter lets request input select the tenancy
 * discriminator — the one thing {@link com.tcms.tenancy.CompanyContext} exists to prevent (ERD §0.1
 * point 2).
 *
 * <p>Both query parameters are <strong>bound</strong>, which is §50's rule rather than a detail: a
 * parameter the contract declares and no handler binds is worse than one the client invents,
 * because the reviewed spec, the generated types and autocomplete all agree with the caller while
 * the server ignores it. {@code scope} defaults to {@code PROJECT} here and in the contract, and the
 * combination that has no answer — {@code PLAN} without a plan, or a plan without {@code PLAN} — is
 * a 400 rather than a quiet fallback to the project's register.
 *
 * <h2>404, never 403, for anything about reachability</h2>
 *
 * <p>A project that does not exist, one in another company, and one in this company the caller holds
 * no grant on all answer <strong>404 with no body</strong>. A 403 would confirm the id names
 * something real, which makes the endpoint an oracle for another client's ids (§6t). The 403 below
 * is only ever about {@code artifact:edit} on a project the caller can already read — including the
 * scope-qualified refusal, where the caller holds the permission and not for that scope.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/artifacts")
public class ArtifactController {

    private final ArtifactService register;
    private final ArtifactWriteService writes;

    public ArtifactController(ArtifactService register, ArtifactWriteService writes) {
        this.register = register;
        this.writes = writes;
    }

    /**
     * The project's register, or a plan's with the project's inherited into it.
     *
     * <p>{@code []} means the project has registered nothing, which is the state of every freshly
     * provisioned one. Nothing seeds a first artifact: plan §6n's "seed the right empty slots from a
     * template" is a project-settings feature, and inventing a client's document set is the same
     * mistake as seeding their Feature tree.
     */
    @GetMapping
    public ResponseEntity<Object> list(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestParam(name = "scope", defaultValue = "PROJECT") ArtifactScope scope,
            @RequestParam(name = "planId", required = false) UUID planId) {

        if (!ArtifactService.isAnswerable(scope, planId)) {
            return ResponseEntity.badRequest().body(problem(Map.of(
                    "planId",
                    List.of(scope == ArtifactScope.PLAN
                            ? "A plan register needs the plan it is about."
                            : "A project register is not about one plan."))));
        }

        return register
                .findRegister(UUID.fromString(jwt.getSubject()), projectId, scope, planId)
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Registers a reference. 201 with the created row, and {@code Location}. */
    @PostMapping
    public ResponseEntity<Object> create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestBody ArtifactInput input) {

        ArtifactWriteService.Outcome outcome =
                writes.create(UUID.fromString(jwt.getSubject()), projectId, input);

        return switch (outcome) {
            case ArtifactWriteService.Outcome.Created created -> ResponseEntity
                    .created(location(projectId, created.view().id()))
                    .body(created.view());
            case ArtifactWriteService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(problem(invalid.errors()));
            case ArtifactWriteService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            // 403 with a body, unlike its sibling. The caller holds the permission and not for this
            // scope, and naming the field is what lets the form put the sentence on the scope
            // selector — see the outcome's own header for why that discloses nothing.
            case ArtifactWriteService.Outcome.ForbiddenAtScope ignored -> ResponseEntity
                    .status(HttpStatus.FORBIDDEN)
                    .body(scopeProblem());
            case ArtifactWriteService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            // The other two writes' outcomes. Unreachable here, and written out rather than
            // defaulted for the reason the delete's own switch gives.
            case ArtifactWriteService.Outcome.Deleted ignored ->
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            case ArtifactWriteService.Outcome.Undeletable ignored ->
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            case ArtifactWriteService.Outcome.Updated ignored ->
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            case ArtifactWriteService.Outcome.Unchangeable ignored ->
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
    }

    /**
     * Corrects an artifact's title, category or description.
     *
     * <p>{@code PATCH} rather than {@code PUT} because the body is a <em>subset</em> of the row —
     * scope, kind and the payload columns are not settable at all — so a {@code PUT} would name a
     * representation this endpoint cannot accept whole. That it happens to require two of its three
     * fields does not make it a replacement: see {@link ArtifactEditInput} for §68's convention and
     * why requiring them is what makes it safe.
     *
     * <p>Every status here is the status endpoint's, and for the same reasons — including the 403
     * with a body, which is the scope-qualified refusal read against the <strong>row's</strong>
     * scope. The 409 is a different one: {@code write_once}, which is the delete's first guard
     * applied to the other act it was always about.
     */
    @PatchMapping("/{artifactId}")
    public ResponseEntity<Object> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID artifactId,
            @RequestBody ArtifactEditInput input) {

        ArtifactWriteService.Outcome outcome =
                writes.edit(UUID.fromString(jwt.getSubject()), projectId, artifactId, input);

        return switch (outcome) {
            case ArtifactWriteService.Outcome.Updated updated -> ResponseEntity.ok(updated.view());
            case ArtifactWriteService.Outcome.Invalid invalid -> ResponseEntity.badRequest()
                    .body(problem("The artifact could not be changed.", invalid.errors()));
            case ArtifactWriteService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            case ArtifactWriteService.Outcome.ForbiddenAtScope ignored -> ResponseEntity
                    .status(HttpStatus.FORBIDDEN)
                    .body(editScopeProblem());
            case ArtifactWriteService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            case ArtifactWriteService.Outcome.Unchangeable refused -> ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(conflict("This artifact cannot be changed", refused.message()));
            // The create's and the delete's. Unreachable here — see the delete's switch for why
            // the arms stay written rather than collapsed into a default.
            case ArtifactWriteService.Outcome.Created ignored ->
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            case ArtifactWriteService.Outcome.Deleted ignored ->
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            case ArtifactWriteService.Outcome.Undeletable ignored ->
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
    }

    /**
     * Archives an artifact, or restores it — {@code PUT} because the payload is the state the caller
     * wants rather than the move they want, so sending it twice lands in the same place.
     *
     * <p>The 403 here is the create's, unchanged: {@code artifact:edit}, scope-qualified, and read
     * against the <strong>row's</strong> scope rather than the request's — a Test Engineer archives
     * their cycle's documents and not the project's test strategy. It carries a body for the same
     * reason the create's does: the caller holds the key, and what they cannot act on is this row's
     * scope, which is a fact worth stating rather than a bare "forbidden".
     *
     * <p>The 409 is not about the caller at all. {@code SUPERSEDED} is a fact about another row (see
     * {@code ArtifactStatus}), so it is neither an origin nor a destination here.
     */
    @PutMapping("/{artifactId}/status")
    public ResponseEntity<Object> status(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID artifactId,
            @RequestBody ArtifactStatusInput input) {

        ArtifactWriteService.Outcome outcome =
                writes.setStatus(UUID.fromString(jwt.getSubject()), projectId, artifactId, input);

        return switch (outcome) {
            case ArtifactWriteService.Outcome.Updated updated -> ResponseEntity.ok(updated.view());
            case ArtifactWriteService.Outcome.Invalid invalid -> ResponseEntity.badRequest()
                    .body(problem("The artifact's status could not be changed.", invalid.errors()));
            case ArtifactWriteService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            case ArtifactWriteService.Outcome.ForbiddenAtScope ignored -> ResponseEntity
                    .status(HttpStatus.FORBIDDEN)
                    .body(statusScopeProblem());
            case ArtifactWriteService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            case ArtifactWriteService.Outcome.Unchangeable refused -> ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(conflict("This artifact's status cannot be changed", refused.message()));
            // The create's and the delete's. Unreachable here — see the delete's switch.
            case ArtifactWriteService.Outcome.Created ignored ->
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            case ArtifactWriteService.Outcome.Deleted ignored ->
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            case ArtifactWriteService.Outcome.Undeletable ignored ->
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
    }

    /**
     * Unregisters an artifact. 204, and nothing in the body — there is no row left to render.
     *
     * <p>The 403 here is {@code artifact:delete}, which is a <em>different</em> refusal from every
     * other one in this controller: a Test Engineer who may register a plan's documents may not
     * remove one. It is bodiless like the create's, and unlike the scope refusal, because there is
     * no field to land a sentence on and nothing the caller can change about the request.
     */
    @DeleteMapping("/{artifactId}")
    public ResponseEntity<Object> delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID artifactId) {

        ArtifactWriteService.Outcome outcome =
                writes.delete(UUID.fromString(jwt.getSubject()), projectId, artifactId);

        return switch (outcome) {
            case ArtifactWriteService.Outcome.Deleted ignored -> ResponseEntity.noContent().build();
            case ArtifactWriteService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            case ArtifactWriteService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            case ArtifactWriteService.Outcome.Undeletable refused -> ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(conflict("This artifact cannot be removed", refused.message()));
            // Unreachable on this path — the create's three outcomes. The arms stay written rather
            // than collapsed into a default, because a sealed interface's whole value is that
            // adding a fourth outcome fails to compile here instead of falling into one.
            case ArtifactWriteService.Outcome.Created ignored ->
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            case ArtifactWriteService.Outcome.Invalid ignored ->
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            case ArtifactWriteService.Outcome.ForbiddenAtScope ignored ->
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            case ArtifactWriteService.Outcome.Updated ignored ->
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            case ArtifactWriteService.Outcome.Unchangeable ignored ->
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
    }

    /**
     * The register's own refusal, worded by the service — see {@code Outcome.Undeletable} and
     * {@code Outcome.Unchangeable}.
     *
     * <p>The title is a parameter because the two 409s are about different acts: "cannot be removed"
     * on a status conflict would tell the reader about an operation they did not attempt.
     */
    private static ProblemDetail conflict(String title, String message) {
        ProblemDetail detail =
                ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, message);
        detail.setTitle(title);
        return detail;
    }

    /**
     * RFC 7807, with {@code errors} as a property — the call {@code work.WorkItemController} and
     * {@code DocPageController} already made, rather than a third hand-rolled envelope. See
     * {@code DocPageController}'s header for why a {@code shared} module is not introduced here.
     */
    private static ProblemDetail problem(Map<String, List<String>> errors) {
        return problem("The artifact could not be registered.", errors);
    }

    private static ProblemDetail problem(String detailText, Map<String, List<String>> errors) {
        ProblemDetail detail =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detailText);
        detail.setTitle("Invalid artifact");
        detail.setProperty("errors", errors);
        return detail;
    }

    /**
     * The scope-qualified refusal, worded as something the user can act on.
     *
     * <p>Same {@code errors} shape as the 400 so the form has one code path for "a field was the
     * problem" rather than two that must be kept in step.
     */
    private static ProblemDetail scopeProblem() {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, "Your role can add artifacts to a plan, but not to the project.");
        detail.setTitle("Not allowed at this scope");
        detail.setProperty("errors", Map.of(
                "scope",
                List.of("Your role can add artifacts to a plan, but not to the project as a whole. "
                        + "Project artifacts are inherited by every plan, so a Test Lead owns them.")));
        return detail;
    }

    /**
     * The same refusal about a row that already exists, which is a different sentence: the caller is
     * not choosing a scope here, they are being told about the one this artifact already sits at.
     *
     * <p>Not folded into {@link #scopeProblem()} with the strings as parameters, and the reason is a
     * check rather than taste — {@code ErrorBodyPolicyTest} reads the <em>construction site</em> and
     * requires the value to be a literal sentence there, so a parameterized builder hands it a
     * variable and the rule stops being able to see the words.
     */
    /**
     * The same refusal about correcting a row, which is a third sentence rather than a parameter.
     *
     * <p>Three near-identical builders is not duplication being tolerated — {@code
     * ErrorBodyPolicyTest} reads the <strong>construction site</strong> and requires the value to
     * be a literal sentence there, so a parameterized builder hands it a variable and the rule
     * stops being able to see the words. See {@link #statusScopeProblem()}.
     */
    private static ProblemDetail editScopeProblem() {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, "Your role can correct a plan's artifacts, but not the project's.");
        detail.setTitle("Not allowed at this scope");
        detail.setProperty("errors", Map.of(
                "scope",
                List.of("Your role can correct a plan's artifacts, but not the project's. Project "
                        + "artifacts are inherited by every plan, so a Test Lead owns them.")));
        return detail;
    }

    private static ProblemDetail statusScopeProblem() {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, "Your role can change a plan's artifacts, but not the project's.");
        detail.setTitle("Not allowed at this scope");
        detail.setProperty("errors", Map.of(
                "scope",
                List.of("Your role can change a plan's artifacts, but not the project's. Project "
                        + "artifacts are inherited by every plan, so a Test Lead owns them.")));
        return detail;
    }

    private static URI location(UUID projectId, UUID artifactId) {
        // The register has no per-artifact GET yet, so this points at the collection the row is now
        // in. A Location naming an endpoint that 404s would be worse than a coarse one.
        return URI.create("/api/v1/projects/" + projectId + "/artifacts#" + artifactId);
    }
}
