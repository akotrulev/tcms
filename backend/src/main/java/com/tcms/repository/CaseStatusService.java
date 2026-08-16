package com.tcms.repository;

import com.tcms.project.GateResult;
import com.tcms.project.ProjectService;
import com.tcms.project.ReviewService;
import com.tcms.project.ReviewSubjectType;
import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import com.tcms.tenancy.CompanyContext;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moving <strong>one</strong> case along §6g's ladder — {@link SuiteStatusService}'s twin, over the
 * same {@link LifecycleLadder} and with the same four decisions in the same order.
 *
 * <h2>What this closes</h2>
 *
 * <p>{@link CaseBulkService} was the <em>only</em> writer of {@code test_case.lifecycle_status}
 * outside a create, so submitting a single case for review meant leaving the case, finding its row
 * in the list, selecting it, and opening the bulk dialog — four steps and a different screen to do
 * the thing the case's own page is about. Suites and plans have had a status endpoint since §73/§74;
 * the case, which §6g calls the common artifact, did not. The 2026-08-15 flow review is where this
 * is written down (improvement A1).
 *
 * <h2>This is not a one-row bulk operation, and the difference is the refusal</h2>
 *
 * <p>{@link CaseBulkService} <strong>skips a row with a reason</strong> when its edge costs more
 * than the caller holds, and that is right there: a selection is a filter's output and may hold rows
 * of several statuses, so refusing the whole operation would refuse the rows the caller may in fact
 * move. One case is not a selection. Here the same situation is a <strong>403</strong>, which is the
 * answer somebody looking at one case can act on — a "skipped: 1" report about the case on the
 * screen in front of them is a refusal wearing a batch's clothes.
 *
 * <p>The bulk path is therefore unchanged and neither endpoint is a special case of the other. What
 * they share is {@link LifecycleLadder}, which is the part that could disagree.
 *
 * <h2>Per-act audit verbs, unlike the bulk path</h2>
 *
 * <p>{@link #action} writes {@code case.submitted}, {@code case.approved}, {@code case.unapproved},
 * {@code case.withdrawn}, {@code case.revived} and {@code case.deprecated} — the vocabulary
 * {@link SuiteStatusService} and {@code PlanStatusService} already use — where the bulk path writes
 * {@code case.bulk.status_changed} and {@code case.bulk.approved}. Plan §7 lists the acts by name,
 * and a reader asking <em>"what did we sign off last quarter"</em> should be able to answer it by
 * filtering the action column. The {@code bulk} infix is kept on the other path deliberately: a
 * batch is a different act and an auditor reading four hundred rows under one event wants to know
 * that is what happened.
 *
 * <h2>The permission is read from the row</h2>
 *
 * <p>{@link ProjectService#writeGrantedOnTarget}, because {@link LifecycleLadder#permissionFor}
 * needs <em>both</em> ends of the move and one of them is stored. A gate that could only see the
 * requested target would let a Test Engineer rescind a lead's approval by asking for {@code DRAFT} —
 * which is exactly what the bulk path did until §75.
 *
 * <h2>No version row</h2>
 *
 * <p>A status move is not authored content, so nothing here writes a {@code test_case_version}.
 * {@link CaseBulkService#mutate} makes the same call through
 * {@code BulkCommand#changesAuthoredContent}, and the reason is {@code CaseVersions#hash}: the
 * digest covers authored fields only, so a version written here would carry an unchanged hash and
 * the <em>next</em> ordinary save would attribute its own content to this move.
 *
 * <h2>No {@code If-Match}</h2>
 *
 * <p>{@link SuiteStatusService}'s reason, unchanged: the request names an absolute target state
 * rather than a delta against something the client read, so there is no lost update to protect. Two
 * leads both pressing Approve want the same outcome and get it.
 */
@Service
public class CaseStatusService {

    private static final String AUDIT_TARGET = "case";

    /**
     * The two keys this ladder is gated on — {@link BulkOperations#CASE_KEYS}, read rather than
     * re-spelled.
     *
     * <p>A second {@code new LifecycleLadder.Keys("case:edit", "case:approve")} here would be a
     * hand-maintained copy of a neighbour under an instruction to keep them in step, which is §58's
     * E1 — and the failure it produces is silent in the worst direction: the two endpoints would
     * gate the same edge on different keys and both would look correct.
     */
    private static final LifecycleLadder.Keys KEYS = BulkOperations.CASE_KEYS;

    private final ProjectService projects;
    private final TestCaseRepository cases;
    private final SuiteRepository suites;
    private final CaseRenderer renderer;
    private final ReviewService reviews;
    private final AuditEventRepository auditEvents;
    private final Clock clock;

    public CaseStatusService(
            ProjectService projects,
            TestCaseRepository cases,
            SuiteRepository suites,
            CaseRenderer renderer,
            ReviewService reviews,
            AuditEventRepository auditEvents,
            Clock clock) {

        this.projects = projects;
        this.cases = cases;
        this.suites = suites;
        this.renderer = renderer;
        this.reviews = reviews;
        this.auditEvents = auditEvents;
        this.clock = clock;
    }

    /** What happened. Sealed, so the controller's switch over it is exhaustive at compile time. */
    public sealed interface Outcome {

        /** Unreachable project, or a case that is not live in it — one bodiless 404 (§6t). */
        record NotFound() implements Outcome {
        }

        /** Readable, and the permission this particular move needs is not held. */
        record Forbidden() implements Outcome {
        }

        /** A status this ladder does not have. 400, on the field. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /**
         * A move the case's current state refuses, or one §6g's review policy will not allow.
         * 409, carrying the sentence the refusing side wrote.
         */
        record Refused(String message) implements Outcome {
        }

        /** Moved, or already there. 200 with the case either way. */
        record Updated(TestCaseDetailView view) implements Outcome {
        }
    }

    @Transactional
    public Outcome setStatus(UUID userId, UUID projectId, UUID caseId, CaseStatusRequest request) {

        UUID companyId = CompanyContext.require();
        LifecycleStatus requested = parse(request == null ? null : request.status());

        GateResult<Outcome> gated = projects.writeGrantedOnTarget(
                userId,
                projectId,
                project -> cases.findLiveInProject(project.getId(), caseId),
                row -> ProjectService.RequiredPermission.onProject(
                        // An unparseable status names no edge and must still be gated, so it falls
                        // back to the LOWER key: a caller who may not touch cases at all is refused
                        // before they learn this ladder's vocabulary, and a caller who may gets the
                        // field error they can act on. SuiteStatusService's argument, verbatim.
                        requested == null
                                ? KEYS.edit()
                                : LifecycleLadder.permissionFor(
                                        row.getLifecycleStatus(), requested, KEYS)),
                (project, row) -> apply(companyId, userId, project.getId(), row, requested));

        return switch (gated) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    private Outcome apply(
            UUID companyId,
            UUID userId,
            UUID projectId,
            TestCase row,
            LifecycleStatus requested) {

        if (requested == null) {
            return new Outcome.Invalid(Map.of("status", List.of(
                    "Choose a status: DRAFT, IN_REVIEW, APPROVED or DEPRECATED.")));
        }

        return switch (LifecycleLadder.decide(row.getLifecycleStatus(), requested, "case")) {
            case LifecycleLadder.Decision.Refused refused -> new Outcome.Refused(refused.message());
            // 200 with the case and nothing written — no timestamp re-stamped, no audit row, and no
            // review record. See Decision.Unchanged: a repeated click must not bury the change that
            // actually happened.
            case LifecycleLadder.Decision.Unchanged ignored ->
                    new Outcome.Updated(view(companyId, userId, projectId, row));
            case LifecycleLadder.Decision.Move move ->
                    // §6g's review policy gets its say AFTER the ladder has agreed the move exists
                    // and BEFORE anything is written — the placement §72 argued for. A move the
                    // ladder does not have is refused as such rather than as a policy failure, so
                    // the two refusals never describe each other's problem.
                    reviews.refuses(
                                    projectId,
                                    ReviewSubjectType.CASE,
                                    row.getId(),
                                    LifecycleLadder.standingOf(move.target()),
                                    userId)
                            .<Outcome>map(Outcome.Refused::new)
                            .orElseGet(() ->
                                    move(companyId, userId, projectId, row, move.target()));
        };
    }

    private Outcome move(
            UUID companyId, UUID userId, UUID projectId, TestCase row, LifecycleStatus target) {

        Instant now = clock.instant();
        LifecycleStatus previous = row.getLifecycleStatus();

        row.moveToStatus(target, userId, now);
        cases.saveAndFlush(row);

        // Inside this transaction, so the case's status and the record of who reviewed it commit
        // together — see ReviewService#moved. The version pinned is the case's current one, which is
        // what V1's own column comment asks for: an approval applies to a SPECIFIC version,
        // including its snapshotted conditions.
        reviews.moved(
                projectId,
                ReviewSubjectType.CASE,
                row.getId(),
                row.getCurrentVersionNo(),
                LifecycleLadder.standingOf(target),
                userId,
                now);

        auditEvents.save(AuditEvent.record(
                projectId,
                userId,
                action(previous, target),
                AUDIT_TARGET,
                List.of(row.getId()),
                // Both ends of the move. "case.approved" alone does not say which state it
                // replaced, and a case that was approved, returned to draft and approved again is
                // three events whose order is only readable if each carries where it came from.
                "{\"key\":" + AuditEvent.jsonString(row.getKey())
                        + ",\"from\":" + AuditEvent.jsonString(previous.name())
                        + ",\"to\":" + AuditEvent.jsonString(target.name())
                        + "}",
                now));

        return new Outcome.Updated(view(companyId, userId, projectId, row));
    }

    /**
     * The case as {@code GET …/cases/{caseId}} serves it.
     *
     * <p>Through {@link CaseRenderer} rather than through {@code CaseDetailService}, which would
     * re-run the visibility gate this method is already inside — and would be a second resolution
     * path for a caller the server has just gated. {@code CaseDetailService#detail} composes the
     * identical call; the renderer is the one assembler of a case's shape, which is the whole reason
     * it was extracted.
     */
    private TestCaseDetailView view(UUID companyId, UUID userId, UUID projectId, TestCase row) {
        return renderer.render(companyId, userId, projectId, row, suites.findLiveForProject(projectId));
    }

    /**
     * The audit action, by where the case landed — and by where it came from for the one target two
     * different acts reach.
     *
     * <p>{@link SuiteStatusService#action}'s table with a different noun, and it is a copy on
     * purpose rather than a shared helper: the strings are the *vocabulary* plan §7 lists per
     * artifact, so a shared method would have to take the noun and would then be one edit away from
     * inventing {@code plan.revived} for an aggregate whose ladder has no such move.
     *
     * <p>{@code DRAFT} is the target reached by three different acts — withdrawing a submission,
     * rescinding an approval and reviving a retired case — and those are not the same act to anybody
     * reading the trail, which is why this arm is the pair-sensitive one.
     */
    private static String action(LifecycleStatus previous, LifecycleStatus target) {
        return switch (target) {
            case IN_REVIEW -> "case.submitted";
            case APPROVED -> "case.approved";
            case DEPRECATED -> "case.deprecated";
            case DRAFT -> switch (previous) {
                case APPROVED -> "case.unapproved";
                case DEPRECATED -> "case.revived";
                // IN_REVIEW. DRAFT is unreachable — Decision.Unchanged answered it and nothing is
                // audited on that path — and is written out so a fifth status is a compile error.
                case IN_REVIEW, DRAFT -> "case.withdrawn";
            };
        };
    }

    /** Null for anything that is not a status — reported as a field error, never thrown. */
    private static LifecycleStatus parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LifecycleStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException notAStatus) {
            return null;
        }
    }
}
