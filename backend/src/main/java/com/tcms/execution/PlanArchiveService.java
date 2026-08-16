package com.tcms.execution;

import com.tcms.project.GateResult;
import com.tcms.project.Project;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Putting a plan away — <strong>{@code test_plan.archived_at}'s only writer</strong> ({@code V65},
 * audit D2).
 *
 * <h2>What this exists for, and what it deliberately is not</h2>
 *
 * <p>{@code V32} removed {@code CLOSED} from the plan ladder in M7, correctly: it described a plan
 * being <em>run</em>, which is the execution's business, and {@code test_run} has carried its own
 * lifecycle since {@code V1}. What went with it was any way for a finished plan to leave the list.
 * {@link PlanDeleteService} refuses to delete a plan that runs were executed against — also
 * correctly, since those runs would be left naming a plan the API answers 404 for — so the plan for
 * last spring's release stayed {@code APPROVED} at the top of the working list for ever, and the
 * only way out was a delete the server was right to refuse.
 *
 * <p>So this is a <strong>visibility</strong> act. It does not freeze the plan, does not touch its
 * status, does not close its runs and does not make the delete succeed:
 *
 * <ul>
 *   <li>An archived plan can still be read, edited, approved, cloned, run against and reported on.
 *       Every gate that applied before applies unchanged.</li>
 *   <li>{@link PlanDeleteService}'s 409 is untouched. Archiving is what to do <em>instead of</em>
 *       deleting such a plan, not a way to get the delete through.</li>
 *   <li>The status survives, because an approved plan is the one you most want to archive when the
 *       release ships and archiving must not unsay the approval.</li>
 * </ul>
 *
 * <p>A flag that also froze the plan would be {@code CLOSED} under another name — a terminal state
 * with the ladder's conflation and none of its clarity. The audit asks for old plans to leave the
 * working list, not for them to become unusable, and a team that archives a plan and then needs one
 * more run against it should get the run rather than a refusal citing a checkbox.
 *
 * <h2>{@code plan:edit}, not {@code plan:delete} and not {@code plan:approve}</h2>
 *
 * <p>Archiving changes what a list shows and destroys nothing, so charging the delete key would put
 * a reversible tidying act behind the product's most destructive plan permission. It is not an
 * approval either — nothing is being judged. It is the same key that renames a plan and edits its
 * scope, which is the closest existing act in both blast radius and reversibility.
 *
 * <h2>No {@code If-Match}</h2>
 *
 * <p>{@link PlanStatusService}'s reason, unchanged: the request names an absolute target state
 * rather than a delta against something the client read, so there is no lost update to protect. Two
 * leads both archiving the same finished plan want the same outcome and get it.
 */
@Service
public class PlanArchiveService {

    /** Roles spec §A3 — every contributor who may author a plan may put one away. */
    private static final String EDIT_PLAN = "plan:edit";

    private static final String AUDIT_TARGET = "test_plan";
    private static final String ARCHIVED = "plan.archived";
    private static final String UNARCHIVED = "plan.unarchived";

    private final ProjectService projects;
    private final TestPlanRepository plans;
    private final PlanService views;
    private final AuditEventRepository auditEvents;
    private final Clock clock;

    public PlanArchiveService(
            ProjectService projects,
            TestPlanRepository plans,
            PlanService views,
            AuditEventRepository auditEvents,
            Clock clock) {

        this.projects = projects;
        this.plans = plans;
        this.views = views;
        this.auditEvents = auditEvents;
        this.clock = clock;
    }

    /** What happened. Sealed, so the controller's switch over it is exhaustive at compile time. */
    public sealed interface Outcome {

        /** Unreachable project, or a plan that is not live in it — one bodiless 404 (§6t). */
        record NotFound() implements Outcome {
        }

        /** {@code plan:edit} is not held here. */
        record Forbidden() implements Outcome {
        }

        /** No {@code archived} in the body. 400, on the field. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** Put away, brought back, or already in the state asked for. 200 with the plan either way. */
        record Updated(TestPlanView view) implements Outcome {
        }
    }

    @Transactional
    public Outcome setArchived(
            UUID userId, UUID projectId, UUID planId, PlanArchiveRequest request) {

        GateResult<Outcome> gated = projects.writeGranted(
                userId, projectId, EDIT_PLAN, project -> apply(userId, project, planId, request));

        return switch (gated) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    private Outcome apply(
            UUID userId, Project project, UUID planId, PlanArchiveRequest request) {

        if (request == null || request.archived() == null) {
            // Before the plan is looked up, and therefore before the 404: the request is malformed
            // whichever plan it names, and answering 404 would send the caller to check an id that
            // is fine. CaseDraftService's ordering, for its reason.
            return new Outcome.Invalid(Map.of("archived", List.of(
                    "Say whether this plan should be archived: true to take it out of the plan "
                            + "list, false to put it back.")));
        }

        // The second gate a two-segment path needs, and the same one every other write here uses:
        // a plan id from a sibling project must not resolve through a path naming a project the
        // caller does hold. findLiveInProject excludes soft-deleted rows; it does NOT exclude
        // archived ones, which is what makes un-archiving reachable.
        Optional<TestPlan> found = plans.findLiveInProject(project.getId(), planId);
        if (found.isEmpty()) {
            return new Outcome.NotFound();
        }
        TestPlan plan = found.get();

        boolean archived = request.archived();
        if (archived == plan.isArchived()) {
            // Already there. 200 with the plan and NOTHING WRITTEN — no re-stamped timestamp and no
            // audit row, which is PlanLifecycle.Decision.Unchanged's rule: a repeated click must not
            // bury the act that actually happened under a row saying it happened again.
            return new Outcome.Updated(views.viewOf(plan));
        }

        Instant now = clock.instant();
        plan.applyArchived(archived, userId, now);
        plans.saveAndFlush(plan);

        auditEvents.save(AuditEvent.record(
                project.getId(),
                userId,
                archived ? ARCHIVED : UNARCHIVED,
                AUDIT_TARGET,
                List.of(plan.getId()),
                // The key and the name, like every other plan act: the key is what an audit reader
                // recognises the plan by, and the name is otherwise recoverable only by reading the
                // row — which an audit trail should not require.
                "{\"key\":" + AuditEvent.jsonString(plan.getKey())
                        + ",\"name\":" + AuditEvent.jsonString(plan.getName()) + "}",
                now));

        return new Outcome.Updated(views.viewOf(plan));
    }
}
