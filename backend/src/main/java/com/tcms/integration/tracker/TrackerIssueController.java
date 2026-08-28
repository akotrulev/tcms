package com.tcms.integration.tracker;

import com.tcms.api.Problem;
import com.tcms.project.GateResult;
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
 * {@code /projects/{projectId}/tracker/issues} — reading somebody else's tracker as yourself, and
 * filing in it as yourself.
 *
 * <h2>The reads never answer 401, and the contract declares none</h2>
 *
 * <p>A caller with no connected identity gets a 200 whose body says {@code NOT_CONNECTED}. They are
 * authenticated to TCMS perfectly well; what they lack is a credential for a different product. A
 * 401 would trip the SPA's session-recovery path for a condition that has nothing to do with their
 * session — see {@link TrackerIssueSearchView}, and the contract, which declares no 401 on either
 * read for exactly this reason.
 *
 * <h2>The create is different, and the contract says so</h2>
 *
 * <p>A create cannot answer "unavailable" the way a read can: nothing happened, and the caller has
 * to know. So an unconnected caller is {@code 409 tracker-not-connected} and a tracker that refused
 * or did not answer is {@code 502 tracker-unavailable}, with the tracker's own message in
 * {@code detail} where it gave one. The ledger row records both.
 *
 * <p>{@code Idempotency-Key} on the create, on the same terms as every other create in this API — a
 * double-click files one issue. It matters more here than anywhere else: a duplicate is not a row
 * this product can delete, it is a second issue in somebody else's tracker.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/tracker/issues")
public class TrackerIssueController {

    private final TrackerIssueService issues;

    public TrackerIssueController(TrackerIssueService issues) {
        this.issues = issues;
    }

    /** Typeahead. {@code project:read} plus the caller's own identity for the binding's host. */
    @GetMapping
    public ResponseEntity<Object> search(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestParam String q,
            @RequestParam(required = false) UUID bindingId) {

        return read(issues.search(caller(jwt), projectId, q, bindingId));
    }

    /**
     * One issue, live.
     *
     * <p>The path variable is the tracker's <strong>stable id</strong> and never a key (004 D17).
     * Jira answers to a key here too and this product never sends one: a key moves and an id does
     * not, and nothing in this product stores or derives a key.
     */
    @GetMapping("/{issueId}")
    public ResponseEntity<Object> lookup(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable String issueId,
            @RequestParam(required = false) UUID bindingId) {

        return read(issues.lookup(caller(jwt), projectId, issueId, bindingId));
    }

    /** Files an issue as the caller. {@code defect:create} plus the caller's own identity. */
    @PostMapping
    public ResponseEntity<Object> file(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody TrackerIssueCreateInput input) {

        TrackerIssueService.Outcome outcome =
                issues.file(caller(jwt), projectId, input, idempotencyKey);

        return switch (outcome) {
            case TrackerIssueService.Outcome.Filed filed -> filed.created()
                    ? ResponseEntity.status(HttpStatus.CREATED).body(filed.view())
                    : ResponseEntity.ok(filed.view());
            case TrackerIssueService.Outcome.NotFound ignored ->
                    ResponseEntity.notFound().build();
            case TrackerIssueService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problem.forbidden(
                            "You do not have permission to file defects in this project.",
                            "defect:create", Problem.Scope.PROJECT));
            case TrackerIssueService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(Problem.validation(invalid.errors()));
            case TrackerIssueService.Outcome.NotConnected ignored ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(new Problem(
                            "tracker-not-connected",
                            "Connect your tracker account",
                            409,
                            "Filing an issue uses your own tracker account, and you have not "
                                    + "connected one for this tracker yet.",
                            null));
            case TrackerIssueService.Outcome.Unavailable unavailable ->
                    ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new Problem(
                            "tracker-unavailable",
                            "The tracker did not accept it",
                            502,
                            // The tracker's own words, redacted. This is the one tracker-facing
                            // message the product shows, and it is shown to the person whose
                            // credential made the call — never on a read, where it would say which
                            // of D16's four conditions applied.
                            unavailable.detail() == null
                                    ? "The tracker did not answer."
                                    : unavailable.detail(),
                            null));
        };
    }

    private static UUID caller(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    /**
     * The reads' two states, and there is no 403 arm.
     *
     * <p>{@code project:read} is the baseline every project member holds, so a caller who can see
     * the project holds it — {@link GateResult.Denied} is unreachable here in practice and the
     * contract declares no 403 on these reads. It is still handled rather than defaulted, because a
     * future grant shape that could deny it must not fall through to a 200.
     */
    private static ResponseEntity<Object> read(GateResult<?> gated) {
        return switch (gated) {
            case GateResult.Unreachable<?> ignored -> ResponseEntity.notFound().build();
            case GateResult.Denied<?> ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problem.forbidden(
                            "You do not have permission to read this project.",
                            "project:read", Problem.Scope.PROJECT));
            case GateResult.Allowed<?> allowed -> ResponseEntity.ok(allowed.value());
        };
    }
}
