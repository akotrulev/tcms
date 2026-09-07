package com.tcms.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.project.GateResult;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import com.tcms.tenancy.Outbox;
import com.tcms.tenancy.OutboxEvents;
import com.tcms.tenancy.TokenContext;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accepting and rejecting a proposal — <strong>the boundary itself</strong> (plan §6w.6).
 *
 * <h2>Accept is a create, and that sentence is the whole design</h2>
 *
 * <p>{@link CaseCloneService}'s shape, for its reason: this class reads the stored payload, which
 * is already a {@link CaseUpdateRequest}, and hands it to {@link CaseCreateService}. It writes no
 * {@code test_case} row, allocates no key, validates nothing and decides no version number. So
 * §6w.6's guarantees hold <em>by construction</em> rather than by being remembered here — the case
 * enters at {@code DRAFT} because {@code TestCase.create} decides the status and no payload field
 * can carry one, version 1 is written by {@link CaseVersions#initial}, and the author is the
 * accepting person. <strong>There is no path from a model to {@code Approved}</strong> because
 * there is no path from a model to a case.
 *
 * <h2>A CREDENTIAL MAY NOT DECIDE — and no permission can express that</h2>
 *
 * <p>This is the finding of the slice. The obvious gate on accept is {@code case:create}, and it is
 * not sufficient: the {@code Agent} role <em>holds</em> {@code case:create} (WIP plan §2.2 keeps it
 * there precisely because the accept path needs it), so an agent token could file a proposal and
 * accept it in two calls — the review surface defeated by the thing it exists to review.
 *
 * <p>No permission fixes this, and the reason is structural. A token's effective set is
 * <em>its owner's set intersected with its role's</em>, so every permission a token holds is one
 * its owner holds: permissions cannot tell Ana from Ana's agent, which is the entire design of the
 * intersection. The only fact that separates them is <strong>that a token is in play</strong> —
 * and that fact became readable outside {@code identity} exactly one slice ago, when §132 moved
 * {@link TokenContext} to {@code tenancy} so audit rows could attribute to it.
 *
 * <p>So the gate is {@code case:create} <em>and</em> {@link TokenContext#currentOrNull()} being
 * null. {@code TokenContext}'s own header names the condition under which reading it stops being a
 * mere bound — <em>"the moment anything reads this context to grant rather than to bound"</em> —
 * and reading it to <em>refuse</em> meets it from the third direction.
 *
 * <p>Rejecting is gated identically. A credential that could clear the queue could hide its own bad
 * proposals, which is the same defeat with a quieter shape.
 *
 * <h2>{@code verifies} is checked here and written elsewhere, and the split is forced</h2>
 *
 * <p>A proposal carries the blocks its case claims to verify (022 §5). Those claims were stored
 * unvalidated a week ago and this is the moment they become real, so this class resolves every one
 * of them against the project — through {@link DocumentationFacts}, before the case is created, as
 * ordinary field errors on {@code verifies[n].blockId} — and then <strong>does not write the
 * links</strong>, because a {@code doc_link} is {@code documentation}'s row and this module may not
 * reach it. It records {@link OutboxEvents#PROPOSAL_ACCEPTED} instead, in this transaction, and that
 * module's handler writes each link through the same path a person's link takes.
 *
 * <p>The order is the design and not an ordering of convenience: validating <em>after</em> the
 * create would leave a case that exists, claims twelve requirements and is linked to eleven, and the
 * reviewer would read the 400 as "nothing happened". Validating here means a proposal either becomes
 * a case with all of its links or stays a proposal.
 *
 * <h2>The accept is idempotent through the create path's own key</h2>
 *
 * <p>{@link CaseCreateService} is handed a synthetic {@code Idempotency-Key} derived from the
 * proposal id. Two reviewers pressing Accept at the same moment both find the row {@code PENDING},
 * and without this they would create two cases from one proposal — the exact duplicate the header
 * exists to prevent, arriving through a door no client can close. The key is deterministic because
 * the intent is: <em>this proposal becomes a case</em>, once.
 */
@Service
public class AiProposalDecisionService {

    /** The same key the create path asks for, because accepting is creating. */
    private static final String CREATE_CASE = "case:create";

    private static final String ACCEPT_ACTION = "ai_proposal.accepted";
    private static final String REJECT_ACTION = "ai_proposal.rejected";
    private static final String AUDIT_TARGET = "ai_proposal";

    /** Bounded so a rejection note cannot be used as unbounded storage on an unpruned table. */
    static final int MAX_NOTE = 2000;

    private final ProjectService projects;
    private final AiProposalRepository proposals;
    private final AiProposalService rendering;
    private final CaseCreateService creation;
    private final AuditEventRepository auditEvents;

    /**
     * The one file in this module permitted to name a {@code documentation} table — here to answer
     * whether every block a proposal claims is still a live statement of this project.
     */
    private final DocumentationFacts documentation;

    /**
     * How {@code documentation} is told to write the links, since it may not be called.
     *
     * <p>See {@link OutboxEvents#PROPOSAL_ACCEPTED}. The event is recorded inside this transaction,
     * so the notice and the decision commit together; what it carries is the list this class has
     * just resolved, so the consumer never has to ask a question it is not allowed to ask.
     */
    private final Outbox outbox;

    private final ObjectMapper json;
    private final Clock clock;

    @SuppressWarnings("checkstyle:ParameterNumber")
    AiProposalDecisionService(
            ProjectService projects,
            AiProposalRepository proposals,
            AiProposalService rendering,
            CaseCreateService creation,
            AuditEventRepository auditEvents,
            DocumentationFacts documentation,
            Outbox outbox,
            ObjectMapper json,
            Clock clock) {

        this.projects = projects;
        this.proposals = proposals;
        this.rendering = rendering;
        this.creation = creation;
        this.auditEvents = auditEvents;
        this.documentation = documentation;
        this.outbox = outbox;
        this.json = json;
        this.clock = clock;
    }

    /** What happened. */
    public sealed interface Outcome {

        /** Unreachable project, or no such proposal in it — one bodiless 404 (§6t). */
        record NotFound() implements Outcome {
        }

        /** Readable, and {@code case:create} not held. */
        record Forbidden() implements Outcome {
        }

        /**
         * Held, and the caller is a credential.
         *
         * <p>Distinct from {@link Forbidden} because the answer is: <em>this is a human's
         * decision</em>. Telling an agent it lacks a permission would be false — its owner holds it
         * — and would send whoever is debugging the agent to widen a grant that would change
         * nothing.
         */
        record CredentialRefused() implements Outcome {
        }

        /** The stored payload no longer validates as a case — the create path's field errors. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** Somebody already decided this the other way. 409. */
        record AlreadyDecided(ProposalStatus status) implements Outcome {
        }

        /** Accepted, and this is the case it became. 201. */
        record Accepted(TestCaseDetailView view) implements Outcome {
        }

        /**
         * This proposal had already been accepted, and here is the case again. 200, no
         * {@code Location} — nothing was created by this request.
         */
        record AlreadyAccepted(TestCaseDetailView view) implements Outcome {
        }

        /** Rejected, and the proposal as it now stands. */
        record Rejected(AiProposalView view) implements Outcome {
        }
    }

    /**
     * Accepts a proposal, creating the case through the ordinary create path.
     *
     * <p>One transaction with that create — {@code REQUIRED} joins — so the case, its steps, its v1
     * version row, the proposal's decision columns and both audit rows commit together. A crash
     * between them would otherwise leave a case nobody proposed or a proposal claiming a case that
     * does not exist, and {@code ai_proposal_accepted_names_its_case} refuses the second.
     */
    @Transactional
    public Outcome accept(UUID userId, UUID projectId, UUID proposalId) {
        return decide(userId, projectId, proposalId, proposal -> acceptOne(userId, proposal));
    }

    private Outcome acceptOne(UUID userId, AiProposal proposal) {
        if (proposal.getStatus() == ProposalStatus.REJECTED) {
            return new Outcome.AlreadyDecided(ProposalStatus.REJECTED);
        }
        if (proposal.getStatus() == ProposalStatus.ACCEPTED) {
            // A retry, or a second reviewer a moment late. Answer with the case rather than a
            // conflict: the state they asked for is the state it is in, and the useful reply is
            // the thing it became. §121's release-a-case-that-was-never-quarantined argument.
            return rendering
                    .renderAcceptedCase(proposal, userId)
                    .<Outcome>map(Outcome.AlreadyAccepted::new)
                    // The case has been deleted since. A 409 rather than a 404: the proposal is
                    // real and readable, and what cannot be done is decide it again.
                    .orElseGet(() -> new Outcome.AlreadyDecided(ProposalStatus.ACCEPTED));
        }

        CaseUpdateRequest payload = deserialize(proposal.getPayload());
        if (payload == null) {
            return new Outcome.Invalid(Map.of("testCase", List.of(
                    "This proposal can no longer be read as a test case. Reject it.")));
        }

        List<ProposalLink> claims = verifies(proposal);
        Map<String, List<String>> claimErrors = resolveClaims(proposal.getProjectId(), claims);
        if (!claimErrors.isEmpty()) {
            // BEFORE the create, so a proposal either becomes a case with all of its links or stays
            // a proposal. Afterwards would leave a case that exists, claims twelve requirements and
            // is linked to eleven — and the only signal would be a 400 the reviewer reads as
            // "nothing happened".
            return new Outcome.Invalid(claimErrors);
        }

        CaseCreateService.Outcome created = creation.create(
                userId,
                proposal.getProjectId(),
                payload,
                // See the class header: deterministic, so two reviewers accepting at once produce
                // one case rather than two.
                "ai-proposal:" + proposal.getId());

        return switch (created) {
            // Unreachable — this caller has already passed the same gate on the same project — but
            // mapped rather than defaulted, so a future change to the create path's gate cannot
            // turn into a silent success here.
            case CaseCreateService.Outcome.NotFound ignored -> new Outcome.NotFound();
            case CaseCreateService.Outcome.Forbidden ignored -> new Outcome.Forbidden();
            case CaseCreateService.Outcome.Invalid invalid -> new Outcome.Invalid(invalid.errors());
            case CaseCreateService.Outcome.Replayed replayed -> {
                // The create path had already seen this key: a previous accept created the case and
                // the decision columns did not commit with it, which the shared transaction should
                // make impossible. Recorded as accepted now rather than left half-decided.
                yield new Outcome.Accepted(record(userId, proposal, claims, replayed.view()));
            }
            case CaseCreateService.Outcome.Created fresh ->
                    new Outcome.Accepted(record(userId, proposal, claims, fresh.view()));
        };
    }

    private TestCaseDetailView record(
            UUID userId,
            AiProposal proposal,
            List<ProposalLink> claims,
            TestCaseDetailView view) {

        Instant now = clock.instant();
        proposal.accepted(view.id(), userId, now);
        proposals.save(proposal);

        auditEvents.save(AuditEvent.record(
                proposal.getProjectId(),
                userId,
                ACCEPT_ACTION,
                AUDIT_TARGET,
                List.of(proposal.getId()),
                // Beside the create path's own case.created row, never instead of it — the case is
                // a case like any other and its history must say so. What this line adds is the
                // provenance: which case, and what the proposer had claimed about it.
                "{\"caseKey\":\"" + view.key() + "\",\"verdictSource\":\""
                        + proposal.getVerdictSource() + "\",\"verifies\":" + claims.size() + "}",
                now));

        if (!claims.isEmpty()) {
            // Inside this transaction, which Outbox.record refuses to be called outside of: the
            // event and the decision it describes commit together, so there is no window where a
            // case exists claiming twelve requirements and the notice to link them was lost.
            //
            // Nothing when nothing was claimed. An event whose handler would iterate an empty list
            // is a whole delivery cycle to do nothing, and — worse for whoever is reading the table
            // — a row per accepted proposal that says something happened.
            outbox.record(
                    ProposalAcceptedEvent.AGGREGATE,
                    proposal.getId(),
                    OutboxEvents.PROPOSAL_ACCEPTED,
                    new ProposalAcceptedEvent(
                            proposal.getId(),
                            proposal.getProjectId(),
                            view.id(),
                            userId,
                            claims));
        }

        return view;
    }

    /**
     * Resolves every claimed target against the project, and answers the field errors for the
     * misses.
     *
     * <h2>Here, at accept, and nowhere else</h2>
     *
     * <p>The claim was stored unvalidated with the payload beside it, on {@link AiProposalService}'s
     * argument: whether a block exists is a question about the project's <em>current</em>
     * documentation, and a proposal may sit in the queue for a week. This is the one moment it
     * becomes real, so it is the one moment it is asked — and the answer arrives as ordinary field
     * errors on the reviewer's screen rather than as an agent's batch refused a week earlier.
     *
     * <p>Keyed {@code verifies[n].blockId}, indexed, because a proposal may claim twelve
     * requirements and <em>one of them is gone</em> is not something a reviewer can act on. The page
     * mismatch is reported on the same key rather than on {@code pageId}: what the reviewer is being
     * told is that this claim does not name a statement of this project's documentation, and which
     * half of the pair is wrong is a distinction only the filing agent could act on.
     *
     * <p>Through {@link DocumentationFacts}, which is the one file in this module permitted to name
     * a {@code documentation} table — a JPA association or a repository of its own would be the
     * second, and the module rule is that a reviewer finds every crossing by opening one class.
     */
    private Map<String, List<String>> resolveClaims(UUID projectId, List<ProposalLink> claims) {
        if (claims.isEmpty()) {
            return Map.of();
        }

        Set<UUID> blockIds = new LinkedHashSet<>();
        for (ProposalLink claim : claims) {
            blockIds.add(claim.blockId());
        }
        Map<UUID, UUID> pageByBlock = documentation.pagesOfBlocks(projectId, blockIds);

        Map<String, List<String>> errors = new LinkedHashMap<>();
        for (int index = 0; index < claims.size(); index++) {
            ProposalLink claim = claims.get(index);
            UUID actualPage = pageByBlock.get(claim.blockId());
            if (actualPage == null) {
                errors.put("verifies[" + index + "].blockId", List.of(
                        "This requirement is no longer in this project's documentation. Remove the "
                                + "claim, or reject the proposal."));
            } else if (!actualPage.equals(claim.pageId())) {
                errors.put("verifies[" + index + "].blockId", List.of(
                        "This block is not on the page the proposal named. Remove the claim, or "
                                + "reject the proposal."));
            }
        }
        return errors;
    }

    /**
     * What the proposal claimed, out of its column — empty for a row that claimed nothing, and for
     * an unreadable one.
     *
     * <p>{@link #deserialize}'s reason applied to the second JSON column: a claim list that cannot
     * be parsed must not stop the reviewer accepting or rejecting the proposal. What it costs is the
     * links, which is visible on the case, rather than a 500 on a decision.
     */
    private List<ProposalLink> verifies(AiProposal proposal) {
        if (proposal.getVerifies() == null) {
            return List.of();
        }
        try {
            return json.readValue(
                    proposal.getVerifies(), new TypeReference<List<ProposalLink>>() { });
        } catch (JsonProcessingException unreadable) {
            return List.of();
        }
    }

    /**
     * Rejects a proposal, keeping the row.
     *
     * <p>§6w.6: a generation profile whose proposals are rejected four times in five is a
     * measurable fact, and proposals that vanish when dismissed make it unknowable. The note is
     * <strong>optional</strong>, unlike {@code case_flake}'s quarantine reason, and the difference
     * is what the row goes on to mean: a quarantine is live debt somebody must eventually clear and
     * cannot without knowing why, while a rejected proposal is finished — the reason improves a
     * signal rather than unblocking a person.
     */
    @Transactional
    public Outcome reject(UUID userId, UUID projectId, UUID proposalId, String note) {
        return decide(userId, projectId, proposalId, proposal -> rejectOne(userId, proposal, note));
    }

    private Outcome rejectOne(UUID userId, AiProposal proposal, String note) {
        if (proposal.getStatus() == ProposalStatus.ACCEPTED) {
            return new Outcome.AlreadyDecided(ProposalStatus.ACCEPTED);
        }
        if (note != null && note.length() > MAX_NOTE) {
            return new Outcome.Invalid(Map.of("note",
                    List.of("Keep the note under " + MAX_NOTE + " characters.")));
        }
        if (proposal.getStatus() == ProposalStatus.REJECTED) {
            // Already in the asked-for state. Answered, not conflicted — and the first rejection's
            // note is kept, because overwriting it would let a second click erase the reason the
            // decision was actually made for.
            return new Outcome.Rejected(rendering.renderOne(proposal));
        }

        Instant now = clock.instant();
        proposal.rejected(userId, note, now);
        proposals.save(proposal);

        auditEvents.save(AuditEvent.record(
                proposal.getProjectId(),
                userId,
                REJECT_ACTION,
                AUDIT_TARGET,
                List.of(proposal.getId()),
                "{\"verdictSource\":\"" + proposal.getVerdictSource() + "\"}",
                now));

        return new Outcome.Rejected(rendering.renderOne(proposal));
    }

    /**
     * The gate both decisions share, in one place — so the credential refusal cannot be present on
     * one verb and missing on the other, which is the shape §133 and §134 each found on a flag read
     * at four sites.
     *
     * <h2>The order of the three questions, and why it is this one</h2>
     *
     * <ol>
     *   <li><strong>Visibility and permission first</strong>, through the ordinary write gate: a
     *       caller who cannot see the project must not learn from a differently-worded refusal that
     *       the endpoint even distinguishes credentials.</li>
     *   <li><strong>Then the credential check</strong>, before the proposal is looked up — so an
     *       agent cannot use the 404-versus-403 difference to discover which proposal ids exist,
     *       which is the enumeration §6t forbids arriving through the one endpoint that refuses it
     *       for a different reason.</li>
     *   <li><strong>Then the row.</strong></li>
     * </ol>
     */
    private Outcome decide(
            UUID userId,
            UUID projectId,
            UUID proposalId,
            Function<AiProposal, Outcome> decision) {

        GateResult<Outcome> gated = projects.writeGranted(userId, projectId, CREATE_CASE, project -> {
            if (TokenContext.currentOrNull() != null) {
                return new Outcome.CredentialRefused();
            }
            return proposals
                    .findScopedById(proposalId, project.getId())
                    .map(decision)
                    .orElseGet(Outcome.NotFound::new);
        });

        return switch (gated) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    private CaseUpdateRequest deserialize(String payload) {
        try {
            return json.readValue(payload, CaseUpdateRequest.class);
        } catch (JsonProcessingException e) {
            // AiProposalService#deserialize's reason: a payload stored before a field left
            // CaseUpdateRequest. Null here becomes a field error telling the reviewer to reject,
            // which is the only decision left that can be made about it.
            return null;
        }
    }
}
