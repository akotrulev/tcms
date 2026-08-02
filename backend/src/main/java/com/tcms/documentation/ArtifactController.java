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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
            case ArtifactWriteService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
        };
    }

    /**
     * RFC 7807, with {@code errors} as a property — the call {@code work.WorkItemController} and
     * {@code DocPageController} already made, rather than a third hand-rolled envelope. See
     * {@code DocPageController}'s header for why a {@code shared} module is not introduced here.
     */
    private static ProblemDetail problem(Map<String, List<String>> errors) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "The artifact could not be registered.");
        detail.setTitle("Invalid artifact");
        detail.setProperty("errors", errors);
        return detail;
    }

    private static URI location(UUID projectId, UUID artifactId) {
        // The register has no per-artifact GET yet, so this points at the collection the row is now
        // in. A Location naming an endpoint that 404s would be worse than a coarse one.
        return URI.create("/api/v1/projects/" + projectId + "/artifacts#" + artifactId);
    }
}
