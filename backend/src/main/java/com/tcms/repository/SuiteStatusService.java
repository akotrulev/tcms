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
 * Moving a suite along §6g's ladder — <strong>the first consumer of {@code suite:approve} in
 * Java</strong>, and the first writer {@code suite.lifecycle_status} has had since
 * {@link Suite#create}.
 *
 * <p>The key has been seeded by {@code V3} and correctly granted since M0 with no code ever asking
 * for it: the eighth instance of that shape after §62's {@code case:approve}, §64's
 * {@code library:manage}, §69's {@code company:vocabulary}, §71's {@code company:teams}, §72's
 * {@code run:close} and §73's {@code plan:edit} / {@code plan:approve}. The search that finds them —
 * <em>a permission key with no grep hit in {@code src/main} is a feature nobody built</em> — has now
 * caught six features in a row.
 *
 * <p>{@code suite:review} is <strong>deliberately still unconsumed</strong> and that is correct
 * rather than an omission, for the reason {@code PlanStatusService} records: §6g puts peer review
 * <em>inside</em> {@code IN_REVIEW}, accumulating on the item without moving it, so the key belongs
 * to the reviewer apparatus (comments, LGTM, request-changes) and not to any transition.
 * {@code case:review} and {@code plan:review} are unconsumed for exactly the same reason, which is
 * why the three of them stay together.
 *
 * <h2>What this closes</h2>
 *
 * <p>{@code Suite.create} writes {@code DRAFT} and nothing else has ever written that column, so
 * three of {@link LifecycleStatus}'s four values were unreachable on a suite and <strong>every
 * suite on every real instance has been a draft for its entire life</strong> — including the ones a
 * team agreed on a year ago. {@code Suite.applyEdit}'s own javadoc named the reason the mutator was
 * absent and, by naming it, named this slice: <em>"a status an ordinary edit can set is an approval
 * anyone can grant themselves by saving"</em>. {@code TestRun}'s finding in §72 and {@code TestPlan}'s
 * in §73, one aggregate over, found the same way.
 *
 * <h2>The permission is read from the row</h2>
 *
 * <p>{@link ProjectService#writeGrantedOnTarget} — §69's seam, its fourth consumer — because
 * {@link LifecycleLadder#permissionFor} needs <em>both</em> ends of the move and one of them is
 * stored. A gate that could only see the requested target would let a Test Engineer rescind a lead's
 * approval by asking for {@code DRAFT}; see {@link LifecycleLadder}'s header for the full argument
 * and for the fact that this was live on a case until the same slice fixed it.
 *
 * <p>The rule {@code writeGranted}'s javadoc states is kept: the permission is never nominated by
 * the request. What the request supplies is one end of an edge whose gate the <em>server</em>
 * computes, from a status only the database holds.
 *
 * <h2>Why the parse happens before the gate, and why an unreadable status asks the lower key</h2>
 *
 * <p>{@code PlanStatusService}'s reasoning, unchanged: the permission cannot be computed without the
 * target, so the parse comes first — which would otherwise hand a Viewer a 400 telling them what
 * this ladder accepts. So an unparseable value falls back to {@code suite:manage}. It names no edge,
 * it must still be gated, and gating it at the <em>lower</em> of the two keys refuses a caller who
 * may not touch suites at all before they learn the vocabulary, while a caller who may gets the
 * field error they can act on.
 *
 * <h2>No {@code If-Match}</h2>
 *
 * <p>{@code RunStatusService}'s reason, unchanged: the request names an absolute target state rather
 * than a delta against something the client read, so there is no lost update to protect. Two leads
 * both pressing Approve want the same outcome and get it. The rename beside it <em>does</em> take
 * one, because a name typed over a stale copy really does lose the other author's words.
 */
@Service
public class SuiteStatusService {

    private static final String AUDIT_TARGET = "suite";

    /**
     * The two keys this ladder is gated on. {@code suite:manage} is the edit key here — the module
     * has no {@code suite:edit}, and {@code suite:manage} is what {@link SuiteWriteService}'s create
     * and rename already ask.
     *
     * <p><strong>{@code suite:approve} is withheld from Test Engineer by {@code V3}</strong>, which
     * is what makes a gate mutation on this class reddening rather than vacuous: §71 had to record
     * that its two keys were granted to identical roles, so no persona the suite can build could
     * tell them apart. These two differ by exactly one role, and that role holds
     * {@code suite:manage}.
     */
    private static final LifecycleLadder.Keys KEYS =
            new LifecycleLadder.Keys("suite:manage", "suite:approve");

    private final ProjectService projects;
    private final SuiteRepository suites;
    private final SuiteService tree;
    private final ReviewService reviews;
    private final AuditEventRepository auditEvents;
    private final Clock clock;

    public SuiteStatusService(
            ProjectService projects,
            SuiteRepository suites,
            SuiteService tree,
            ReviewService reviews,
            AuditEventRepository auditEvents,
            Clock clock) {

        this.projects = projects;
        this.suites = suites;
        this.tree = tree;
        this.reviews = reviews;
        this.auditEvents = auditEvents;
        this.clock = clock;
    }

    /** What happened. Sealed, so the controller's switch over it is exhaustive at compile time. */
    public sealed interface Outcome {

        /** Unreachable project, or a suite that is not live in it — one bodiless 404 (§6t). */
        record NotFound() implements Outcome {
        }

        /** Readable, and the permission this particular move needs is not held. */
        record Forbidden() implements Outcome {
        }

        /** A status this ladder does not have. 400, on the field. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** A move the suite's current state refuses. 409, with {@link LifecycleLadder}'s sentence. */
        record Refused(String message) implements Outcome {
        }

        /** Moved, or already there. 200 with the suite either way. */
        record Updated(SuiteView view) implements Outcome {
        }
    }

    @Transactional
    public Outcome setStatus(
            UUID userId, UUID projectId, UUID suiteId, SuiteStatusRequest request) {

        UUID companyId = CompanyContext.require();
        LifecycleStatus requested = parse(request == null ? null : request.status());

        GateResult<Outcome> gated = projects.writeGrantedOnTarget(
                userId,
                projectId,
                project -> suites.findLiveInProject(project.getId(), suiteId),
                suite -> ProjectService.RequiredPermission.onProject(
                        requested == null
                                ? KEYS.edit()
                                : LifecycleLadder.permissionFor(
                                        suite.getLifecycleStatus(), requested, KEYS)),
                (project, suite) -> apply(companyId, userId, project.getId(), suite, requested));

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
            Suite suite,
            LifecycleStatus requested) {

        if (requested == null) {
            return new Outcome.Invalid(Map.of("status", List.of(
                    "Choose a status: DRAFT, IN_REVIEW, APPROVED or DEPRECATED.")));
        }

        return switch (LifecycleLadder.decide(suite.getLifecycleStatus(), requested, "suite")) {
            case LifecycleLadder.Decision.Refused refused -> new Outcome.Refused(refused.message());
            // 200 with the suite and nothing written — no timestamp re-stamped, no audit row. See
            // Decision.Unchanged: a repeated click must not bury the change that happened.
            case LifecycleLadder.Decision.Unchanged ignored ->
                    new Outcome.Updated(tree.viewOf(companyId, projectId, suite));
            case LifecycleLadder.Decision.Move move ->
                    // §6g's review policy gets its say AFTER the ladder has agreed the move exists
                    // and BEFORE anything is written — the placement §72 argued for, one rung in.
                    // A move the ladder does not have is refused as such rather than as a policy
                    // failure, so the two refusals never describe each other's problem.
                    reviews.refuses(
                                    projectId,
                                    ReviewSubjectType.SUITE,
                                    suite.getId(),
                                    LifecycleLadder.standingOf(move.target()),
                                    userId)
                            .<Outcome>map(Outcome.Refused::new)
                            .orElseGet(() ->
                                    move(companyId, userId, projectId, suite, move.target()));
        };
    }

    private Outcome move(
            UUID companyId, UUID userId, UUID projectId, Suite suite, LifecycleStatus target) {

        Instant now = clock.instant();
        LifecycleStatus previous = suite.getLifecycleStatus();

        suite.applyStatus(target, userId, now);
        suites.saveAndFlush(suite);

        // Inside this transaction, so the folder's status and the record of who reviewed it commit
        // together — see ReviewService#moved. The version pinned is the suite's current one, which
        // for a suite means its composition (SuiteVersions): approving a folder is a statement about
        // what was in it on the day, and that is exactly the number an auditor needs back.
        reviews.moved(
                projectId,
                ReviewSubjectType.SUITE,
                suite.getId(),
                suite.getCurrentVersionNo(),
                LifecycleLadder.standingOf(target),
                userId,
                now);

        auditEvents.save(AuditEvent.record(
                projectId,
                userId,
                action(previous, target),
                AUDIT_TARGET,
                List.of(suite.getId()),
                // Both ends of the move. "suite.approved" alone does not say which state it
                // replaced, and a suite that was approved, returned to draft and approved again is
                // three events whose order is only readable if each carries where it came from —
                // PlanStatusService's argument, and RunStatusService's before it.
                "{\"key\":" + AuditEvent.jsonString(suite.getKey())
                        + ",\"from\":" + AuditEvent.jsonString(previous.name())
                        + ",\"to\":" + AuditEvent.jsonString(target.name())
                        + "}",
                now));

        return new Outcome.Updated(tree.viewOf(companyId, projectId, suite));
    }

    /**
     * The audit action, by where the suite landed — and by where it came from for the one target two
     * different acts reach.
     *
     * <p>Distinct verbs rather than one {@code suite.status_changed}, for {@code PlanStatusService}'s
     * reason: plan §7's audit vocabulary lists the acts by name, and a reader asking "what did we
     * sign off last quarter" should not have to parse a payload to exclude the withdrawals.
     *
     * <p>{@code DRAFT} is the target reached by three different acts — withdrawing a submission,
     * rescinding an approval and reviving a retired suite — and those are not the same act to anybody
     * reading the trail, which is why this arm is the pair-sensitive one.
     */
    private static String action(LifecycleStatus previous, LifecycleStatus target) {
        return switch (target) {
            case IN_REVIEW -> "suite.submitted";
            case APPROVED -> "suite.approved";
            case DEPRECATED -> "suite.deprecated";
            case DRAFT -> switch (previous) {
                case APPROVED -> "suite.unapproved";
                case DEPRECATED -> "suite.revived";
                // IN_REVIEW. DRAFT is unreachable — Decision.Unchanged answered it and nothing is
                // audited on that path — and is written out so a fifth status is a compile error.
                case IN_REVIEW, DRAFT -> "suite.withdrawn";
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
