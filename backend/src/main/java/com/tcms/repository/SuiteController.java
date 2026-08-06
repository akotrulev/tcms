package com.tcms.repository;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * The suite tree — the first {@code repository}-module endpoint, and the first one whose response
 * the SPA renders as a <em>screen</em> rather than as reference data.
 *
 * <p>A separate controller from {@code ProjectController} even though the path nests under
 * {@code /projects/{projectId}}: the path expresses the scope, not the owner. Suites are ERD §4
 * and this module, and putting them on the project controller would make that class the place
 * every project-scoped feature lands.
 *
 * <h2>The caller comes from the token, and the company from the context</h2>
 *
 * <p>Neither is a parameter, for the reasons {@code ProjectController} sets out at length: a
 * {@code userId} parameter answers "what may <em>that</em> user see", and a {@code companyId}
 * parameter lets request input select the tenancy discriminator — the single thing
 * {@link com.tcms.tenancy.CompanyContext} exists to prevent (ERD §0.1 point 2).
 *
 * <h2>404, never 403</h2>
 *
 * <p>A project that does not exist, one in another company, and one in this company the caller
 * holds no grant on all answer <strong>404 with no body</strong>. A 403 would confirm the id names
 * something real, which makes the endpoint an oracle for another client's project ids (§6t,
 * handoff §3 lesson 13). {@link SuiteService#findTree} collapses the three into an empty
 * {@link java.util.Optional} so this method has nothing to un-collapse.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/suites")
public class SuiteController {

    private final SuiteService suites;
    private final SuiteWriteService writes;
    private final SuiteStatusService statuses;

    public SuiteController(
            SuiteService suites, SuiteWriteService writes, SuiteStatusService statuses) {

        this.suites = suites;
        this.writes = writes;
        this.statuses = statuses;
    }

    /**
     * The project's suites, as a flat list carrying {@code parentId} — the client builds the tree.
     *
     * <p>{@code []} is a valid answer and means the project has no suites, which is the state of
     * every freshly provisioned project: {@code tcms_seed_project_defaults} seeds a configuration
     * and the status vocabulary, deliberately not a folder. Inventing a "Default" suite would be
     * inventing a client's structure, which is the same mistake as seeding their Feature tree.
     */
    @GetMapping
    public ResponseEntity<List<SuiteView>> tree(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {

        return suites.findTree(UUID.fromString(jwt.getSubject()), projectId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Creates a suite — <strong>the write that makes a provisioned company usable.</strong>
     *
     * <p>Before it, a project seeded by {@code provision-company} had no folders and could therefore
     * hold no cases, since a case requires a {@code suiteId}. Nothing seeds a "Default" folder,
     * deliberately: inventing a client's structure is the same mistake as seeding their Feature
     * tree.
     *
     * <p>{@code key} and {@code path} are server-owned. The key comes from the company's counter
     * ({@code TS-n}, §6t) and the path is derived from it rather than from the name, so a rename
     * never rewrites a path — see {@link Suite#create}.
     *
     * <p>403 is {@code suite:manage}, which Viewer does not hold. It is reached only after the read
     * gate passed, so nothing is disclosed that loading the tree did not already disclose.
     */
    @PostMapping
    public ResponseEntity<Object> create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestBody SuiteWriteRequest request) {

        SuiteWriteService.Outcome outcome =
                writes.create(UUID.fromString(jwt.getSubject()), projectId, request);

        return switch (outcome) {
            case SuiteWriteService.Outcome.Created created -> ResponseEntity
                    .created(location(projectId, created.view().id()))
                    .body(created.view());
            default -> problem(outcome, "create suites in this project");
        };
    }

    /**
     * Renames a suite, and edits its description.
     *
     * <p>{@code If-Match} carries the {@code lockVersion} the client read, exactly as on a case edit
     * and for the same reason: a body field would put a value on the input schema that must never be
     * persisted. A missing header is last-write-wins, which is right for an importer with no prior
     * read; the SPA always sends it.
     *
     * <p><strong>A payload asking to move the suite is a 400</strong>, not a silently ignored
     * field — see {@link SuiteEdit#validateRename}.
     */
    @PatchMapping("/{suiteId}")
    public ResponseEntity<Object> rename(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID suiteId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody SuiteWriteRequest request) {

        Long expected;
        try {
            expected = parseIfMatch(ifMatch);
        } catch (NumberFormatException notAVersion) {
            // 400 rather than 409, for CaseController's reason: answering 409 would send a client
            // with a malformed header into a reload-and-retry loop that cannot succeed.
            return ResponseEntity.badRequest().body(ApiProblem.validation(Map.of(
                    "If-Match",
                    List.of("Send the suite's lockVersion as the If-Match header, or omit the "
                            + "header."))));
        }

        SuiteWriteService.Outcome outcome = writes.rename(
                UUID.fromString(jwt.getSubject()), projectId, suiteId, request, expected);

        return switch (outcome) {
            case SuiteWriteService.Outcome.Updated updated -> ResponseEntity.ok(updated.view());
            default -> problem(outcome, "rename suites in this project");
        };
    }

    /**
     * Soft-deletes an <strong>empty</strong> suite.
     *
     * <p>403 here is {@code suite:delete}, a different permission from the one the other two writes
     * ask: a Test Engineer may create and rename folders and may not delete them (roles spec §6b).
     * So this is the one endpoint in the module where a caller who just successfully renamed a suite
     * can be refused, which is the intended shape rather than an inconsistency.
     *
     * <p>409 when the folder holds live cases or child suites, with the counts in {@code detail}.
     * Cascading is not implemented and the refusal is the feature — see {@link SuiteWriteService}.
     */
    @DeleteMapping("/{suiteId}")
    public ResponseEntity<Object> delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID suiteId) {

        SuiteWriteService.Outcome outcome =
                writes.delete(UUID.fromString(jwt.getSubject()), projectId, suiteId);

        return switch (outcome) {
            case SuiteWriteService.Outcome.Deleted ignored -> ResponseEntity.noContent().build();
            default -> problem(outcome, "delete suites in this project");
        };
    }

    /**
     * Moves the suite along §6g's ladder — submit for review, approve, withdraw, deprecate, revive.
     *
     * <p>A separate endpoint from the {@code PATCH} beside it, and not a field on
     * {@code SuiteInput}, because {@link Suite#applyEdit} refuses to carry it: a status an ordinary
     * edit can set is an approval anyone can grant themselves by saving. Same shape as
     * {@code PUT /plans/&#123;planId&#125;/status} and {@code PUT /runs/&#123;runId&#125;/status}.
     *
     * <p><strong>{@code PUT}, not {@code PATCH}</strong>: the body names an absolute target state,
     * so a repeated call is the same call. That is also why there is no {@code If-Match} — see
     * {@link SuiteStatusService}.
     *
     * <p>403 here can be either of two keys, decided by the <em>edge</em> the request asks for
     * ({@link LifecycleLadder#permissionFor}): a Test Engineer holding {@code suite:manage} may
     * submit a draft for review and be refused the approval beside it, and may equally be refused
     * the move that would take a lead's approval back. 409 is the ladder refusing a move the
     * caller is otherwise permitted to make.
     */
    @PutMapping("/{suiteId}/status")
    public ResponseEntity<Object> setStatus(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID suiteId,
            @RequestBody SuiteStatusRequest request) {

        SuiteStatusService.Outcome outcome = statuses.setStatus(
                UUID.fromString(jwt.getSubject()), projectId, suiteId, request);

        return switch (outcome) {
            case SuiteStatusService.Outcome.Updated updated -> ResponseEntity.ok(updated.view());
            case SuiteStatusService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            case SuiteStatusService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiProblem.forbidden(
                            "You do not have permission to make this change to suites in this "
                                    + "project."));
            case SuiteStatusService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(ApiProblem.validation(invalid.errors()));
            // 409 rather than 400: the payload is well-formed and the permission is held — it is
            // the suite's current state that refuses, which is a different thing to fix.
            case SuiteStatusService.Outcome.Refused refused ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(ApiProblem.conflict(
                            "Status change refused", refused.message()));
        };
    }

    /**
     * The failure half of all three writes, mapped once.
     *
     * <p>Three copies of this switch is three places a status code can drift apart, and the drift
     * that matters is the 404: a create answering 403 where a rename answers 404 for the same
     * unreachable project turns the pair into an oracle (§6t). The success cases stay at the call
     * sites because each is genuinely different — 201 with a {@code Location}, 200 with a body, 204
     * with neither.
     *
     * @param action the sentence fragment naming what was refused, so the 403 copy reads as the
     *               user's own attempt rather than as a permission key
     */
    private static ResponseEntity<Object> problem(
            SuiteWriteService.Outcome outcome, String action) {

        return switch (outcome) {
            case SuiteWriteService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            case SuiteWriteService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body(ApiProblem.forbidden("You do not have permission to " + action
                                    + "."));
            case SuiteWriteService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(ApiProblem.validation(invalid.errors()));
            case SuiteWriteService.Outcome.Stale ignored ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(ApiProblem.stale("suite"));
            case SuiteWriteService.Outcome.NotEmpty notEmpty ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(ApiProblem.conflict(
                            "Suite is not empty", describe(notEmpty)));
            // The three success shapes. Reached only if a call site above routes one here — each
            // handles its own success and delegates the rest — so these arms exist to satisfy
            // exhaustiveness, not as a fallback that quietly renders a created suite as an error.
            case SuiteWriteService.Outcome.Created ignored ->
                    throw new IllegalStateException("A created suite was mapped as a problem");
            case SuiteWriteService.Outcome.Updated ignored ->
                    throw new IllegalStateException("An updated suite was mapped as a problem");
            case SuiteWriteService.Outcome.Deleted ignored ->
                    throw new IllegalStateException("A deleted suite was mapped as a problem");
        };
    }

    /**
     * Names what is standing in the way of a delete.
     *
     * <p>The count rather than "the suite is not empty", because the two are different amounts of
     * help: a user cannot see from the rail that a suite holds four cases, and "move or delete them
     * first" is only actionable once they know there are four.
     *
     * <p>It named a child-suite count too, until §98 left a suite with no children to hold.
     */
    private static String describe(SuiteWriteService.Outcome.NotEmpty notEmpty) {
        return "This suite still holds " + notEmpty.cases()
                + (notEmpty.cases() == 1 ? " case" : " cases")
                + ". Move or delete them first.";
    }

    /**
     * A genuine write race, answered as a 409 rather than a 500 — {@link CaseController#onWriteRace}
     * carries the full account of why this is a handler and not a {@code catch} in the service, and
     * of why this path is not covered by the suite (it needs two interleaved transactions).
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Object> onWriteRace(OptimisticLockingFailureException race) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiProblem.conflict());
    }

    /**
     * The created suite's own URL, built from this controller's mapping rather than from a
     * request-relative builder: {@code fromCurrentRequest} would echo whatever host or proxy prefix
     * the caller sent, which is a header-controlled value in a response.
     */
    private static URI location(UUID projectId, UUID suiteId) {
        return URI.create("/api/v1/projects/" + projectId + "/suites/" + suiteId);
    }

    /** Reads {@code If-Match}, tolerating the quoted form — {@link CaseController} explains both. */
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
