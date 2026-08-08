package com.tcms.execution;

import com.tcms.project.GateResult;
import com.tcms.project.Project;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cloning one plan — plan §6v, decision round 3 #59, and <strong>the one of the three where "a clone
 * is a create" does not hold.</strong>
 *
 * <h2>Why this class exists at all, when the case and the run clones are four lines of delegation</h2>
 *
 * <p>{@code repository.CaseCloneService} and {@link RunCloneService} build the request their create
 * endpoint already takes and hand it over, because for those two aggregates the create contract
 * <em>is</em> the whole editable set. A plan's is not, and deliberately:
 * {@link PlanWriteRequest} carries six fields where {@link PlanUpdateRequest} carries twelve, on the
 * stated ground that <em>a plan is named before anybody has subdivided it</em>.
 *
 * <p>So a clone routed through {@code POST /plans} would silently drop <strong>six</strong> things —
 * the tag arm, the sections, both prose fields and both criteria lists — which between them are most
 * of what §97 says a plan detail consists of. The plan would save, read back, and look like a
 * successful copy of a plan that had never had any of it.
 *
 * <p>The two ways out and why this is the third:
 *
 * <ul>
 *   <li><strong>Create, then edit.</strong> Cheap and wrong: the create writes version 1 describing
 *       a plan with no sections and no criteria, and the edit immediately writes version 2 — so a
 *       cloned plan is born with a history saying somebody rewrote it a second later. §6v rule 3
 *       says a clone gets <em>its own version-1 snapshot</em>, and this produces a v1 that never
 *       described anything real.</li>
 *   <li><strong>Write the rows here.</strong> A second place that allocates a display key, decides a
 *       starting status and writes a version 1.</li>
 *   <li><strong>Share the orchestration and vary the input</strong> — {@link PlanCreateService#write},
 *       which takes a validated {@link PlanEdit} instead of a request. One key allocator, one v1
 *       writer, one audit row, and this class supplies an edit the narrow request could not have
 *       expressed.</li>
 * </ul>
 *
 * <h2>The criteria are copied as stored JSON and NOT re-validated</h2>
 *
 * <p>They were canonicalized by {@link PlanCriteria#serialize} when they were written, and
 * {@link PlanCriteria#parse} already drops an entry it cannot evaluate on the way out. Putting them
 * back through {@code validateCriteria} would make a plan <strong>uncloneable</strong> the day a
 * metric is retired — a rule about <em>requests</em> refusing a copy of something already stored,
 * which is exactly the concession {@code repository.CaseEdit.of} makes for a retired shared step.
 *
 * <h2>The gate is {@code plan:create} alone, and it is safe because of who holds it</h2>
 *
 * <p>This writes criteria and scope prose, which on the ordinary path cost {@code plan:edit}. Every
 * role in {@code V3}'s catalogue holding {@code plan:create} also holds {@code plan:edit} — Instance
 * Admin, Company Admin, Test Lead, Test Engineer — so no caller gains anything they could not do in
 * two requests. That is asserted from the seeded catalogue rather than stated here, in
 * {@code PlanCloneEndpointTest}, so a future role that separates the two fails the build instead of
 * quietly acquiring an edit nobody granted it.
 */
@Service
public class PlanCloneService {

    private static final String CREATE_PLAN = "plan:create";

    private static final String AUDIT_ACTION = "plan.cloned";
    private static final String AUDIT_TARGET = "test_plan";

    private final ProjectService projects;
    private final PlanCreateService creation;
    private final TestPlanRepository plans;
    private final PlanScopeItemRepository scopeItems;
    private final CaseFacts cases;
    private final AuditEventRepository auditEvents;
    private final Clock clock;

    @SuppressWarnings("checkstyle:ParameterNumber")
    PlanCloneService(
            ProjectService projects,
            PlanCreateService creation,
            TestPlanRepository plans,
            PlanScopeItemRepository scopeItems,
            CaseFacts cases,
            AuditEventRepository auditEvents,
            Clock clock) {

        this.projects = projects;
        this.creation = creation;
        this.plans = plans;
        this.scopeItems = scopeItems;
        this.cases = cases;
        this.auditEvents = auditEvents;
        this.clock = clock;
    }

    /** {@link PlanCreateService.Outcome}'s five shapes, because the outcome of a clone is a create's. */
    public sealed interface Outcome {

        /** Unreachable project, or no such live plan in it — one bodiless 404 (§6t). */
        record NotFound() implements Outcome {
        }

        /** Readable, and {@code plan:create} not held. */
        record Forbidden() implements Outcome {
        }

        /** Field name to messages — {@link PlanEdit}'s, unedited. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** The new plan. 201. */
        record Created(TestPlanView view) implements Outcome {
        }

        /** This intent already cloned. 200. */
        record Replayed(TestPlanView view) implements Outcome {
        }
    }

    /** Clones a plan. One transaction: the row, its scope items, its v1 and both audit rows. */
    @Transactional
    public Outcome clonePlan(
            UUID userId,
            UUID projectId,
            UUID planId,
            PlanCloneRequest request,
            String idempotencyKey) {

        GateResult<Outcome> gated = projects.writeGranted(userId, projectId, CREATE_PLAN,
                project -> apply(userId, project, planId, request, idempotencyKey));

        return switch (gated) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    private Outcome apply(
            UUID userId,
            Project project,
            UUID planId,
            PlanCloneRequest request,
            String idempotencyKey) {

        // FIRST, before reading or deriving anything — the create path's rule, through the create
        // path's own arm. A retry must answer with the plan it made even if the SOURCE has since
        // been edited, which is the drift this endpoint has and the ordinary create does not.
        PlanCreateService.Outcome replay = creation.replayOf(project.getId(), idempotencyKey);
        if (replay != null) {
            return translate(userId, replay, null, null);
        }

        // The second gate a two-segment path needs: is this plan in THAT project.
        Optional<TestPlan> found = plans.findLiveInProject(project.getId(), planId);
        if (found.isEmpty()) {
            return new Outcome.NotFound();
        }
        TestPlan source = found.get();

        PlanScope stored = PlanScope.ofItems(scopeItems.findForPlans(List.of(source.getId())));
        PlanUpdateRequest derived = derive(source, stored, request);

        Set<UUID> liveSuites = cases.liveSuiteIds(project.getId(), allSuiteIds(stored));
        Set<UUID> liveCases = cases.liveCaseIds(project.getId(), allCaseIds(stored));

        // validateUpdate, not validateCreate — the create's validator cannot see a section, a tag
        // arm, or either prose field. `stored` is EMPTY: this validates a plan that does not exist
        // yet, so "leave the stored scope alone" must mean "there is none", the same empty sets
        // CaseCreateService passes for a case's steps and classification.
        PlanEdit.Outcome validated =
                PlanEdit.validateUpdate(derived, PlanScope.empty(), liveSuites, liveCases);
        if (validated instanceof PlanEdit.Outcome.Invalid invalid) {
            return new Outcome.Invalid(invalid.errors());
        }
        PlanEdit edit = ((PlanEdit.Outcome.Valid) validated).edit();

        return translate(
                userId,
                creation.write(
                        userId,
                        project,
                        edit,
                        // Verbatim, not re-validated — see the class header.
                        source.getEntryCriteria(),
                        source.getExitCriteria(),
                        idempotencyKey),
                source,
                project.getId());
    }

    /**
     * The create's outcome as this endpoint's, writing the provenance row on the way through.
     *
     * <p>{@code source} is null on the replay path, where nothing was created and nothing is
     * recorded — the first attempt already wrote both rows, and a second provenance event would
     * claim a clone that did not happen.
     */
    private Outcome translate(
            UUID userId, PlanCreateService.Outcome outcome, TestPlan source, UUID projectId) {
        return switch (outcome) {
            case PlanCreateService.Outcome.NotFound ignored -> new Outcome.NotFound();
            case PlanCreateService.Outcome.Forbidden ignored -> new Outcome.Forbidden();
            case PlanCreateService.Outcome.Invalid invalid -> new Outcome.Invalid(invalid.errors());
            case PlanCreateService.Outcome.Replayed replayed ->
                    new Outcome.Replayed(replayed.view());
            case PlanCreateService.Outcome.Created created -> {
                recordProvenance(userId, created.view(), source, projectId, clock.instant());
                yield new Outcome.Created(created.view());
            }
        };
    }

    /**
     * The source plan as an edit request.
     *
     * <p>Twelve fields, one of which is the caller's. The rest are copied because a plan <em>is</em>
     * its scope, its sections and its gates — §97's whole argument for the detail being one form.
     */
    private PlanUpdateRequest derive(
            TestPlan source, PlanScope stored, PlanCloneRequest request) {

        List<PlanSectionInput> sections = new ArrayList<>();
        for (PlanScope.Section section : stored.sections()) {
            sections.add(new PlanSectionInput(
                    section.name(), section.caseIds(), section.suiteIds(), section.tags()));
        }

        return new PlanUpdateRequest(
                request.name(),
                source.getDescription(),
                source.getMilestoneName(),
                source.getMilestoneDate(),
                stored.suiteIds(),
                stored.caseIds(),
                stored.tags(),
                sections,
                source.getScopeText(),
                source.getOutOfScopeText(),
                // Null, not the source's: "leave the stored criteria alone", which for a plan that
                // does not exist yet means "this request declares none". The real values travel to
                // PlanCreateService#write as the stored JSON, past the validator that would refuse
                // a retired metric. See the class header.
                null,
                null);
    }

    /** Every suite id the derived request will submit, unsectioned arm and sections together. */
    private static List<UUID> allSuiteIds(PlanScope scope) {
        List<UUID> ids = new ArrayList<>(scope.suiteIds());
        for (PlanScope.Section section : scope.sections()) {
            ids.addAll(section.suiteIds());
        }
        return ids;
    }

    /** Likewise for cases — a section's members are validated as strictly as the top-level arm. */
    private static List<UUID> allCaseIds(PlanScope scope) {
        List<UUID> ids = new ArrayList<>(scope.caseIds());
        for (PlanScope.Section section : scope.sections()) {
            ids.addAll(section.caseIds());
        }
        return ids;
    }

    /**
     * The provenance row §6v asks for — a second audit event beside {@code plan.created}, in
     * {@code repository.CaseCloneService}'s shape and for its reasons.
     */
    private void recordProvenance(
            UUID userId, TestPlanView clone, TestPlan source, UUID projectId, Instant now) {

        auditEvents.save(AuditEvent.record(
                projectId,
                userId,
                AUDIT_ACTION,
                AUDIT_TARGET,
                List.of(clone.id()),
                "{\"key\":" + AuditEvent.jsonString(clone.key())
                        + ",\"clonedFrom\":" + AuditEvent.jsonString(source.getKey())
                        + ",\"clonedFromId\":" + AuditEvent.jsonString(source.getId().toString())
                        + "}",
                now));
    }
}
