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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /projects/{projectId}/cases} — the case list, and the biggest screen in the product.
 *
 * <p>A separate controller from {@link SuiteController} for the reason that one is separate from
 * {@code ProjectController}: the path expresses the scope, not the owner. Both are ERD §4 and this
 * module, and merging them would make one class the home of every project-scoped read.
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
 * something real, which makes the endpoint an oracle for another client's project ids (§6t, handoff
 * §3 lesson 13). {@link CaseService#search} collapses the three into an empty
 * {@link java.util.Optional} so this method has nothing to un-collapse.
 *
 * <h2>Every filter is bound explicitly, and the unbindable ones are not silently dropped</h2>
 *
 * <p>Spring binds each parameter by name, and an enum value it cannot parse is a 400 rather than a
 * silently ignored filter — which matters more here than it looks: a dropped filter returns the
 * project's <em>entire</em> case list under a heading that claims it is narrowed. That is the same
 * failure {@code releaseVersion} is handled for in {@link CaseSpecifications}, arriving through a
 * different door.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/cases")
public class CaseController {

    /**
     * The header carrying one <em>user intent</em> to create, so a retry is not a second case.
     *
     * <p>Not a constant on {@code HttpHeaders} — it is an IETF draft header, not an RFC 9110 one, and
     * spelling it once here is better than three string literals across the controller and its test.
     */
    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final CaseService search;
    private final CaseDetailService detail;
    private final CaseVersionService history;
    private final CaseUpdateService cases;
    private final CaseCreateService creation;
    private final CaseDeleteService deletion;
    private final CaseBulkService bulk;
    private final CaseQuarantineService quarantine;

    public CaseController(
            CaseService search,
            CaseDetailService detail,
            CaseVersionService history,
            CaseUpdateService cases,
            CaseCreateService creation,
            CaseDeleteService deletion,
            CaseBulkService bulk,
            CaseQuarantineService quarantine) {
        this.search = search;
        this.detail = detail;
        this.history = history;
        this.cases = cases;
        this.creation = creation;
        this.deletion = deletion;
        this.bulk = bulk;
        this.quarantine = quarantine;
    }

    /**
     * A page of matching cases with the facet counts beside them.
     *
     * <p>An empty {@code items} array is a valid 200 and means "nothing matched" — the state of a
     * freshly provisioned project, and of any filter combination the client has narrowed past its
     * data. It is deliberately not the 404 the gate uses: a user who cannot load the page cannot
     * clear the filter that emptied it.
     *
     * <p>Defaults live on the annotations rather than in {@link CaseQuery}'s constructor so they are
     * visible at the edge, and are re-normalized there anyway — {@code page=-3} and
     * {@code size=100000} are bound before that record ever sees them, and clamping is its job.
     */
    @GetMapping
    public ResponseEntity<CasePageView> search(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID suiteId,
            @RequestParam(required = false) UUID featureId,
            @RequestParam(required = false) UUID testTypeId,
            @RequestParam(required = false) UUID testLevelId,
            @RequestParam(required = false) UUID ownerId,
            @RequestParam(required = false) List<LifecycleStatus> lifecycleStatus,
            @RequestParam(required = false) List<AutomationStatus> automationStatus,
            @RequestParam(required = false) List<Priority> priority,
            @RequestParam(required = false) List<HealthSignal> healthSignal,
            @RequestParam(name = "tag", required = false) List<String> tags,
            @RequestParam(required = false, defaultValue = "false") boolean unfiled,
            @RequestParam(required = false) String releaseVersion,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + CaseQuery.DEFAULT_SIZE) int size,
            @RequestParam(defaultValue = CaseSort.DEFAULT) String sort) {

        CaseQuery request = new CaseQuery(
                q, suiteId, featureId, testTypeId, testLevelId, ownerId,
                lifecycleStatus, automationStatus, priority, healthSignal,
                // `tag` on the wire, `tags` here: the parameter is repeated per value
                // (`?tag=smoke&tag=slow`), which reads as one tag per occurrence at the call site
                // and as a list at this one.
                tags, unfiled,
                releaseVersion, page, size, sort);

        return search.search(UUID.fromString(jwt.getSubject()), projectId, request)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * One whole case.
     *
     * <p><strong>Both path segments are gates, and the 404 covers three different failures</strong>
     * — an unreachable project, a case that does not exist, and a case that exists in a project the
     * caller was not granted. They answer identically and with no body, because telling them apart
     * turns {@code /projects/{granted}/cases/{id}} into a probe for case ids elsewhere in the
     * company. {@link CaseDetailService#findOne} collapses all three into an empty
     * {@link java.util.Optional} so this method has nothing to un-collapse.
     *
     * <p>{@code caseId} is a UUID and never a display key. {@code TC-1} is company-scoped and
     * therefore guessable, which is exactly why plan §6t keeps display keys out of API paths.
     */
    @GetMapping("/{caseId}")
    public ResponseEntity<TestCaseDetailView> findOne(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID caseId) {

        return detail.findOne(UUID.fromString(jwt.getSubject()), projectId, caseId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * The case's authored version history, newest first.
     *
     * <h2>Same two gates as the detail, same single 404</h2>
     *
     * <p>An unreachable project, a case that does not exist and a case in a sibling project all
     * answer <strong>404 with no body</strong>, exactly as {@link #findOne} does. This response is a
     * strict subset of what reading the case already discloses to the same caller, so it takes the
     * read gate and no permission of its own.
     *
     * <h2>{@code 200 []} is not a 404, and the difference is most of the installation</h2>
     *
     * <p>Every case authored before the edit endpoint existed has no version rows at all (handoff
     * §28) — an ordinary state of a readable case, not a missing resource. Answering 404 for it
     * would tell the reader their case was gone, on most of the cases in an existing project.
     *
     * <p>The contract declares only a 200 for this path. The 404 is served all the same, because
     * every other read on this controller answers one and an endpoint that instead disclosed
     * something for an unreachable case would be the inference channel §6t exists to close. See
     * {@code docs/handoff/35-m1-s12-case-versions.md} for the divergence, which is the contract
     * holder's to close.
     */
    @GetMapping("/{caseId}/versions")
    public ResponseEntity<List<EntityVersionView>> versions(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID caseId) {

        return history.findForCase(UUID.fromString(jwt.getSubject()), projectId, caseId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Creates a case.
     *
     * <h2>201 for a new case, 200 for a replay, and the difference is the point</h2>
     *
     * <p>{@code Idempotency-Key} identifies one <em>user intent</em>, not one attempt — the SPA
     * generates it when the editor mounts and reuses it across retries, which is what makes pressing
     * Save again after a timeout safe. A replay answers <strong>200</strong> with the case that intent
     * already created, so a client can distinguish "this call made it" from "this call found it", and
     * a retry is not recorded anywhere as a second creation. The MSW mock has answered exactly this
     * since Phase 1; until now the real server ignored the header entirely.
     *
     * <p>{@code Location} names the created case, which is what 201 means. The SPA reads the body
     * rather than the header — it needs the whole case to seed its cache — so this is for every other
     * client, and for the contract not to describe a 201 with nothing to fetch.
     *
     * <h2>No {@code If-Match}, deliberately</h2>
     *
     * <p>A precondition asserts something about state the caller already read, and there is none. The
     * lost-update race a create can actually have is "did my retry land twice", which is what the
     * idempotency key answers.
     *
     * <h2>403 and 404 mean here exactly what they mean on the edit below</h2>
     *
     * <p>An unreachable project is 404 with no body — including for a member of the company holding no
     * grant on it — and 403 is reached only after that gate has passed, when the caller is looking at
     * the project's case list and pressing New. Viewer is the role this refuses: it holds
     * {@code project:read} like every other role and not {@code case:create}.
     */
    @PostMapping
    public ResponseEntity<Object> create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody CaseUpdateRequest request) {

        CaseCreateService.Outcome outcome = creation.create(
                UUID.fromString(jwt.getSubject()), projectId, request, trimToNull(idempotencyKey));

        return switch (outcome) {
            case CaseCreateService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            case CaseCreateService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiProblem.forbidden(
                            "You do not have permission to create test cases in this project."));
            case CaseCreateService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(ApiProblem.validation(invalid.errors()));
            case CaseCreateService.Outcome.Created created -> ResponseEntity
                    .created(location(projectId, created.view().id()))
                    .body(created.view());
            // Not 201, and not a Location either: nothing was created by THIS request.
            case CaseCreateService.Outcome.Replayed replayed -> ResponseEntity.ok(replayed.view());
        };
    }

    /**
     * Edits a case — <strong>the first write this application serves.</strong>
     *
     * <h2>403 exists here, and it does not weaken the 404-not-403 rule</h2>
     *
     * <p>Every read in this module answers 404 for a target the caller cannot reach, so the response
     * cannot be used to discover what exists elsewhere in the company (§6t, handoff §3 lesson 13).
     * That still holds: an unreachable project and a case that is not in it both answer 404 here too.
     *
     * <p>{@code 403} is reached only <em>after</em> the read gate has passed — the caller is looking
     * at the case in the editor. Concealing it as a 404 at that point would tell them their case had
     * been deleted, which is false, alarming, and about the one thing a test repository must never
     * appear to have done. Nothing is disclosed that reading the case did not already disclose.
     *
     * <h2>The precondition is a header, not a field</h2>
     *
     * <p>{@code If-Match} carries the {@code lockVersion} the client read. A body field would put a
     * value on the input schema that must never be persisted, and the SPA sent one for exactly as
     * long as no server read it. A missing header is last-write-wins, which is right for an importer
     * with no prior read and is why the editor always sends it.
     */
    @PatchMapping("/{caseId}")
    public ResponseEntity<Object> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID caseId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody CaseUpdateRequest request) {

        Long expected;
        try {
            expected = parseIfMatch(ifMatch);
        } catch (NumberFormatException notAVersion) {
            // 400 rather than 409: a stale precondition and an unparseable one are different bugs,
            // and answering 409 would send a client with a malformed header into a reload-and-retry
            // loop that cannot succeed.
            return ResponseEntity.badRequest().body(ApiProblem.validation(
                    Map.of("If-Match", List.of("Send the case's lockVersion as the If-Match "
                            + "header, or omit the header."))));
        }

        CaseUpdateService.Outcome outcome =
                cases.update(UUID.fromString(jwt.getSubject()), projectId, caseId, request, expected);

        return switch (outcome) {
            case CaseUpdateService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            case CaseUpdateService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiProblem.forbidden(
                            "You do not have permission to edit test cases in this project."));
            case CaseUpdateService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(ApiProblem.validation(invalid.errors()));
            case CaseUpdateService.Outcome.Stale ignored ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(ApiProblem.conflict());
            case CaseUpdateService.Outcome.Updated updated -> ResponseEntity.ok(updated.view());
        };
    }

    /**
     * Previews or applies one operation over a selection of cases.
     *
     * <h2>Why the 403 carries a body here, when the sibling writes' does not</h2>
     *
     * <p>{@link CaseUpdateService}'s refusal is one sentence because there is one way to be refused:
     * the caller lacks {@code case:edit}. This endpoint has two, and they need opposite advice. A
     * Test Engineer moving cases to {@code APPROVED} is refused for lacking {@code case:approve}
     * while holding everything else — telling them "you cannot change cases here" is false, since
     * they change cases every day, and would send them to ask for a permission they already have.
     * So {@link BulkOperations#refusal} picks the sentence and the controller passes it through
     * rather than choosing one that fits neither.
     *
     * <p>Unlike §61's scope refusal this is <strong>not</strong> a field error, and the difference
     * is that {@code case:approve} is a real permission key. §31's disable-don't-hide therefore
     * works here as designed: {@code GET /me/permissions} carries the key, so the SPA disables the
     * APPROVED option for a caller who does not hold it and this refusal should be unreachable from
     * the product's own UI. It is answered properly anyway, for anyone arriving another way.
     *
     * <p>200 for both a preview and a commit. A preview is not a creation and has nothing to locate,
     * and a commit's own result — which cases changed — is the response body rather than a resource
     * at a new URL, so neither 201 nor 204 describes what happened.
     */
    @PostMapping("/bulk")
    public ResponseEntity<Object> bulk(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestBody BulkOperationRequest request) {

        CaseBulkService.Outcome outcome =
                bulk.run(UUID.fromString(jwt.getSubject()), projectId, request);

        return switch (outcome) {
            case CaseBulkService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            case CaseBulkService.Outcome.Forbidden forbidden ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body(ApiProblem.forbidden(forbidden.detail()));
            case CaseBulkService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(ApiProblem.validation(invalid.errors()));
            case CaseBulkService.Outcome.Done done -> ResponseEntity.ok(done.result());
        };
    }

    /**
     * Quarantines a flaky test, or puts it back into the gate — M3's quarantine flow.
     *
     * <h2>PUT with an absolute state, not POST and DELETE</h2>
     *
     * <p>{@link CaseQuarantineRequest}'s header carries the argument. Briefly: a client sending the
     * same request twice must not be told 404 or 409 about a state it already asked for, and a
     * checkbox that has to become two HTTP methods is where "the quarantine turned itself off"
     * comes from.
     *
     * <h2>200 with the row, not 204</h2>
     *
     * <p>Unlike the delete below, there is something left to describe and the caller cannot compute
     * it: {@code quarantinedAt}, {@code quarantinedBy} and the derived counts all come from the
     * server. A 204 would force the screen into a refetch to render the state it just set, which is
     * the round trip that makes a toggle feel broken.
     *
     * <h2>One 403, one sentence — unlike the delete</h2>
     *
     * <p>{@code case:edit} carries no {@code OWN} qualifier (roles spec §A5 qualifies
     * {@code case:delete}, not this), so there is exactly one way to be refused and one thing to say
     * about it. §31's disable-don't-hide makes it unreachable from the product's own UI, and it is
     * answered properly anyway for anyone arriving another way.
     *
     * <p>Worth knowing which role this excludes and why it is the point: <strong>Automation Service
     * does not hold {@code case:edit}</strong>, so a leaked CI token can post results here and
     * cannot quarantine a test to stop its own red results from gating. See
     * {@link CaseQuarantineService}.
     */
    @PutMapping("/{caseId}/quarantine")
    public ResponseEntity<Object> quarantine(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID caseId,
            @RequestBody CaseQuarantineRequest request) {

        if (request == null || request.quarantined() == null) {
            // A Boolean, so an absent field arrives as null rather than as a silent false that
            // would release a quarantine the caller never mentioned. See the request's header.
            return ResponseEntity.badRequest().body(ApiProblem.validation(Map.of(
                    "quarantined", List.of("Say whether to quarantine or release."))));
        }

        CaseQuarantineService.Outcome outcome = quarantine.set(
                UUID.fromString(jwt.getSubject()), projectId, caseId,
                request.quarantined(), request.reason());

        return switch (outcome) {
            case CaseQuarantineService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            case CaseQuarantineService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiProblem.forbidden(
                            "You do not have permission to change test cases in this project."));
            case CaseQuarantineService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(ApiProblem.validation(
                            Map.of(invalid.field(), List.of(invalid.message()))));
            case CaseQuarantineService.Outcome.Done done ->
                    ResponseEntity.ok(CaseFlakeView.of(done.flake()));
        };
    }

    /**
     * Soft-deletes a case.
     *
     * <h2>204, and why there is no body to return</h2>
     *
     * <p>Nothing is left to describe: the case is gone from every read, and a client that wanted its
     * content had it before pressing delete. A 200 carrying the tombstone would invite a screen that
     * renders a deleted case as if it were current.
     *
     * <h2>Two 403s with two sentences, from one permission</h2>
     *
     * <p>{@code case:delete} is {@code OWN}-qualified for Test Engineer (roles spec §A5), so
     * "refused" has two meanings and they must not be worded alike. A Test Engineer told "you do not
     * have permission to delete test cases in this project" would go and ask an admin for a
     * permission they already hold, about a case they are looking at, having deleted three of their
     * own that morning. See {@link CaseDeleteService} for the split.
     *
     * <h2>No {@code If-Match}</h2>
     *
     * <p>Deliberate, and {@link CaseDeleteService}'s header gives the reason: there is no lost-delete
     * for a precondition to prevent. The suite delete one level up takes none either.
     */
    @DeleteMapping("/{caseId}")
    public ResponseEntity<Object> delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID caseId) {

        CaseDeleteService.Outcome outcome =
                deletion.delete(UUID.fromString(jwt.getSubject()), projectId, caseId);

        return switch (outcome) {
            case CaseDeleteService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            case CaseDeleteService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiProblem.forbidden(
                            "You do not have permission to delete test cases in this project."));
            case CaseDeleteService.Outcome.NotTheAuthor ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiProblem.forbidden(
                            "You can only delete test cases you created. Ask a test lead to delete "
                                    + "this one."));
            case CaseDeleteService.Outcome.Deleted ignored -> ResponseEntity.noContent().build();
        };
    }

    /**
     * A genuine write race, answered as the 409 the contract declares rather than as a 500.
     *
     * <h2>Why this is a handler and not a {@code catch} in the service</h2>
     *
     * <p>{@code @Version}'s check fails during {@code flush}, inside the service's transaction, and
     * catching it there is a trap: the transaction is already marked rollback-only, so returning a
     * tidy outcome from inside it makes Spring throw {@code UnexpectedRollbackException} at commit
     * and the tidy outcome never reaches the client. By the time the exception arrives here the
     * rollback has happened and there is nothing left to unwind.
     *
     * <h2>This is the second concurrency check, and it is NOT the one the tests cover</h2>
     *
     * <p>{@code If-Match} catches the stale editor and has assertions of its own. This catches two
     * saves that both passed that check and then interleaved — which needs two transactions running
     * at once, and the suite has no mechanism for that. So <strong>this path is untested</strong>, and
     * it is recorded rather than presented as verified: the mutation that removes the early flush in
     * {@link CaseUpdateService} survives for exactly this reason.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Object> onWriteRace(OptimisticLockingFailureException race) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiProblem.conflict());
    }

    /**
     * Reads {@code If-Match}, tolerating the quoted form.
     *
     * <p>HTTP defines {@code If-Match} as carrying entity tags, which are quoted — {@code "7"} — and
     * a client library that formats it correctly is not wrong. The contract says the bare version, the
     * SPA sends the bare version, and both are accepted rather than one of them being a 400 nobody
     * can debug from the header they can see. A weak tag ({@code W/"7"}) is not accepted: weak
     * comparison is explicitly the wrong semantics for a lost-update check.
     */
    /**
     * The created case's own URL, built from the path this controller is mapped on rather than from a
     * request-relative builder: {@code fromCurrentRequest} would echo whatever host or proxy prefix the
     * caller sent, which is a header-controlled value in a response.
     */
    private static URI location(UUID projectId, UUID caseId) {
        return URI.create("/api/v1/projects/" + projectId + "/cases/" + caseId);
    }

    /**
     * Blank is the same as absent for {@code Idempotency-Key}.
     *
     * <p>{@code Idempotency-Key: } (empty) would otherwise be stored as {@code ''} and become a key
     * every other empty-header request in the project replays against — one case, permanently, for
     * every caller that sends the header wrong.
     */
    private static String trimToNull(String header) {
        if (header == null) {
            return null;
        }
        String trimmed = header.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

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
