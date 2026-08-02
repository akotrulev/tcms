package com.tcms.execution;

import com.tcms.project.GateResult;
import com.tcms.project.Project;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import com.tcms.tenancy.DisplayKeys;
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
 * Creating one plan — the {@code execution} module's first write that makes a row rather than
 * changing one.
 *
 * <h2>The permission is {@code plan:create}, and Automation Service does not hold it</h2>
 *
 * <p>Roles spec §A4: Instance Admin, Company Admin, Test Lead and Test Engineer. Two exclusions
 * carry the argument.
 *
 * <ul>
 *   <li><strong>Viewer.</strong> Every read in this module asks {@code project:read}, which all six
 *       roles hold — so a create gated like a read is one the client's own read-only stakeholder may
 *       perform. That is the gate that matters and it is the one a copy-paste from
 *       {@link PlanService} would get wrong.</li>
 *   <li><strong>Automation Service.</strong> It holds {@code run:create} and {@code result:submit}
 *       and nothing else that writes (§A6): a leaked CI token may open a run and post results into
 *       it, and may not <em>author</em> anything. A plan is authoring — it declares what the team
 *       has agreed to test — so the smallest role in the product stays smallest.</li>
 * </ul>
 *
 * <h2>What commits together, and why the counter is inside it</h2>
 *
 * <p>The {@code key_sequence} increment, the plan row, its scope items and the audit row are one
 * transaction. {@link DisplayKeys#allocate} joins it rather than starting its own precisely so the
 * row lock is held until the plan that used the number commits — release it early and two creates
 * are issued {@code TP-4}.
 *
 * <p>And the key is allocated <strong>after</strong> validation, for {@code CaseCreateService}'s
 * reason: numbers are never recycled, so a rejected payload that had already burnt one leaves a
 * permanent hole in the company's sequence for every typo anybody makes.
 */
@Service
public class PlanCreateService {

    /** Roles spec §A4. Not {@code project:read} — see the class header. */
    private static final String CREATE_PLAN = "plan:create";

    private static final String AUDIT_ACTION = "plan.created";
    private static final String AUDIT_TARGET = "test_plan";

    /** {@code CaseCreateService}'s cap, for its reason: the header is attacker-controlled. */
    private static final int MAX_IDEMPOTENCY_KEY = 200;

    private final ProjectService projects;
    private final TestPlanRepository plans;
    private final PlanScopeItemRepository scopeItems;
    private final PlanService views;
    private final PlanVersions versions;
    private final PlanCriteria criteria;
    private final CaseFacts cases;
    private final DisplayKeys displayKeys;
    private final AuditEventRepository auditEvents;
    private final Clock clock;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public PlanCreateService(
            ProjectService projects,
            TestPlanRepository plans,
            PlanScopeItemRepository scopeItems,
            PlanService views,
            PlanVersions versions,
            PlanCriteria criteria,
            CaseFacts cases,
            DisplayKeys displayKeys,
            AuditEventRepository auditEvents,
            Clock clock) {

        this.projects = projects;
        this.plans = plans;
        this.scopeItems = scopeItems;
        this.views = views;
        this.versions = versions;
        this.criteria = criteria;
        this.cases = cases;
        this.displayKeys = displayKeys;
        this.auditEvents = auditEvents;
        this.clock = clock;
    }

    /** {@code CaseCreateService.Outcome}'s five shapes, and no {@code Stale}: a create has no prior. */
    public sealed interface Outcome {

        /** Unreachable project — one bodiless 404, exactly as every read in this module (§6t). */
        record NotFound() implements Outcome {
        }

        /** Readable, and {@code plan:create} not held. */
        record Forbidden() implements Outcome {
        }

        /** Field name to messages, for the form to land on its inputs. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** A new plan. 201. */
        record Created(TestPlanView view) implements Outcome {
        }

        /** A retry of a create that already succeeded. 200, so "I made this" is distinguishable. */
        record Replayed(TestPlanView view) implements Outcome {
        }
    }

    /**
     * Creates a plan.
     *
     * @param idempotencyKey the request's {@code Idempotency-Key} header, or null when absent
     */
    @Transactional
    public Outcome create(
            UUID userId, UUID projectId, PlanWriteRequest request, String idempotencyKey) {

        GateResult<Outcome> gated = projects.writeGranted(userId, projectId, CREATE_PLAN,
                project -> apply(userId, project, request, idempotencyKey));

        return switch (gated) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    private Outcome apply(
            UUID userId, Project project, PlanWriteRequest request, String idempotencyKey) {

        // FIRST, before validating anything — CaseCreateService's rule. A retry of a create that
        // succeeded must answer with the plan it made even if the payload has since drifted: the
        // client is asking "did my intent land", and a 400 would send it to fix fields on a plan
        // that already exists.
        if (idempotencyKey != null) {
            if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY) {
                return new Outcome.Invalid(Map.of("Idempotency-Key",
                        List.of("Send a key of at most " + MAX_IDEMPOTENCY_KEY + " characters.")));
            }
            Optional<TestPlan> already = plans.findByIdempotencyKey(project.getId(), idempotencyKey);
            if (already.isPresent()) {
                return new Outcome.Replayed(views.viewOf(already.get()));
            }
        }

        List<UUID> requestedSuites = PlanEdit.distinct(request.suiteIds());
        List<UUID> requestedCases = PlanEdit.distinct(request.caseIds());
        Set<UUID> liveSuites = cases.liveSuiteIds(project.getId(), requestedSuites);
        Set<UUID> liveCases = cases.liveCaseIds(project.getId(), requestedCases);

        PlanEdit.Outcome validated =
                PlanEdit.validateCreate(request, liveSuites, liveCases);
        if (validated instanceof PlanEdit.Outcome.Invalid invalid) {
            return new Outcome.Invalid(invalid.errors());
        }
        PlanEdit edit = ((PlanEdit.Outcome.Valid) validated).edit();

        DisplayKeys.Allocation allocated = displayKeys.allocate(DisplayKeys.EntityType.PLAN, 1);

        Instant now = clock.instant();
        TestPlan created = TestPlan.create(
                project.getId(),
                allocated.keyAt(0),
                allocated.seqAt(0),
                edit,
                userId,
                idempotencyKey,
                now);

        // Flushed before the scope items so the plan row exists for plan_scope_item.plan_id to
        // reference, and so uq_test_plan_key fails at its cause rather than under the rows written
        // on top of it.
        plans.saveAndFlush(created);

        List<PlanScopeItem> items = new ArrayList<>();
        for (UUID suiteId : edit.scope().suiteIds()) {
            items.add(PlanScopeItem.ofSuite(created.getId(), suiteId));
        }
        for (UUID caseId : edit.scope().caseIds()) {
            items.add(PlanScopeItem.ofCase(created.getId(), caseId));
        }
        scopeItems.saveAll(items);

        // v1, in the same transaction as the row it describes — §29's rule, and SuiteVersions makes
        // the sharper form of it: a plan created after the version writer exists must not be born
        // with the same history gap as the plans that predate it. Every plan in the database today
        // has current_version_no = 1 and no version row, so the two populations stay distinguishable
        // by the fact that matters rather than by a flag somebody maintains.
        versions.writeInitial(created, PlanSnapshot.of(created, edit.scope(), criteria), userId, now);

        auditEvents.save(AuditEvent.record(
                project.getId(),
                userId,
                AUDIT_ACTION,
                AUDIT_TARGET,
                List.of(created.getId()),
                // The key and the scope size, not the scope itself. An audit reader recognises the
                // plan by TP-n — the one thing the id does not tell them — and "how big was it when
                // it was made" is the question a later scope change is read against. The member ids
                // are in plan_scope_item, which is where a diff belongs.
                "{\"key\":" + AuditEvent.jsonString(created.getKey())
                        + ",\"scopeItems\":" + items.size() + "}",
                now));

        return new Outcome.Created(views.viewOf(created));
    }
}
