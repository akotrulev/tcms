package com.tcms.execution;

import com.tcms.project.ReviewStanding;

/**
 * Which plan transitions are legal, and <strong>which permission each edge needs</strong> — the
 * whole ladder decided in one pure class.
 *
 * <p>Pure and static, the shape {@link RunLifecycle}, {@code BulkOperations} and {@code PageContent}
 * use: the decision is a property of two statuses, so it needs no row, no gate and no transaction,
 * and a test can enumerate all twenty-five pairs without a database.
 *
 * <h2>What this closes</h2>
 *
 * <p>{@link PlanStatus} has five values and {@code TestPlan.create} writes {@code DRAFT}. Until this
 * class existed there was no other writer, so <strong>every plan on every real instance has been a
 * draft for ever</strong> — four of the five values were unreachable, {@code test_plan.closed_at}
 * was a column nothing filled, and {@code plan:edit}, {@code plan:review}, {@code plan:approve} all
 * had zero consumers in Java. {@code RunLifecycle}'s finding one aggregate over.
 *
 * <h2>The ladder must not require approval, because review is optional</h2>
 *
 * <p>Plan §6g opens with it: review is <em>"optional … enabled per project and per artifact type,
 * off by default, and a project that never turns it on should see no extra UI at all"</em>. The
 * per-project setting does not exist yet. A ladder that made {@code ACTIVE} reachable only through
 * {@code APPROVED} would therefore impose review on every project in the product and make starting a
 * plan impossible for a team that never asked for it — so <strong>{@code DRAFT → ACTIVE} is a legal
 * move</strong>, and review is a detour a project takes when it wants one.
 *
 * <h2>Two "reachable only from" rules, each with a precedent in this codebase</h2>
 *
 * <ul>
 *   <li><strong>{@code APPROVED} only from {@code IN_REVIEW}.</strong> §6g: <em>"the state machine
 *       has exactly one gate"</em>, and {@code CaseBulkService} made the identical call for a case —
 *       without it, approval is reachable without ever passing review and the gate guards a door
 *       standing beside an open one.</li>
 *   <li><strong>{@code CLOSED} only from {@code ACTIVE}.</strong> Closing says a release was
 *       executed and is over; a plan that was never started has nothing to say that about, and
 *       closing it would put a 0%-executed plan into every report beside plans that were really run
 *       — {@link RunLifecycle}'s argument for refusing {@code NOT_STARTED → COMPLETED}. The way out
 *       for a plan nobody used is {@code plan:delete}, which {@link PlanDeleteService} allows
 *       precisely while no run names it. <strong>The two acts partition the space</strong>: delete a
 *       plan nobody executed, close one somebody did.</li>
 * </ul>
 *
 * <h2>The gate is decided by the EDGE, not by the target</h2>
 *
 * <p>§6g says submitting for review <em>"needs only edit rights"</em> and only Admin/Test Lead may
 * perform the transition into {@code APPROVED}. Reading that as "the target decides" — the shape
 * {@code BulkOperations.permission} uses for a case — is not enough here, and the gap is the whole
 * reason this method takes both ends:
 *
 * <p><strong>Leaving an approval is an approval act too.</strong> {@code APPROVED → DRAFT} under a
 * target-decides rule would need only {@code plan:edit}, and a Test Engineer who holds it could
 * silently rescind a lead's sign-off — the release-readiness signature §6g calls <em>"often the gate
 * that matters most"</em> — by pressing the button that returns it to draft. Same for un-starting a
 * plan somebody is executing against, and for reopening a closed one. So {@code plan:approve} is
 * required to <em>grant</em> the sign-off, to <em>end</em> the plan, and to <em>take either back</em>;
 * {@code plan:edit} carries a plan forward through the states nobody has signed off. This is roles
 * spec §A4 principle 1 applied unchanged — a Test Engineer is never blocked from producing, only
 * from unmaking — and it is why {@code run:close}, {@code suite:delete} and {@code plan:delete} are
 * all Lead-only.
 */
final class PlanLifecycle {

    private PlanLifecycle() {
    }

    /** Roles spec §A4 — Instance Admin, Company Admin, Test Lead, Test Engineer. */
    static final String EDIT_PLAN = "plan:edit";

    /**
     * Roles spec §A4 — Instance Admin, Company Admin, Test Lead. <strong>Withheld from Test
     * Engineer</strong>, which is what makes the gate mutation on this class reddening rather than
     * vacuous: §71 had to record that its two keys were granted to identical roles, so no persona
     * the suite can build could tell them apart. These two differ by exactly one role, and that role
     * holds {@code plan:create} and {@code plan:edit} — so the persona exists.
     */
    static final String APPROVE_PLAN = "plan:approve";

    /**
     * §6g's gating refusal for a plan (§82) — see {@link #startsWithoutApproval}.
     *
     * <p>A constant rather than a sentence built at the call site, for the reason
     * {@code RunLifecycle.CLOSED_MESSAGE} gives: one rule must not be described two ways. Unlike a
     * run's refusal there is nothing to interpolate — a plan is one row, so there is no list of
     * offenders to name, which is also why the run's sentence could not be a constant.
     */
    static final String APPROVAL_REQUIRED_MESSAGE =
            "This project only starts approved plans. Submit this plan for review and have it "
                    + "approved before starting it.";

    /** What the request does to the plan. Sealed, so the caller's switch is exhaustive. */
    sealed interface Decision {

        /** A real move. The service applies it, derives {@code closedAt} and audits it. */
        record Move(PlanStatus target) implements Decision {
        }

        /**
         * The plan is already in the requested state. 200 with the plan, not 409 — two leads
         * approving the same plan is agreement rather than a conflict, the call
         * {@code DocPageWriteService#setStatus} and then {@link RunLifecycle} both made. Nothing is
         * stamped and nothing is audited, or a repeated click buries the change that actually
         * happened under rows recording that nothing did.
         */
        record Unchanged() implements Decision {
        }

        /**
         * A move this ladder does not have. A <strong>409</strong>, not a 400: the payload is
         * well-formed and the permission is held — it is the plan's current state that refuses.
         *
         * @param message written for the person looking at the plan, naming the way forward
         */
        record Refused(String message) implements Decision {
        }
    }

    /** Whether the plan is over. One terminal value, unlike a run's pair. */
    static boolean isClosed(PlanStatus status) {
        return status == PlanStatus.CLOSED;
    }

    /**
     * Whether this move is <strong>starting a plan that has not been approved</strong> — the edge
     * §6g's gating rule refuses on a project that turned {@code requireApprovedPlanToStart} on
     * (§82).
     *
     * <p>Here rather than in {@link PlanStatusService} because it is a property of two statuses and
     * nothing else, which is what this class is for — and because getting it wrong is invisible in
     * a service and obvious beside the table above.
     *
     * <h2>ONE EDGE, not one target, and that is the whole of the rule</h2>
     *
     * <p>{@link #decide} reaches {@code ACTIVE} from three places, and only one of them is somebody
     * starting an unreviewed plan:
     *
     * <ul>
     *   <li>{@code DRAFT → ACTIVE} — the move §6g's ladder allows precisely <em>because</em> review
     *       is optional (see this class's header), and therefore the one a project turning gating on
     *       is asking to close;</li>
     *   <li>{@code APPROVED → ACTIVE} — starting a signed-off plan, which is the rule being
     *       satisfied rather than an exception to it;</li>
     *   <li><strong>{@code CLOSED → ACTIVE} — a REOPEN, and refusing it would be a defect.</strong>
     *       A closed plan's status is {@code CLOSED}; no legal move takes it back to
     *       {@code APPROVED}, and {@code CLOSED → DRAFT} is refused outright. So a target-keyed rule
     *       would make every closed plan on a gated project <em>permanently un-reopenable</em>,
     *       whatever anybody's permissions — a dead end reachable by ordinary use, which is the
     *       bound §68 and §69 each drew from the other side. {@code PlanStatusService} had already
     *       named this edge as a distinct act before there was a rule about it: it audits this one
     *       as {@code plan.reopened} and the other two as {@code plan.started}, so §6g's word
     *       <em>started</em> was defined in the code before the gate needed it.</li>
     * </ul>
     *
     * <p>{@code IN_REVIEW → ACTIVE} never reaches this method: {@link #decide} refuses it first, and
     * with a better sentence — the plan is <em>in</em> review, so "approve it or return it to draft"
     * names the two ways out, where the gate's sentence would say only that it is not approved.
     */
    static boolean startsWithoutApproval(PlanStatus current, PlanStatus target) {
        return target == PlanStatus.ACTIVE && current == PlanStatus.DRAFT;
    }

    /**
     * The permission the move from {@code current} to {@code requested} needs.
     *
     * <p>Total over all twenty-five pairs on purpose, including the pairs {@link #decide} goes on to
     * refuse: the gate runs <em>before</em> the transition is judged (the placement §72 argued for),
     * so a caller who may not approve is told 403 rather than being handed a 409 that describes a
     * ladder they may not climb. A pair that is both illegal and approval-shaped therefore answers
     * {@link #APPROVE_PLAN} here and is refused afterwards — in that order.
     */
    static String permissionFor(PlanStatus current, PlanStatus requested) {
        if (requested == PlanStatus.APPROVED || requested == PlanStatus.CLOSED) {
            // Granting the sign-off, and ending the plan.
            return APPROVE_PLAN;
        }
        // Taking either back: a move down the ladder out of a plan that has been signed off,
        // started, or closed. See the class header — this is the arm a target-decides rule misses.
        boolean unmaking = rank(requested) < rank(current) && rank(current) >= rank(PlanStatus.APPROVED);
        return unmaking ? APPROVE_PLAN : EDIT_PLAN;
    }

    /**
     * The transition table.
     *
     * <p>No {@code hasStarted}-style normalization, unlike {@link RunLifecycle#decide}: a plan's
     * status is chosen outright rather than derived, because there is no second column here that
     * could contradict it. A run needed one because {@code IN_PROGRESS} and {@code NOT_STARTED} are
     * both "open" and only {@code started_at} says which is true.
     */
    static Decision decide(PlanStatus current, PlanStatus requested) {
        if (current == requested) {
            return new Decision.Unchanged();
        }
        return switch (requested) {
            case IN_REVIEW -> current == PlanStatus.DRAFT
                    ? new Decision.Move(PlanStatus.IN_REVIEW)
                    : new Decision.Refused(
                            "Only a draft plan can be submitted for review. "
                                    + "Return this plan to draft first.");
            case APPROVED -> current == PlanStatus.IN_REVIEW
                    ? new Decision.Move(PlanStatus.APPROVED)
                    : new Decision.Refused(
                            "A plan is approved after it has been reviewed. "
                                    + "Submit it for review first.");
            case ACTIVE -> switch (current) {
                // Straight from draft: the project never turned review on, which is the default.
                case DRAFT, APPROVED, CLOSED -> new Decision.Move(PlanStatus.ACTIVE);
                case IN_REVIEW -> new Decision.Refused(
                        "This plan is still in review. Approve it, or return it to draft, "
                                + "before starting it.");
                case ACTIVE -> new Decision.Unchanged();
            };
            case CLOSED -> current == PlanStatus.ACTIVE
                    ? new Decision.Move(PlanStatus.CLOSED)
                    : new Decision.Refused(
                            "Only a plan that is being executed can be closed. Start the plan, "
                                    + "or delete it if it was never used.");
            case DRAFT -> current == PlanStatus.CLOSED
                    // Reopening a closed plan lands on ACTIVE, never here: a closed release that
                    // turns out to need more testing is still the release it was, and dropping it
                    // to draft would say the scope had never been agreed. The one extra call is the
                    // same shape RunLifecycle's reopen takes, and it keeps both endings real events.
                    ? new Decision.Refused(
                            "This plan has been closed. Reopen it before returning it to draft.")
                    : new Decision.Move(PlanStatus.DRAFT);
        };
    }

    /**
     * Where a status sits <strong>relative to review</strong> — the translation
     * {@code project.ReviewService} needs, and the whole of what the reviewer apparatus knows about
     * this enum.
     *
     * <p>{@code LifecycleLadder} carries the same method for the case-and-suite enum, and the two
     * are not a duplicated rule: each maps a <em>different</em> enum, which is precisely why the
     * review record takes a standing rather than a status. This one has the arm the other cannot —
     * a plan that is {@code ACTIVE} or {@code CLOSED} is being executed, which is not a review state
     * at all and must not read as one. Starting an approved plan therefore writes nothing to its
     * review, and closing a release does not retract the sign-off it was started on.
     */
    static ReviewStanding standingOf(PlanStatus status) {
        return switch (status) {
            case DRAFT, ACTIVE, CLOSED -> ReviewStanding.OUTSIDE_REVIEW;
            case IN_REVIEW -> ReviewStanding.IN_REVIEW;
            case APPROVED -> ReviewStanding.APPROVED;
        };
    }

    /**
     * How far along the ladder a status sits — used only by {@link #permissionFor}, and deliberately
     * <em>not</em> by {@link #decide}, which spells its table out.
     *
     * <p>An ordinal comparison decides an authorization question here, which is what
     * {@code PermissionQualifier.strongest} was rewritten in §61 to stop doing. The difference is
     * that this order is <em>the domain's own</em> — DRAFT before review before approval before
     * execution before closure is what the ladder means — and it is written out rather than read off
     * {@code Enum#compareTo}, so re-ordering the enum's declaration cannot silently move a gate.
     */
    private static int rank(PlanStatus status) {
        return switch (status) {
            case DRAFT -> 0;
            case IN_REVIEW -> 1;
            case APPROVED -> 2;
            case ACTIVE -> 3;
            case CLOSED -> 4;
        };
    }
}
