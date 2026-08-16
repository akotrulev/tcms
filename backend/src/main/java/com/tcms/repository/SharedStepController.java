package com.tcms.repository;

import com.tcms.api.Problem;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /projects/{projectId}/shared-steps} — the reusable step library.
 *
 * <p>A separate controller from {@link ConditionController} on the terms that class states for
 * being separate from {@link CaseController}: the path expresses the scope, not the owner, and
 * merging the two libraries would make one class the home of every project-scoped list. They are
 * also not the same endpoint wearing two shapes — a condition has a {@code type} and attaches to a
 * suite or a case, a shared step is referenced from inside a step list.
 *
 * <h2>What this closes</h2>
 *
 * <p>The {@code GET} has been in the contract since it was written and no server ever answered it,
 * so {@code useSharedSteps} sat in the SPA's data module with zero call sites — dead on both sides
 * at once, which is why neither side noticed. Meanwhile {@code TestStepInput.sharedStepId} has been
 * accepted and persisted on every case save since M1: the product could store a reference into a
 * library it had no way to populate and no way to check against. The {@code POST} is the writer
 * that makes the reference mean something, and {@link CaseEdit#validate} is where the check landed.
 *
 * <h2>The caller comes from the token, and the company from the context</h2>
 *
 * <p>Neither is a parameter. A {@code userId} parameter answers "what may <em>that</em> user see",
 * and a {@code companyId} parameter lets request input select the tenancy discriminator — the one
 * thing {@link com.tcms.tenancy.CompanyContext} exists to prevent (ERD §0.1 point 2).
 *
 * <h2>404, never 403, on the read</h2>
 *
 * <p>A project that does not exist, one in another company, and one in this company the caller
 * holds no grant on all answer <strong>404 with no body</strong> (§6t, handoff §3 lesson 13).
 * {@link SharedStepService#findLibrary} collapses the three into an empty {@link java.util.Optional}
 * so this class has nothing to un-collapse. The {@code POST} answers 403 only once the project is
 * known to be reachable, which is the same rule and not an exception to it: refusing a permission
 * on a project the caller can already read discloses nothing they did not have.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/shared-steps")
public class SharedStepController {

    private final SharedStepService sharedSteps;

    public SharedStepController(SharedStepService sharedSteps) {
        this.sharedSteps = sharedSteps;
    }

    /**
     * The library, most-used first, narrowed by an optional title fragment.
     *
     * <p>{@code []} is a valid 200 and means nothing matched — the state of a freshly provisioned
     * project, whose {@code tcms_seed_project_defaults} seeds statuses and a configuration and
     * deliberately not a client's procedures, and of any typeahead narrowed past the data. It is
     * not the 404 the gate uses: a user who cannot load the picker cannot clear the query that
     * emptied it.
     */
    @GetMapping
    public ResponseEntity<List<SharedStepView>> library(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestParam(required = false) String q) {

        return sharedSteps
                .findLibrary(UUID.fromString(jwt.getSubject()), projectId, new SharedStepQuery(q))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Adds an entry to the library. {@code library:manage}.
     *
     * <p>No {@code Idempotency-Key} — see {@link SharedStepService#create}.
     */
    /** A header that is present and blank is a header that was not sent (B4). */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @PostMapping
    public ResponseEntity<Object> create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody SharedStepWriteRequest request) {

        SharedStepService.Outcome outcome = sharedSteps.create(
                UUID.fromString(jwt.getSubject()), projectId, request,
                trimToNull(idempotencyKey));

        return switch (outcome) {
            case SharedStepService.Outcome.Created created -> ResponseEntity
                    .created(location(projectId, created.view().id()))
                    .body(created.view());
            // 200 and no Location: nothing was created by THIS request.
            case SharedStepService.Outcome.Replayed replayed ->
                    ResponseEntity.ok(replayed.view());
            case SharedStepService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            case SharedStepService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problem.forbidden(
                            "You do not have permission to maintain the step library in this "
                                    + "project."));
            case SharedStepService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(Problem.validation(invalid.errors()));
        };
    }

    /**
     * The created entry's address.
     *
     * <p>The collection has no {@code /{id}} operation to GET, so this points at the list. That is
     * deliberate rather than a placeholder: {@code Location} is required to be a URI the client can
     * dereference, and naming a path no route serves would answer 404 to anything that followed it.
     */
    private static URI location(UUID projectId, UUID sharedStepId) {
        return URI.create(
                "/api/v1/projects/" + projectId + "/shared-steps?created=" + sharedStepId);
    }
}
