package com.tcms.execution;

import com.tcms.project.GateResult;
import com.tcms.project.ProjectService;
import com.tcms.project.ReviewService;
import com.tcms.project.ReviewSubjectType;
import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moving a plan along its ladder — <strong>the first consumer of {@code plan:edit} and
 * {@code plan:approve} in Java</strong>, and the first writer {@code test_plan.status} has had since
 * the create factory.
 *
 * <p>Both keys have been seeded by {@code V3} and correctly granted since M0 with no code ever
 * asking for them: the sixth and seventh instance of that shape after §62's {@code case:approve},
 * §64's {@code library:manage}, §69's {@code company:vocabulary}, §71's {@code company:teams} and
 * §72's {@code run:close}. {@code plan:review} is <strong>deliberately still unconsumed</strong> and
 * that is correct rather than an omission — §6g puts peer review <em>inside</em> {@code IN_REVIEW},
 * accumulating on the item without moving it, so the key belongs to the reviewer apparatus (comments,
 * LGTM, request-changes) and not to any transition. {@code case:review} and {@code suite:review} are
 * unconsumed for exactly the same reason, which is why the three of them stay together.
 *
 * <h2>The permission is read from the row, and that is the point of this endpoint</h2>
 *
 * <p>{@link ProjectService#writeGrantedOnTarget} — §69's seam — because
 * {@link PlanLifecycle#permissionFor} needs <em>both</em> ends of the move, and one of them is
 * stored. A gate that could only see the requested target would let a Test Engineer rescind a lead's
 * approval by asking for {@code DRAFT}; see {@code PlanLifecycle}'s header for the full argument.
 *
 * <p>The rule {@code writeGranted}'s javadoc states is kept: the permission is never nominated by
 * the request. What the request supplies is one end of an edge whose gate the <em>server</em>
 * computes, from a status only the database holds.
 *
 * <h2>Why the parse happens before the gate, and why an unreadable status asks the lower key</h2>
 *
 * <p>The permission cannot be computed without knowing the target, so the parse has to come first —
 * which would ordinarily hand a Viewer a 400 that tells them what this project's ladder accepts,
 * the disclosure §72 avoided by parsing inside its gate. So an unparseable value falls back to
 * {@link PlanLifecycle#EDIT_PLAN}: it names no edge, it must still be gated, and gating it at the
 * <em>lower</em> of the two keys means a caller who may not touch plans at all is refused before
 * learning the vocabulary, while a caller who may gets the field error they can act on.
 *
 * <h2>No {@code If-Match}</h2>
 *
 * <p>{@link RunStatusService}'s reason, unchanged: the request names an absolute target state rather
 * than a delta against something the client read, so there is no lost update to protect. Two leads
 * both pressing Approve want the same outcome and get it.
 *
 * <h2>§6g's gating rule was here and is gone (M7)</h2>
 *
 * <p>~~A project may close the {@code DRAFT → ACTIVE} edge: {@code requireApprovedPlanToStart}
 * refuses starting a plan nobody signed off, overridable with {@code plan:approve}.~~
 *
 * <p>→ <strong>superseded 2026-08-05.</strong> The rule bound on that one edge and §97 removed the
 * edge with the {@code ACTIVE} state. {@link PlanLifecycle}'s header keeps §82's finding — the rule
 * was an edge and not a target — because it cost a slice to discover and is moot rather than wrong.
 * §6g's <em>other</em> gating rule, {@code requireApprovedCaseForRun}, is untouched and lives in
 * {@link RunCreateService}: it binds on the resolved case set at run-create time, which is where a
 * plan whose sections re-resolve (D7′) still meets an approval check.
 */
@Service
public class PlanStatusService {

    private static final String AUDIT_TARGET = "test_plan";

    private final ProjectService projects;
    private final TestPlanRepository plans;
    private final PlanService views;
    private final ReviewService reviews;
    private final AuditEventRepository auditEvents;
    private final Clock clock;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public PlanStatusService(
            ProjectService projects,
            TestPlanRepository plans,
            PlanService views,
            ReviewService reviews,
            AuditEventRepository auditEvents,
            Clock clock) {

        this.projects = projects;
        this.plans = plans;
        this.views = views;
        this.reviews = reviews;
        this.auditEvents = auditEvents;
        this.clock = clock;
    }

    /** What happened. Sealed, so the controller's switch over it is exhaustive at compile time. */
    public sealed interface Outcome {

        /** Unreachable project, or a plan that is not live in it — one bodiless 404 (§6t). */
        record NotFound() implements Outcome {
        }

        /** Readable, and the permission this particular move needs is not held. */
        record Forbidden() implements Outcome {
        }

        /** A status this ladder does not have. 400, on the field. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** A move the plan's current state refuses. 409, with the sentence {@link PlanLifecycle} chose. */
        record Refused(String message) implements Outcome {
        }

        /** Moved, or already there. 200 with the plan either way. */
        record Updated(TestPlanView view) implements Outcome {
        }
    }

    @Transactional
    public Outcome setStatus(UUID userId, UUID projectId, UUID planId, PlanStatusRequest request) {
        PlanStatus requested = parse(request == null ? null : request.status());

        GateResult<Outcome> gated = projects.writeGrantedOnTarget(
                userId,
                projectId,
                project -> plans.findLiveInProject(project.getId(), planId),
                plan -> ProjectService.RequiredPermission.onProject(
                        requested == null
                                ? PlanLifecycle.EDIT_PLAN
                                : PlanLifecycle.permissionFor(plan.getStatus(), requested)),
                (project, plan) -> apply(userId, project.getId(), plan, requested));

        return switch (gated) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    private Outcome apply(UUID userId, UUID projectId, TestPlan plan, PlanStatus requested) {

        if (requested == null) {
            return new Outcome.Invalid(Map.of("status", List.of(
                    "Choose a status: DRAFT, IN_REVIEW or APPROVED.")));
        }

        return switch (PlanLifecycle.decide(plan.getStatus(), requested)) {
            case PlanLifecycle.Decision.Refused refused -> new Outcome.Refused(refused.message());
            // 200 with the plan and nothing written — no timestamp re-stamped, no audit row. See
            // Decision.Unchanged: a repeated click must not bury the change that happened.
            case PlanLifecycle.Decision.Unchanged ignored -> new Outcome.Updated(views.viewOf(plan));
            case PlanLifecycle.Decision.Move move -> moving(userId, projectId, plan, move.target());
        };
    }

    /**
     * The one policy question between "the ladder allows this move" and anything being written.
     *
     * <p>§6g's review policy binds only on a move to {@code APPROVED} — {@code ReviewService#refuses}
     * returns empty for every other standing.
     *
     * <p>~~It is one of two, disjoint by construction from §6g's gating rule, which binds only on
     * {@code DRAFT → ACTIVE}.~~ → the gating rule went with that edge in M7, so there is one policy
     * here now and no ordering question at all.
     *
     * <p>Placement is {@code SuiteStatusService}'s and its reason: <em>after</em> the ladder has
     * agreed the move exists, so a caller asking for an impossible transition is told that rather
     * than told about a policy that would not have applied, and <em>before</em> anything is written.
     */
    private Outcome moving(UUID userId, UUID projectId, TestPlan plan, PlanStatus target) {

        Optional<String> reviewPolicy = reviews.refuses(
                projectId,
                ReviewSubjectType.PLAN,
                plan.getId(),
                PlanLifecycle.standingOf(target),
                userId);
        if (reviewPolicy.isPresent()) {
            return new Outcome.Refused(reviewPolicy.get());
        }
        return move(userId, projectId, plan, target);
    }

    private Outcome move(UUID userId, UUID projectId, TestPlan plan, PlanStatus target) {
        Instant now = clock.instant();
        PlanStatus previous = plan.getStatus();

        plan.applyStatus(target, userId, now);
        plans.saveAndFlush(plan);

        // Inside this transaction, so the plan's status and the record of who signed it off commit
        // together — see ReviewService#moved. The version pinned is the plan's current one, and for
        // a plan a version IS its scope (§74's PlanSnapshot), which is exactly what a release
        // sign-off is a signature on: these suites, these cases, this milestone.
        reviews.moved(
                projectId,
                ReviewSubjectType.PLAN,
                plan.getId(),
                plan.getCurrentVersionNo(),
                PlanLifecycle.standingOf(target),
                userId,
                now);

        auditEvents.save(AuditEvent.record(
                projectId,
                userId,
                action(target),
                AUDIT_TARGET,
                List.of(plan.getId()),
                // Both ends of the move. "plan.approved" alone does not say which state it replaced,
                // and a plan that was approved, returned to draft and approved again is three events
                // whose order is only readable if each carries where it came from — RunStatusService's
                // argument, and ExecutionService's before it.
                //
                // The rows M7 stopped writing — plan.started, plan.reopened, plan.closed — are still
                // in this table on every deployment that ran them, and they are now the ONLY record
                // of which releases were executed and when they ended: V32 dropped the column that
                // said so. A reader of this trail must not assume the vocabulary was always three.
                "{\"key\":" + AuditEvent.jsonString(plan.getKey())
                        + ",\"from\":" + AuditEvent.jsonString(previous.name())
                        + ",\"to\":" + AuditEvent.jsonString(target.name())
                        + "}",
                now));

        return new Outcome.Updated(views.viewOf(plan));
    }

    /**
     * The audit action, by where the plan landed.
     *
     * <p>Distinct verbs rather than one {@code plan.status_changed}, for {@link RunStatusService}'s
     * reason: plan §7's audit vocabulary lists the acts by name, and a reader asking "who signed off
     * on releases last quarter" should not have to parse a payload to exclude the withdrawals.
     *
     * <p>~~{@code ACTIVE} is the target reached both by starting a plan and by reopening a closed
     * one, and those are not the same act to anybody reading the trail — hence the one
     * pair-sensitive arm.~~ → gone with M7, which is why this no longer needs {@code previous}. The
     * three verbs it wrote are still in {@code audit_event} and are described in {@link #move}.
     */
    private static String action(PlanStatus target) {
        return switch (target) {
            case IN_REVIEW -> "plan.submitted";
            case APPROVED -> "plan.approved";
            case DRAFT -> "plan.withdrawn";
        };
    }

    /** Null for anything that is not a status — reported as a field error, never thrown. */
    private static PlanStatus parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return PlanStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException notAStatus) {
            return null;
        }
    }
}
