package com.tcms.execution;

import com.tcms.project.GateResult;
import com.tcms.project.Project;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removing a plan — <strong>{@code plan:delete}'s first consumer in Java</strong>, and the third
 * Lead-only destructive verb after {@code suite:delete} and {@code run:close}.
 *
 * <h2>The refusal is the feature</h2>
 *
 * <p>{@code test_run.plan_id} references this row and {@code plan_scope_item} cascades from it, so a
 * plan is not a leaf: deleting one that runs were executed against takes the scope those runs were
 * chosen from and leaves every one of them naming a plan the API answers 404 for. Nothing breaks
 * loudly — {@code TestRunRepository.findPlanKeys} is not filtered on {@code deleted_at}, so the runs
 * keep rendering {@code TP-3} — and the link under it goes nowhere. §71's live-grants shape, and
 * §69's live-children shape before it: <strong>409 with the count</strong>, so the sentence names
 * the thing standing in the way rather than reporting a failure.
 *
 * <p>~~The way out is not to force the delete. It is {@link PlanLifecycle}, whose {@code CLOSED}
 * state exists for exactly this plan and is reachable only from {@code ACTIVE}. <strong>The two
 * acts partition the space</strong>: delete a plan nobody executed, close one somebody did.~~
 *
 * <p>→ <strong>superseded 2026-08-05 (M7).</strong> There is no {@code CLOSED}, so the partition
 * has one half: delete stays refused while a run names the plan, and such a plan has <em>no</em>
 * terminal state — it stays {@code APPROVED} for ever. That is correct under M7, where a plan is an
 * outline and its execution state lives on its runs, and it is why the refusal below can no longer
 * name an alternative act. It names the runs instead, which is the thing actually in the way.
 *
 * <h2>Soft, and nothing cascades</h2>
 *
 * <p>{@link TestPlan#softDelete} carries the reason. As with {@code CaseDeleteService}, what does
 * <em>not</em> happen is worth stating: the scope items stay, and no suite or case named by them is
 * touched. A delete that reached downward would unmake artifacts the caller did not name.
 *
 * <h2>No {@code If-Match}</h2>
 *
 * <p>{@code CaseDeleteService}'s argument unchanged: there is no lost delete. Deleting a plan
 * somebody has just renamed deletes the plan they renamed, which is what was asked for, and a stale
 * {@code lockVersion} would refuse it for a change the caller does not care about.
 */
@Service
public class PlanDeleteService {

    /** Roles spec §A4 — Instance Admin, Company Admin, Test Lead. Not Test Engineer. */
    private static final String DELETE_PLAN = "plan:delete";

    private static final String AUDIT_ACTION = "plan.deleted";
    private static final String AUDIT_TARGET = "test_plan";

    private final ProjectService projects;
    private final TestPlanRepository plans;
    private final TestRunRepository runs;
    private final AuditEventRepository auditEvents;
    private final Clock clock;

    public PlanDeleteService(
            ProjectService projects,
            TestPlanRepository plans,
            TestRunRepository runs,
            AuditEventRepository auditEvents,
            Clock clock) {

        this.projects = projects;
        this.plans = plans;
        this.runs = runs;
        this.auditEvents = auditEvents;
        this.clock = clock;
    }

    /** What happened. Sealed, so the controller's switch over it is exhaustive at compile time. */
    public sealed interface Outcome {

        /**
         * Unreachable project, a plan that is not in it, or one already deleted — one bodiless 404
         * (§6t).
         */
        record NotFound() implements Outcome {
        }

        /** {@code plan:delete} is not held here. */
        record Forbidden() implements Outcome {
        }

        /** Runs have been executed against this plan. 409, naming how many. */
        record Refused(String message) implements Outcome {
        }

        /** Soft-deleted. 204. */
        record Deleted() implements Outcome {
        }
    }

    @Transactional
    public Outcome delete(UUID userId, UUID projectId, UUID planId) {
        GateResult<Outcome> gated = projects.writeGranted(userId, projectId, DELETE_PLAN,
                project -> apply(userId, project, planId));

        return switch (gated) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    private Outcome apply(UUID userId, Project project, UUID planId) {
        // The second gate a two-segment path needs: is this plan in THAT project. Without it,
        // /projects/{granted}/plans/{ungranted} deletes a plan the caller was never given, and every
        // cross-company test still passes because the row is in the caller's own company.
        //
        // findLiveInProject excludes soft-deleted rows, so a second delete is a 404 rather than a
        // second 204: reporting success for a delete this request did not perform is the same
        // untruth as a 201 on a replayed create.
        Optional<TestPlan> found = plans.findLiveInProject(project.getId(), planId);
        if (found.isEmpty()) {
            return new Outcome.NotFound();
        }
        TestPlan plan = found.get();

        long liveRuns = runs.countLiveByPlan(project.getId(), plan.getId());
        if (liveRuns > 0) {
            return new Outcome.Refused(
                    liveRuns + (liveRuns == 1 ? " test run has" : " test runs have")
                            + " been executed against this plan, so deleting it would leave "
                            + (liveRuns == 1 ? "that run" : "those runs")
                            + " pointing at a plan nobody can open. Delete "
                            + (liveRuns == 1 ? "that run" : "those runs")
                            + " first if this plan really was never used.");
        }

        Instant now = clock.instant();
        plan.softDelete(userId, now);
        plans.saveAndFlush(plan);

        auditEvents.save(AuditEvent.record(
                project.getId(),
                userId,
                AUDIT_ACTION,
                AUDIT_TARGET,
                List.of(plan.getId()),
                // The key and the name, like the case and suite deletes: the key is what an audit
                // reader recognises the plan by, and the name is otherwise recoverable only by
                // reading a soft-deleted row — precisely what an audit trail should not require.
                "{\"key\":" + AuditEvent.jsonString(plan.getKey())
                        + ",\"name\":" + AuditEvent.jsonString(plan.getName()) + "}",
                now));

        return new Outcome.Deleted();
    }
}
