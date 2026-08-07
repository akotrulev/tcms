package com.tcms.execution;

import com.tcms.project.GateResult;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import com.tcms.tenancy.CompanyContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <em>"This test name means this case"</em> — {@code automation_key_mapping}'s writer, and §6c's
 * third ladder step.
 *
 * <p>This endpoint is what makes {@link AutomationMatcher}'s decision not to auto-create case stubs
 * a design rather than a gap. An unmatched test comes back on the ingestion response with its
 * {@code normalizedName}; a person decides which case it means; this records it; and every
 * subsequent build matches — through renames of the case, the suite and the project, because the
 * row is a fact about the pair rather than about either side.
 *
 * <h2>The gate is {@code case:edit}, and Automation Service deliberately does not hold it</h2>
 *
 * <p>Confirming a mapping is the same statement as writing the case's {@code automation_key}: it
 * decides where that test's results land. It belongs to the same gate, and no key is invented for it
 * — §67's argument, now the eleventh time.
 *
 * <p>What that buys is worth stating, because it is the security property of the whole ingestion
 * feature. Roles spec §A6 grants a CI token {@code result:submit} and {@code run:create} and not
 * {@code case:edit}, so a <strong>leaked token can post results and cannot re-point where anybody's
 * results land</strong>. Without the split, a token could bind every failing test's name to a
 * throwaway case and launder a red suite into a green one, leaving a coverage dashboard that is
 * confidently wrong and an audit log that records nothing unusual.
 *
 * <h2>Re-confirming MOVES the row rather than refusing</h2>
 *
 * <p>{@code uq_automation_mapping (company_id, normalized_test_name)} makes a second row
 * impossible, so the choice is between moving and a 409. Moving, because a 409 makes correcting a
 * mis-binding a delete-then-create — two requests with a window in the middle during which the CI
 * job silently stops matching, on a name somebody is in the middle of fixing.
 * {@link AutomationKeyMapping}'s header carries the consequence: the key is the company's, so a
 * second project can claim a name the first one had, and the last confirmation wins.
 */
@Service
public class AutomationMappingService {

    /** Roles spec §A5 — see the class header for why this and not a new key. */
    private static final String EDIT_CASE = "case:edit";

    /** {@code case:read}, which every project member holds — a list, not a write. */
    private static final String READ_CASE = "case:read";

    private static final String AUDIT_TARGET = "automation_key_mapping";

    /**
     * The cap on a test name.
     *
     * <p>{@code normalized_test_name} is unbounded {@code TEXT} and this is the only writer, so the
     * bound has to be here. A parameterised Cucumber scenario whose name embeds a data table is
     * genuinely long; a megabyte is not a test name, and an unbounded unique index over one is a
     * B-tree page overflow reported as a 500.
     */
    static final int MAX_TEST_NAME = 500;

    private final ProjectService projects;
    private final AutomationKeyMappingRepository mappings;
    private final CaseFacts cases;
    private final AuditEventRepository auditEvents;
    private final Clock clock;

    public AutomationMappingService(
            ProjectService projects,
            AutomationKeyMappingRepository mappings,
            CaseFacts cases,
            AuditEventRepository auditEvents,
            Clock clock) {

        this.projects = projects;
        this.mappings = mappings;
        this.cases = cases;
        this.auditEvents = auditEvents;
        this.clock = clock;
    }

    /** {@code ApiTokenService.Outcome}'s shapes. */
    public sealed interface Outcome {

        record NotFound() implements Outcome {
        }

        record Forbidden() implements Outcome {
        }

        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        record Listed(List<AutomationMappingView> views) implements Outcome {
        }

        /** Created or re-pointed. 200 either way — see the class header on why this is not a 409. */
        record Confirmed(AutomationMappingView view) implements Outcome {
        }

        record Deleted() implements Outcome {
        }
    }

    /** This project's mappings, by test name. */
    @Transactional(readOnly = true)
    public Outcome list(UUID userId, UUID projectId) {
        GateResult<Outcome> gated = projects.readGranted(userId, projectId, READ_CASE,
                project -> new Outcome.Listed(
                        render(project.getId(),
                                mappings.findByProjectIdOrderByNormalizedTestNameAsc(
                                        project.getId()))));

        return switch (gated) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    /** Confirms — or re-points — the binding for one test name. */
    @Transactional
    public Outcome confirm(UUID userId, UUID projectId, AutomationMappingRequest request) {
        GateResult<Outcome> gated = projects.writeGranted(userId, projectId, EDIT_CASE,
                project -> apply(userId, project.getId(), request));

        return switch (gated) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    /** Removes a binding. 204 whether it existed or not — delete is idempotent. */
    @Transactional
    public Outcome delete(UUID userId, UUID projectId, UUID mappingId) {
        GateResult<Outcome> gated = projects.writeGranted(userId, projectId, EDIT_CASE, project -> {
            mappings.findById(mappingId)
                    // Scoped by project as well as by id: the row is company-scoped by RLS, so an
                    // id from a SIBLING PROJECT in the same company resolves here and would be
                    // deletable by somebody holding no grant on the project that owns it. The
                    // narrowest of the two-segment defects, and the one every delete in this
                    // codebase has had to write out since CaseDeleteService.
                    .filter(mapping -> mapping.getProjectId().equals(project.getId()))
                    .ifPresent(mappings::delete);
            return new Outcome.Deleted();
        });

        return switch (gated) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    private Outcome apply(UUID userId, UUID projectId, AutomationMappingRequest request) {
        UUID companyId = CompanyContext.require();
        Instant now = clock.instant();

        Map<String, List<String>> errors = new LinkedHashMap<>();

        String normalized = request == null || request.testName() == null
                ? null
                : IngestedResult.normalize(request.testName());
        if (normalized == null || normalized.isBlank()) {
            errors.put("testName", List.of("Name the test this mapping is for."));
        } else if (normalized.length() > MAX_TEST_NAME) {
            errors.put("testName", List.of(
                    "That test name is longer than " + MAX_TEST_NAME + " characters."));
        }

        UUID caseId = request == null ? null : request.caseId();
        if (caseId == null) {
            errors.put("caseId", List.of("Choose the case this test verifies."));
        } else {
            // findLiveInProject's rule, through the seam: the sibling-project case is in the same
            // company, so RLS and the Hibernate filter both pass it. Without this predicate a
            // person could bind a test name to a case in a project they hold no grant on — and the
            // ingestion would then post results across the project boundary, which is the one thing
            // AutomationMatcher's project bound exists to make impossible.
            Set<UUID> live = cases.liveCaseIds(projectId, List.of(caseId));
            if (live.isEmpty()) {
                errors.put("caseId", List.of("That case is not one of this project's."));
            }
        }

        if (!errors.isEmpty()) {
            return new Outcome.Invalid(errors);
        }

        Optional<AutomationKeyMapping> existing = mappings.findByNormalizedTestName(normalized);
        AutomationKeyMapping mapping;
        String action;
        if (existing.isPresent()) {
            mapping = existing.get();
            action = "automation_mapping.repointed";
            mapping.reconfirm(projectId, caseId, userId, now);
        } else {
            mapping = AutomationKeyMapping.confirm(
                    companyId, projectId, normalized, caseId, userId, now);
            action = "automation_mapping.confirmed";
        }
        mappings.save(mapping);

        auditEvents.save(AuditEvent.record(
                projectId,
                userId,
                // Two verbs, because they are two acts: confirming a name nobody had bound is a
                // decision, and re-pointing one is a CORRECTION of somebody's earlier decision —
                // which is exactly the distinction ExecutionService draws between recording and
                // amending a verdict, and for the same reason. A reader auditing "why did these
                // results move" needs the second to be findable.
                action,
                AUDIT_TARGET,
                List.of(mapping.getId()),
                "{\"test\":" + AuditEvent.jsonString(normalized)
                        + ",\"case\":" + AuditEvent.jsonString(
                                cases.caseKeys(projectId, List.of(caseId)).getOrDefault(caseId, ""))
                        + "}",
                now));

        return new Outcome.Confirmed(render(projectId, List.of(mapping)).getFirst());
    }

    /**
     * Renders with each case's display key resolved in <strong>one</strong> query.
     *
     * <p>Per-row would be the N+1 that looks harmless because each query is fast — the shape
     * {@code EvidenceViews} and {@code RunService} each record having avoided, on lists that are
     * smaller than this one can be.
     */
    private List<AutomationMappingView> render(
            UUID projectId, List<AutomationKeyMapping> rows) {

        List<UUID> caseIds = rows.stream().map(AutomationKeyMapping::getCaseId).distinct().toList();
        Map<UUID, String> keys = cases.caseKeys(projectId, caseIds);

        List<AutomationMappingView> views = new ArrayList<>(rows.size());
        for (AutomationKeyMapping mapping : rows) {
            views.add(new AutomationMappingView(
                    mapping.getId(),
                    mapping.getNormalizedTestName(),
                    mapping.getCaseId(),
                    keys.get(mapping.getCaseId()),
                    mapping.getConfirmedBy(),
                    mapping.getConfirmedAt()));
        }
        return views;
    }
}
