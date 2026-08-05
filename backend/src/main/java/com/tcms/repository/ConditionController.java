package com.tcms.repository;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /projects/{projectId}/conditions} — the reusable pre/post condition library, read and
 * (since §95) written.
 *
 * <p>Until §95 this was a {@code GET} of a table <strong>nothing could write</strong>: the library
 * had no writer anywhere in the product and no frontend call site at all, so the one genuinely
 * shared authoring object in the schema could be read, ranked and attached, and never created. The
 * {@code POST} and {@code PUT} close that. The {@code PUT} is the consequential half — an edit
 * propagates to every case and suite attaching the entry, because an attachment is a reference and
 * not a copy.
 *
 * <p>A separate controller from {@link SuiteController} and {@link CaseController} on the same
 * terms those two are separate from each other: the path expresses the scope, not the owner, and
 * merging them would make one class the home of every project-scoped read.
 *
 * <h2>What this closes</h2>
 *
 * <p>{@code CaseListPage} fetches this endpoint for its bulk-action dialog on every load, so until
 * now the biggest screen in the product raised a "Something didn't load" notification even when
 * everything on it worked (handoff §25). It was invisible before that slice only because
 * {@code /cases} 404'd first — which is the useful half of the observation: an error notification
 * the user learns to ignore is one that no longer reports anything.
 *
 * <h2>The caller comes from the token, and the company from the context</h2>
 *
 * <p>Neither is a parameter. A {@code userId} parameter answers "what may <em>that</em> user see",
 * and a {@code companyId} parameter lets request input select the tenancy discriminator — the one
 * thing {@link com.tcms.tenancy.CompanyContext} exists to prevent (ERD §0.1 point 2).
 *
 * <h2>404, never 403</h2>
 *
 * <p>A project that does not exist, one in another company, and one in this company the caller
 * holds no grant on all answer <strong>404 with no body</strong>. A 403 confirms the id names
 * something real, which makes the endpoint an oracle for another client's project ids (§6t,
 * handoff §3 lesson 13). {@link ConditionService#findLibrary} collapses the three into an empty
 * {@link java.util.Optional} so this method has nothing to un-collapse.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/conditions")
public class ConditionController {

    private final ConditionService conditions;

    public ConditionController(ConditionService conditions) {
        this.conditions = conditions;
    }

    /**
     * The library, most-used first, narrowed by an optional title fragment and an optional role.
     *
     * <p>{@code type} is bound as an enum, so a value outside {@code PRE|POST|BOTH} is a 400 rather
     * than a silently ignored filter. That matters for the same reason it does on the case list: a
     * dropped filter here offers postconditions in a precondition picker, and every one of them
     * looks like a plausible answer.
     *
     * <p>{@code []} is a valid 200 and means nothing matched — the state of a freshly provisioned
     * project, and of any typeahead narrowed past the data. It is deliberately not the 404 the
     * gate uses: a user who cannot load the dialog cannot clear the query that emptied it.
     */
    @GetMapping
    public ResponseEntity<List<ConditionView>> library(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) ConditionType type) {

        return conditions
                .findLibrary(UUID.fromString(jwt.getSubject()), projectId, new ConditionQuery(q, type))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Adds an entry to the library. {@code library:manage}.
     *
     * <p>No {@code Idempotency-Key} — see {@link ConditionService#create}.
     */
    @PostMapping
    public ResponseEntity<Object> create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestBody ConditionWriteRequest request) {

        return render(
                conditions.create(UUID.fromString(jwt.getSubject()), projectId, request),
                projectId);
    }

    /**
     * Edits an entry, propagating to every case and suite attaching it. {@code library:manage}.
     *
     * <p>This is the operation the roles spec's "gated by an impact modal, not by permission" note
     * is about. The modal is the client's and is computed from {@code usageCount} /
     * {@code suiteUsageCount}; what this endpoint owes it is the {@code If-Match} check, so that
     * an author who read the impact figures cannot apply their edit to a row somebody else has
     * since changed underneath them.
     *
     * <p>{@code If-Match} is bound as a {@link Long} rather than parsed from a string: a
     * malformed header is then a 400 from the binder, not a silently skipped precondition. The
     * header being <em>absent</em> is the documented last-write-wins path and stays null.
     */
    @PutMapping("/{conditionId}")
    public ResponseEntity<Object> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID conditionId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) Long ifMatch,
            @RequestBody ConditionWriteRequest request) {

        return render(
                conditions.update(
                        UUID.fromString(jwt.getSubject()), projectId, conditionId, ifMatch, request),
                projectId);
    }

    /**
     * One mapping for both writers — they answer the same six outcomes and differ only in which
     * success arm they can reach, which the sealed interface makes the compiler check rather than
     * the reader.
     */
    private static ResponseEntity<Object> render(ConditionService.Outcome outcome, UUID projectId) {
        return switch (outcome) {
            case ConditionService.Outcome.Created created -> ResponseEntity
                    .created(location(projectId, created.view().id()))
                    .body(created.view());
            case ConditionService.Outcome.Updated updated -> ResponseEntity.ok(updated.view());
            case ConditionService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            case ConditionService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiProblem.forbidden(
                            "You do not have permission to maintain the condition library in this "
                                    + "project."));
            case ConditionService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(ApiProblem.validation(invalid.errors()));
            // stale(noun), not conflict(title, detail): this is a lost update, and the two 409s
            // want opposite advice — "reload and reapply" is exactly right here and is useless for
            // a refusal about the target's state. The noun is parameterized so the copy does not
            // send the reader to the wrong screen.
            case ConditionService.Outcome.Conflict ignored ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(ApiProblem.stale("condition"));
        };
    }

    /**
     * The created entry's address.
     *
     * <p>Points at the list rather than at {@code /{conditionId}}, even though that path now
     * exists: the new path serves {@code PUT} and no {@code GET}, and {@code Location} is required
     * to be a URI the client can dereference. Naming it would answer 405 to anything that followed
     * it — the same trap {@link SharedStepController#location} avoids for the opposite reason.
     */
    private static URI location(UUID projectId, UUID conditionId) {
        return URI.create("/api/v1/projects/" + projectId + "/conditions?created=" + conditionId);
    }
}
