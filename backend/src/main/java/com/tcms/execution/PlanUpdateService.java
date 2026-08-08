package com.tcms.execution;

import com.tcms.project.GateResult;
import com.tcms.project.Project;
import com.tcms.project.ProjectService;
import com.tcms.project.ReviewService;
import com.tcms.project.ReviewSubjectType;
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
 * Editing a plan's content — <strong>the writer {@code entry_criteria}, {@code exit_criteria} and
 * {@code test_plan_version} have all been waiting for since {@code V1}</strong>.
 *
 * <p>§73 gave {@code plan:edit} a consumer that moves a status and left the plan's own content
 * unwritable, which is what this closes. Three things had never been written by anything:
 *
 * <ul>
 *   <li><strong>the two criteria columns</strong>, so {@code PlanCriteria.evaluate} — built in §46
 *       with an evaluator, a nullable {@code met} and a whole panel above it — has had nothing to
 *       evaluate on any real instance for its entire life;</li>
 *   <li><strong>{@code test_plan_version}</strong>, the last of §6j's four versioned aggregates with
 *       no writer ({@link PlanVersions});</li>
 *   <li><strong>a plan's scope after creation</strong>. The only way to change what a release covers
 *       was to delete the plan and make another one, which loses its key, its runs and its history.</li>
 * </ul>
 *
 * <h2>~~The refusal that is a decision: a closed plan does not change~~ — gone with M7</h2>
 *
 * <p>~~{@code CLOSED} says a release was executed and is over, and §6n pins a plan's numbers into
 * the evidence bundle when it closes. Editing the exit criteria of a closed plan therefore rewrites
 * the gate a sign-off was measured against, <em>after</em> the sign-off. Refused with 409, and the
 * way out is reopening, which costs {@code plan:approve}.~~
 *
 * <p>→ <strong>superseded 2026-08-05.</strong> §97 removed {@code CLOSED}, so there is no frozen
 * plan to refuse and no {@code Refused} arm on this service at all. The concern the freeze answered
 * has not gone away and is answered elsewhere: editing an <em>approved</em> plan on a project with
 * review on demotes it to {@code DRAFT} (§6g's RE_REVIEW arm, below), so a sign-off cannot silently
 * come to cover criteria nobody signed. What is genuinely lost is the freeze on a plan whose runs
 * are finished — under M7 such a plan stays {@code APPROVED} for ever, because its execution state
 * lives on its runs, and it can be edited. Named here rather than discovered.
 *
 * <h2>What commits together</h2>
 *
 * <p>The plan row, its scope items, the {@code test_plan_version} row and the {@code audit_event}
 * row are one transaction — {@code CaseUpdateService}'s pairing, and each half matters for the same
 * reason: a committed scope change whose version row is missing is a hole in the history exactly
 * where the scope moved, and the scope is replaced wholesale, so the version row is the only record
 * that the previous one ever existed.
 */
@Service
public class PlanUpdateService {

    /** Roles spec §A4 — Instance Admin, Company Admin, Test Lead, Test Engineer. */
    private static final String EDIT_PLAN = "plan:edit";

    private static final String AUDIT_ACTION = "plan.updated";

    /**
     * §6g's RE_REVIEW demotion. Its own verb, for {@code CaseUpdateService}'s reason: an approval
     * that ended is what somebody comes looking for, and "plan.updated" is not where they look.
     */
    private static final String AUDIT_ACTION_RE_REVIEW = "plan.re_review_required";
    private static final String AUDIT_TARGET = "test_plan";

    private final ProjectService projects;
    private final TestPlanRepository plans;
    private final PlanScopeItemRepository scopeItems;
    private final PlanService views;
    private final PlanVersions versions;
    private final PlanCriteria criteria;
    private final CaseFacts cases;
    private final ReviewService reviews;
    private final AuditEventRepository auditEvents;
    private final Clock clock;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public PlanUpdateService(
            ProjectService projects,
            TestPlanRepository plans,
            PlanScopeItemRepository scopeItems,
            PlanService views,
            PlanVersions versions,
            PlanCriteria criteria,
            CaseFacts cases,
            ReviewService reviews,
            AuditEventRepository auditEvents,
            Clock clock) {

        this.projects = projects;
        this.plans = plans;
        this.scopeItems = scopeItems;
        this.views = views;
        this.versions = versions;
        this.criteria = criteria;
        this.cases = cases;
        this.reviews = reviews;
        this.auditEvents = auditEvents;
        this.clock = clock;
    }

    /** What happened. Sealed, so the controller's switch over it is exhaustive at compile time. */
    public sealed interface Outcome {

        /** Unreachable project, or a plan that is not live in it — one bodiless 404 (§6t). */
        record NotFound() implements Outcome {
        }

        /** Readable, and {@code plan:edit} not held here. */
        record Forbidden() implements Outcome {
        }

        /** The caller's {@code If-Match} did not match the stored lock version. 409. */
        record Stale() implements Outcome {
        }

        /** Field name to messages, for the form to land on its inputs. 400. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** Saved. 200 with the plan, whether or not the scope moved. */
        record Updated(TestPlanView view) implements Outcome {
        }
    }

    /**
     * Edits a plan.
     *
     * @param expectedLockVersion the parsed {@code If-Match}, or null when the header was absent —
     *                            {@code CaseUpdateService}'s two-check shape, and unlike
     *                            {@code PUT .../status}, this one needs it: an edit <em>is</em> a
     *                            delta against something the client read, so two editors who both
     *                            opened the plan can lose each other's work
     */
    @Transactional
    public Outcome update(
            UUID userId,
            UUID projectId,
            UUID planId,
            PlanUpdateRequest request,
            Long expectedLockVersion) {

        GateResult<Outcome> gated = projects.writeGranted(userId, projectId, EDIT_PLAN,
                project -> apply(userId, project, planId, request, expectedLockVersion));

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
            PlanUpdateRequest request,
            Long expectedLockVersion) {

        // The second gate a two-segment path needs: is this plan in THAT project. Without it,
        // /projects/{granted}/plans/{ungranted} edits a plan the caller was never given, and every
        // cross-company test still passes because the row is in the caller's own company.
        Optional<TestPlan> found = plans.findLiveInProject(project.getId(), planId);
        if (found.isEmpty()) {
            return new Outcome.NotFound();
        }
        TestPlan plan = found.get();

        if (expectedLockVersion != null && expectedLockVersion != plan.getLockVersion()) {
            // Checked before validating, so a stale editor is told to reload rather than being sent
            // to fix fields in a copy it is about to have to discard anyway.
            return new Outcome.Stale();
        }

        PlanScope stored = PlanScope.ofItems(scopeItems.findForPlans(List.of(plan.getId())));

        // The live-id sets cover what was SUBMITTED and what is STORED both, because validateUpdate
        // carries an omitted list over from the stored scope and then checks every id it ends up
        // with — so a folder deleted since the plan was written is surfaced rather than silently
        // re-inserted by an edit that only meant to change the milestone.
        // Both levels on both sides: the submitted sections' ids as well as the unsectioned ones,
        // and the stored scope's whole expansion. An id checked in one group and not the other is
        // the hole this check exists to close, moved one level down.
        Set<UUID> liveSuites = cases.liveSuiteIds(
                project.getId(),
                union(sectionSuiteIds(request), union(request.suiteIds(), stored.allSuiteIds())));
        Set<UUID> liveCases = cases.liveCaseIds(
                project.getId(),
                union(sectionCaseIds(request), union(request.caseIds(), stored.allCaseIds())));

        PlanEdit.Outcome validated =
                PlanEdit.validateUpdate(request, stored, liveSuites, liveCases);
        if (validated instanceof PlanEdit.Outcome.Invalid invalid) {
            return new Outcome.Invalid(invalid.errors());
        }
        PlanEdit edit = ((PlanEdit.Outcome.Valid) validated).edit();

        Instant now = clock.instant();

        // §6g's edit-after-approval rule, RE_REVIEW arm — see EditAfterApproval#RE_REVIEW for why
        // the demotion is not gated on plan:approve. Read before applyEdit, which cannot change the
        // status: §73 put that on PUT /plans/{id}/status and PlanEdit does not accept one.
        boolean unApproves = plan.getStatus() == PlanStatus.APPROVED
                && reviews.reReviewOnEdit(project.getId(), ReviewSubjectType.PLAN);

        plan.applyEdit(
                edit,
                criteria.serialize(edit.entryCriteria()),
                criteria.serialize(edit.exitCriteria()),
                userId,
                now);

        // Taken AFTER applyEdit, so the criteria in the snapshot are the ones about to be committed
        // rather than a second rendering of the request — PlanSnapshot's own note. The scope comes
        // from the edit because the rows have not been rewritten yet.
        PlanSnapshot snapshot = PlanSnapshot.of(plan, edit.scope(), criteria);
        PlanVersions.Decision decision = versions.decide(plan, snapshot);
        if (decision.isNew()) {
            plan.stampVersion(decision.versionNo());
        }

        // After the snapshot is taken and before the row is written: the version records the plan's
        // SCOPE (§74's PlanSnapshot) and the status is deliberately not in it — §73 declined to put
        // it there — so demoting must not change what this edit's version says it covers.
        if (unApproves) {
            plan.applyStatus(PlanStatus.DRAFT, userId, now);
        }

        plans.saveAndFlush(plan);

        if (!edit.scope().equals(stored)) {
            // Replaced rather than reconciled — PlanScopeItemRepository#deleteForPlan says why. The
            // delete is flushed before the inserts for CaseClassifications' reason: Hibernate orders
            // inserts before deletes within a flush, so re-adding a scope item that is still there
            // would collide before the row it replaces had gone.
            scopeItems.deleteForPlan(plan.getId());
            scopeItems.flush();

            List<PlanScopeItem> items = PlanScopeItem.rowsOf(plan.getId(), edit.scope());
            scopeItems.saveAll(items);
        }

        if (decision.isNew()) {
            versions.write(plan, snapshot, decision, userId, now);
        }

        auditEvents.save(AuditEvent.record(
                project.getId(),
                userId,
                AUDIT_ACTION,
                AUDIT_TARGET,
                List.of(plan.getId()),
                // The version number is the interesting half. An audit row saying a plan was edited
                // does not say whether what it COVERS moved, and that is the only question a reader
                // asks of a plan's history — "v3" points at the test_plan_version row holding the
                // scope, and an unchanged number says this edit was prose.
                "{\"key\":" + AuditEvent.jsonString(plan.getKey())
                        + ",\"versionNo\":" + plan.getCurrentVersionNo()
                        + ",\"scopeChanged\":" + decision.isNew() + "}",
                now));

        if (unApproves) {
            auditEvents.save(AuditEvent.record(
                    project.getId(),
                    userId,
                    AUDIT_ACTION_RE_REVIEW,
                    AUDIT_TARGET,
                    List.of(plan.getId()),
                    "{\"key\":" + AuditEvent.jsonString(plan.getKey())
                            + ",\"from\":\"APPROVED\",\"to\":\"DRAFT\""
                            + ",\"reason\":\"EDIT_AFTER_APPROVAL\"}",
                    now));
        }

        return new Outcome.Updated(views.viewOf(plan));
    }

    /**
     * The ids worth asking about: everything submitted, plus everything already stored.
     *
     * <p>One query per side rather than two — {@code CaseUpdateService} resolves its shared-step
     * union the same way, and for the same reason: the validator needs one set covering both halves,
     * and asking twice would be two answers to "what does this project hold" a few lines apart.
     */
    private static List<UUID> union(List<UUID> submitted, List<UUID> stored) {
        List<UUID> all = new ArrayList<>(stored);
        if (submitted != null) {
            all.addAll(submitted);
        }
        return all;
    }

    /** The suite ids named inside the submitted sections, or empty when none were submitted. */
    private static List<UUID> sectionSuiteIds(PlanUpdateRequest request) {
        return sectionIds(request, PlanSectionInput::suiteIds);
    }

    /** Likewise for cases. */
    private static List<UUID> sectionCaseIds(PlanUpdateRequest request) {
        return sectionIds(request, PlanSectionInput::caseIds);
    }

    private static List<UUID> sectionIds(
            PlanUpdateRequest request, java.util.function.Function<PlanSectionInput, List<UUID>> arm) {

        if (request.sections() == null) {
            return List.of();
        }
        List<UUID> ids = new ArrayList<>();
        for (PlanSectionInput section : request.sections()) {
            if (section != null && arm.apply(section) != null) {
                arm.apply(section).stream().filter(java.util.Objects::nonNull).forEach(ids::add);
            }
        }
        return ids;
    }
}
