package com.tcms.repository;

import com.tcms.api.Problem;
import java.net.URI;
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
 * {@code /projects/{projectId}/ai-proposals} — the review queue for generated cases (plan §6w.6).
 *
 * <h2>Two callers, opposite directions</h2>
 *
 * <p>The {@code POST} is an agent's: it files a case it generated. Everything else is a person's:
 * the queue, one proposal, and the two decisions. That asymmetry is the whole point of the resource
 * — it is where a program's output waits for a human — and it is why the decision endpoints refuse
 * a credential outright ({@link AiProposalDecisionService}).
 *
 * <h2>404 before 403, as everywhere</h2>
 *
 * <p>An unreachable project and a proposal that is not in it are both a bodiless 404 (§6t). A 403 is
 * reached only after the read gate has passed.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/ai-proposals")
public class AiProposalController {

    /** The same header the six create endpoints take; spelled here for {@code CaseController}'s reason. */
    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final AiProposalService proposals;
    private final AiProposalDecisionService decisions;

    AiProposalController(AiProposalService proposals, AiProposalDecisionService decisions) {
        this.proposals = proposals;
        this.decisions = decisions;
    }

    /**
     * The review queue, defaulting to {@code PENDING}.
     *
     * <p>The default is the status, not "all": a queue is a list of things waiting, and a first page
     * mixing in every decided proposal from the project's history is a list nobody can work from.
     * A client wanting the rejection rate asks for the status it wants.
     */
    @GetMapping
    public ResponseEntity<Object> list(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestParam(required = false) ProposalStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {

        AiProposalService.Outcome outcome = proposals.list(
                UUID.fromString(jwt.getSubject()), projectId, status, page, size);

        return switch (outcome) {
            case AiProposalService.Outcome.Listed listed -> ResponseEntity.ok(listed.page());
            default -> ResponseEntity.notFound().build();
        };
    }

    /** One proposal, with its payload and whatever grounding evidence came with it. */
    @GetMapping("/{proposalId}")
    public ResponseEntity<Object> findOne(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID proposalId) {

        AiProposalService.Outcome outcome =
                proposals.findOne(UUID.fromString(jwt.getSubject()), projectId, proposalId);

        return switch (outcome) {
            case AiProposalService.Outcome.Found found -> ResponseEntity.ok(found.view());
            default -> ResponseEntity.notFound().build();
        };
    }

    /**
     * Files a generated case for review — the one arm a generation agent calls.
     *
     * <p>201 with a {@code Location} for a new proposal, 200 with none for a replay: the create
     * endpoints' distinction, and it matters more here, because the caller is a program deciding
     * whether to retry the rest of a batch.
     */
    @PostMapping
    public ResponseEntity<Object> file(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody AiProposalInput request) {

        AiProposalService.Outcome outcome = proposals.file(
                UUID.fromString(jwt.getSubject()), projectId, request,
                CaseController.trimToNull(idempotencyKey));

        return switch (outcome) {
            case AiProposalService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            case AiProposalService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problem.forbidden(
                            "You do not have permission to propose test cases in this project."));
            case AiProposalService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(Problem.validation(invalid.errors()));
            case AiProposalService.Outcome.Filed filed -> ResponseEntity
                    .created(location(projectId, filed.view().id()))
                    .body(filed.view());
            // Not 201, and no Location: nothing was filed by THIS request.
            case AiProposalService.Outcome.Replayed replayed ->
                    ResponseEntity.ok(replayed.view());
            default -> ResponseEntity.notFound().build();
        };
    }

    /**
     * Accepts a proposal — the case is created by the ordinary create path.
     *
     * <p>201 with a {@code Location} pointing at <strong>the case</strong>, not at the proposal:
     * what this request created is a case, and that is where the reviewer goes next.
     *
     * <p>The 403 has two bodies and they say different things. {@code case:create} not held is the
     * ordinary refusal; a credential asking is told that this is a person's decision, because
     * telling an agent it lacks a permission would be false — its owner holds it — and would send
     * whoever is debugging that agent to widen a grant that changes nothing.
     */
    @PostMapping("/{proposalId}/accept")
    public ResponseEntity<Object> accept(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID proposalId) {

        AiProposalDecisionService.Outcome outcome =
                decisions.accept(UUID.fromString(jwt.getSubject()), projectId, proposalId);

        return switch (outcome) {
            case AiProposalDecisionService.Outcome.NotFound ignored ->
                    ResponseEntity.notFound().build();
            case AiProposalDecisionService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problem.forbidden(
                            "You do not have permission to create test cases in this project."));
            case AiProposalDecisionService.Outcome.CredentialRefused ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problem.forbidden(
                            "Accepting a generated case is a person's decision, so it cannot be "
                                    + "done with an API token. Sign in and review it."));
            case AiProposalDecisionService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(Problem.validation(invalid.errors()));
            case AiProposalDecisionService.Outcome.AlreadyDecided decided ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(Problem.conflict(
                            "Already decided",
                            decided.status() == ProposalStatus.REJECTED
                                    ? "This proposal was rejected. Reload the queue."
                                    : "This proposal was accepted into a case that has since been "
                                            + "deleted."));
            case AiProposalDecisionService.Outcome.Accepted accepted -> ResponseEntity
                    .created(caseLocation(projectId, accepted.view().id()))
                    .body(accepted.view());
            // Not 201, and no Location: nothing was created by THIS request.
            case AiProposalDecisionService.Outcome.AlreadyAccepted already ->
                    ResponseEntity.ok(already.view());
            case AiProposalDecisionService.Outcome.Rejected ignored ->
                    ResponseEntity.notFound().build();
        };
    }

    /** Rejects a proposal, keeping the row and the reason. */
    @PostMapping("/{proposalId}/reject")
    public ResponseEntity<Object> reject(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID proposalId,
            @RequestBody ProposalRejectionRequest request) {

        AiProposalDecisionService.Outcome outcome = decisions.reject(
                UUID.fromString(jwt.getSubject()), projectId, proposalId, request.note());

        return switch (outcome) {
            case AiProposalDecisionService.Outcome.NotFound ignored ->
                    ResponseEntity.notFound().build();
            case AiProposalDecisionService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problem.forbidden(
                            "You do not have permission to decide proposals in this project."));
            case AiProposalDecisionService.Outcome.CredentialRefused ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problem.forbidden(
                            "Rejecting a generated case is a person's decision, so it cannot be "
                                    + "done with an API token. Sign in and review it."));
            case AiProposalDecisionService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(Problem.validation(invalid.errors()));
            case AiProposalDecisionService.Outcome.AlreadyDecided ignored ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(Problem.conflict(
                            "Already accepted",
                            "This proposal has already become a case. Reload the queue."));
            case AiProposalDecisionService.Outcome.Rejected rejected ->
                    ResponseEntity.ok(rejected.view());
            case AiProposalDecisionService.Outcome.Accepted ignored ->
                    ResponseEntity.notFound().build();
            case AiProposalDecisionService.Outcome.AlreadyAccepted ignored ->
                    ResponseEntity.notFound().build();
        };
    }

    private static URI caseLocation(UUID projectId, UUID caseId) {
        return URI.create("/api/v1/projects/" + projectId + "/cases/" + caseId);
    }

    private static URI location(UUID projectId, UUID proposalId) {
        return URI.create(
                "/api/v1/projects/" + projectId + "/ai-proposals/" + proposalId);
    }
}
