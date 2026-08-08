package com.tcms.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.project.GateResult;
import com.tcms.project.Project;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.TokenContext;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Filing and reading proposals — {@code ai_proposal}'s writer, and the surface a generation agent
 * reaches (plan §6w.6, MCP WIP plan phase 6).
 *
 * <h2>What this class does not do</h2>
 *
 * <p>It does not create cases. {@link AiProposalDecisionService} owns the accept path and delegates
 * to {@link CaseCreateService}, so nothing anywhere in this module writes a {@code test_case} row
 * except the one class that has always written them.
 *
 * <h2>The payload is stored unvalidated, on purpose</h2>
 *
 * <p>A proposal is a suggestion, and validating it at file time would mean answering <em>is this a
 * legal case</em> twice — once now and once at accept, possibly a week apart, against a project
 * whose suites and vocabularies have moved in between. The second answer is the one that matters,
 * because it is the one the case is created under. So this endpoint refuses only what it can judge
 * without the project's state: a missing body, a blank title, a payload over the ceiling.
 *
 * <p>The consequence is real and is the correct trade: a proposal can be filed that cannot be
 * accepted, and the reviewer finds out when they press Accept and get the field errors. That is a
 * legible failure with the fix in it. The alternative — refusing at file time — throws away an
 * agent's whole batch because one suite was renamed, and gives the reviewer nothing to look at.
 *
 * <h2>The gates</h2>
 *
 * <p>Filing costs {@code case:create}: it is the arm the {@code Agent} role exists for, and the
 * WIP plan §2.2 keeps that key in the grant for exactly this. Reading costs project visibility
 * alone, like every other read in this module — {@code case:read} is held by all six roles
 * including Viewer, so gating on it would refuse nobody while adding a second style of read gate to
 * a package that has one.
 */
@Service
public class AiProposalService {

    /** Roles spec §A5, and the WIP plan §2.2's reason for keeping it in the {@code Agent} grant. */
    private static final String CREATE_CASE = "case:create";

    private static final String AUDIT_ACTION = "ai_proposal.filed";
    private static final String AUDIT_TARGET = "ai_proposal";

    /**
     * The longest {@code Idempotency-Key} this endpoint will store — the eighth declaration of a
     * constant seven services already carry, and {@code IdempotencyKeyCapTest} is what keeps them
     * equal.
     *
     * <p>The extraction is refused again, on {@code V17}'s argument rather than on inertia: the
     * alternative to a per-table column is a general {@code idempotent_request} table storing the
     * <em>rendered response</em>, which goes stale the moment the row it describes is edited. What
     * is new here is the caller: this is the first of the eight whose client has no screen at all.
     */
    private static final int MAX_IDEMPOTENCY_KEY = 200;

    /**
     * The ceiling on one proposal, serialized — {@link CaseDraftService#MAX_CONTENT_BYTES} exactly,
     * and for a sharper version of its reason. The content is deliberately unvalidated, the caller
     * is a program rather than a person, and nothing prunes this table; a generation loop with a
     * runaway payload would otherwise write into it unboundedly.
     */
    static final int MAX_PAYLOAD_BYTES = 256 * 1024;

    /** {@code PageMeta}'s ceiling, as everywhere else. */
    private static final int MAX_PAGE_SIZE = 200;

    private final ProjectService projects;
    private final AiProposalRepository proposals;
    private final TestCaseRepository cases;
    private final SuiteRepository suites;
    private final CaseRenderer renderer;
    private final CaseOwners people;
    private final AuditEventRepository auditEvents;

    /**
     * The application's mapper, injected for {@link CaseDraftService}'s reason: a private
     * {@code new ObjectMapper()} is a second serialization configuration, and the stored payload
     * would drift from the shape the API serves the moment either is configured.
     */
    private final ObjectMapper json;

    private final Clock clock;

    @SuppressWarnings("checkstyle:ParameterNumber")
    AiProposalService(
            ProjectService projects,
            AiProposalRepository proposals,
            TestCaseRepository cases,
            SuiteRepository suites,
            CaseRenderer renderer,
            CaseOwners people,
            AuditEventRepository auditEvents,
            ObjectMapper json,
            Clock clock) {

        this.projects = projects;
        this.proposals = proposals;
        this.cases = cases;
        this.suites = suites;
        this.renderer = renderer;
        this.people = people;
        this.auditEvents = auditEvents;
        this.json = json;
        this.clock = clock;
    }

    /** What happened, across the three verbs here. */
    public sealed interface Outcome {

        /** Unreachable project, or no such proposal in it — one bodiless 404 (§6t). */
        record NotFound() implements Outcome {
        }

        /** Readable, and {@code case:create} not held. */
        record Forbidden() implements Outcome {
        }

        /** Field name to messages, spelled as the input schema spells them. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** A new proposal. 201. */
        record Filed(AiProposalView view) implements Outcome {
        }

        /** A retry of a file that already succeeded. 200, and no {@code Location}. */
        record Replayed(AiProposalView view) implements Outcome {
        }

        /** One proposal. */
        record Found(AiProposalView view) implements Outcome {
        }

        /** A page of the queue. */
        record Listed(AiProposalPageView page) implements Outcome {
        }
    }

    /**
     * Files a generated case for review.
     *
     * @param idempotencyKey the request's {@code Idempotency-Key} header, or null when absent
     */
    @Transactional
    public Outcome file(
            UUID userId, UUID projectId, AiProposalInput request, String idempotencyKey) {

        GateResult<Outcome> gated = projects.writeGranted(userId, projectId, CREATE_CASE,
                project -> store(userId, project, request, idempotencyKey));

        return switch (gated) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    private Outcome store(
            UUID userId, Project project, AiProposalInput request, String idempotencyKey) {

        UUID companyId = CompanyContext.require();

        // FIRST, before validating anything — CaseCreateService's ordering and its reason: a retry
        // of a file that succeeded must answer with the proposal it made even if the payload has
        // since drifted, because the client is asking "did my intent land".
        if (idempotencyKey != null) {
            if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY) {
                return new Outcome.Invalid(Map.of("Idempotency-Key",
                        List.of("Send a key of at most " + MAX_IDEMPOTENCY_KEY + " characters.")));
            }
            Optional<AiProposal> already =
                    proposals.findByIdempotencyKey(project.getId(), idempotencyKey);
            if (already.isPresent()) {
                return new Outcome.Replayed(render(companyId, already.get()));
            }
        }

        Map<String, List<String>> errors = validate(request);
        if (!errors.isEmpty()) {
            return new Outcome.Invalid(errors);
        }

        String payload = serialize(request.testCase());
        if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            // Bytes on the serialized payload, not characters on the title — CaseDraftService's
            // measurement, for its reason: the column stores bytes and non-ASCII prose costs up to
            // four of them per character, so a character bound is a different ceiling per language.
            return new Outcome.Invalid(Map.of("testCase",
                    List.of("This proposal is too large to file. Send fewer steps.")));
        }

        TokenContext.ApiTokenPrincipal token = TokenContext.currentOrNull();
        Instant now = clock.instant();

        AiProposal filed = AiProposal.file(
                project.getId(),
                request.testCase().suiteId(),
                payload,
                request.testCase().title().trim(),
                // The claim as sent, and NOT a source — AiProposal.file derives that, which is the
                // one place §6w.1a's rule is expressible. See VerdictSource.
                request.verdict(),
                request.grounding() == null ? null : request.grounding().toString(),
                userId,
                token == null ? null : token.tokenId(),
                idempotencyKey,
                now);

        proposals.saveAndFlush(filed);

        auditEvents.save(AuditEvent.record(
                project.getId(),
                userId,
                AUDIT_ACTION,
                AUDIT_TARGET,
                List.of(filed.getId()),
                // The title and the verdict source, not the payload: the row itself holds the whole
                // proposal and this table has different retention. The source is here because it is
                // the fact an auditor is looking for — whether a claim of grounding came from
                // outside — and it is derived rather than sent, so recording it is recording a
                // decision this service made.
                "{\"title\":" + quoted(filed.getTitle())
                        + ",\"verdictSource\":\"" + filed.getVerdictSource() + "\"}",
                now));

        return new Outcome.Filed(render(companyId, filed));
    }

    /**
     * What can be judged without the project's state. Everything else waits for accept — see the
     * class header for why that is the trade and not an omission.
     */
    private Map<String, List<String>> validate(AiProposalInput request) {
        Map<String, List<String>> errors = new java.util.LinkedHashMap<>();
        if (request == null || request.testCase() == null) {
            errors.put("testCase", List.of("Send the proposed test case."));
            return errors;
        }
        String title = request.testCase().title();
        if (title == null || title.isBlank()) {
            // Checked here as well as in CaseEdit, and this is not the duplicate validation the
            // class header refuses: `title` is copied into a NOT NULL column with its own CHECK, so
            // a blank one is a constraint violation at file time rather than a field error at
            // accept time. The queue also has nothing to render a row with.
            errors.put("testCase.title", List.of("A proposal needs a title."));
        }
        return errors;
    }

    /** The review queue. */
    @Transactional(readOnly = true)
    public Outcome list(
            UUID userId, UUID projectId, ProposalStatus status, int page, int size) {

        int effectiveSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        int effectivePage = Math.max(page, 0);
        ProposalStatus filter = status == null ? ProposalStatus.PENDING : status;

        return projects
                .readVisible(userId, projectId, project -> {
                    UUID companyId = CompanyContext.require();
                    List<AiProposal> rows = proposals.findByStatus(
                            project.getId(), filter,
                            PageRequest.of(effectivePage, effectiveSize));
                    return (Outcome) new Outcome.Listed(new AiProposalPageView(
                            summarise(companyId, rows),
                            effectivePage,
                            effectiveSize,
                            proposals.countByStatus(project.getId(), filter)));
                })
                .orElseGet(Outcome.NotFound::new);
    }

    /** One proposal, with its payload and its grounding. */
    @Transactional(readOnly = true)
    public Outcome findOne(UUID userId, UUID projectId, UUID proposalId) {
        return projects
                .readVisible(userId, projectId, project -> proposals
                        .findScopedById(proposalId, project.getId())
                        .map(row -> (Outcome) new Outcome.Found(
                                render(CompanyContext.require(), row)))
                        .orElseGet(Outcome.NotFound::new))
                .orElseGet(Outcome.NotFound::new);
    }

    private List<AiProposalSummaryView> summarise(UUID companyId, List<AiProposal> rows) {
        Set<UUID> authorIds = new HashSet<>();
        for (AiProposal row : rows) {
            authorIds.add(row.getCreatedBy());
        }
        // One resolution for the page, never one per row — CaseOwners' rule.
        Map<UUID, UserRefView> authors = people.resolve(companyId, authorIds);

        List<AiProposalSummaryView> summaries = new ArrayList<>();
        for (AiProposal row : rows) {
            summaries.add(new AiProposalSummaryView(
                    row.getId(),
                    row.getTitle(),
                    row.getSuiteId(),
                    row.getVerdict(),
                    row.getVerdictSource(),
                    row.getStatus(),
                    row.getCreatedAt(),
                    authors.get(row.getCreatedBy()),
                    row.getCreatedTokenId() != null,
                    stepCount(row)));
        }
        return summaries;
    }

    /**
     * How many steps the proposal carries, read off the stored payload.
     *
     * <p>Not a column: it is derivable from a write-once payload, and a stored count is a second
     * fact that can disagree with the first. A malformed payload counts as zero rather than
     * throwing — the queue must render a row whose content cannot be parsed, because that row is
     * exactly the one a reviewer needs to reject.
     */
    private int stepCount(AiProposal row) {
        CaseUpdateRequest parsed = deserialize(row.getPayload());
        return parsed == null || parsed.steps() == null ? 0 : parsed.steps().size();
    }

    /**
     * One stored proposal, rendered — for {@link AiProposalDecisionService}, which decides rows and
     * must answer with the same shape this endpoint serves.
     *
     * <p>Package-private and taking the entity rather than an id, so the decision service renders
     * the row it just wrote instead of re-reading it. A second read inside the same transaction
     * would return the same object; taking it directly says so.
     */
    AiProposalView renderOne(AiProposal row) {
        return render(CompanyContext.require(), row);
    }

    /**
     * The case an accepted proposal became, as the case endpoints serve it — or empty if it has
     * since been deleted.
     */
    Optional<TestCaseDetailView> renderAcceptedCase(AiProposal row, UUID userId) {
        if (row.getAcceptedCaseId() == null) {
            return Optional.empty();
        }
        UUID companyId = CompanyContext.require();
        return cases
                .findLiveInProject(row.getProjectId(), row.getAcceptedCaseId())
                .map(created -> renderer.render(
                        companyId, userId, row.getProjectId(), created,
                        suites.findLiveForProject(row.getProjectId())));
    }

    private AiProposalView render(UUID companyId, AiProposal row) {
        Set<UUID> peopleIds = new HashSet<>();
        peopleIds.add(row.getCreatedBy());
        if (row.getDecidedBy() != null) {
            peopleIds.add(row.getDecidedBy());
        }
        Map<UUID, UserRefView> resolved = people.resolve(companyId, peopleIds);

        AiProposalView.AcceptedCaseView accepted = null;
        if (row.getAcceptedCaseId() != null) {
            accepted = cases
                    .findLiveInProject(row.getProjectId(), row.getAcceptedCaseId())
                    .map(created -> new AiProposalView.AcceptedCaseView(
                            created.getId(), created.getKey()))
                    // Null when the case has since been deleted — findLiveInProject excludes
                    // soft-deleted rows, which is the same answer the case's own detail endpoint
                    // gives. The proposal stays ACCEPTED and stops offering a link to a case that
                    // is no longer in the repository; it does not become pending again, because
                    // the decision was made and deleting the case is a second decision.
                    .orElse(null);
        }

        return new AiProposalView(
                row.getId(),
                row.getProjectId(),
                row.getSuiteId(),
                row.getTitle(),
                deserialize(row.getPayload()),
                row.getVerdict(),
                row.getVerdictSource(),
                grounding(row),
                row.getStatus(),
                row.getCreatedAt(),
                resolved.get(row.getCreatedBy()),
                row.getCreatedTokenId() != null,
                row.getDecidedAt(),
                row.getDecidedBy() == null ? null : resolved.get(row.getDecidedBy()),
                row.getDecisionNote(),
                accepted);
    }

    private JsonNode grounding(AiProposal row) {
        if (row.getGrounding() == null) {
            return null;
        }
        try {
            return json.readTree(row.getGrounding());
        } catch (JsonProcessingException e) {
            // Unreachable: the column is JSONB, so the database refused anything unparseable on the
            // way in. Null rather than a 500 if it ever happens — a reviewer who cannot see the
            // evidence must still be able to reject the proposal.
            return null;
        }
    }

    private String serialize(CaseUpdateRequest request) {
        try {
            return json.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            // The request came in as JSON, so it serializes back. Unchecked rather than swallowed:
            // a silent empty payload would file a proposal with no case in it.
            throw new IllegalStateException("A proposal payload failed to serialize", e);
        }
    }

    private CaseUpdateRequest deserialize(String payload) {
        try {
            return json.readValue(payload, CaseUpdateRequest.class);
        } catch (JsonProcessingException e) {
            // Reachable in one direction only: a payload stored before a field was removed from
            // CaseUpdateRequest. Null, so the proposal still renders and can still be rejected —
            // the review surface shows an unreadable proposal, which is a decidable state, rather
            // than an error page, which is not.
            return null;
        }
    }

    /** JSON string escaping for the audit detail — {@code CaseVersions}' helper, one field wide. */
    private String quoted(String value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A title failed to serialize", e);
        }
    }
}
