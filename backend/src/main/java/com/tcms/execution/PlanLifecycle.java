package com.tcms.execution;

import com.tcms.project.ReviewStanding;

/**
 * Which plan transitions are legal, and <strong>which permission each edge needs</strong> — the
 * whole ladder decided in one pure class.
 *
 * <p>Pure and static, the shape {@link RunLifecycle}, {@code BulkOperations} and {@code PageContent}
 * use: the decision is a property of two statuses, so it needs no row, no gate and no transaction,
 * and a test can enumerate all nine pairs without a database.
 *
 * <h2>What this closes</h2>
 *
 * <p>{@link PlanStatus} has three values and {@code TestPlan.create} writes {@code DRAFT}. Until this
 * class existed there was no other writer, so <strong>every plan on every real instance had been a
 * draft for ever</strong> — the other values were unreachable, and {@code plan:edit},
 * {@code plan:review}, {@code plan:approve} all had zero consumers in Java.
 * {@code RunLifecycle}'s finding one aggregate over.
 *
 * <h2>The ladder is three states since §97, and two rules died with the other two</h2>
 *
 * <p>~~<strong>The ladder must not require approval, because review is optional.</strong> A ladder
 * that made {@code ACTIVE} reachable only through {@code APPROVED} would impose review on every
 * project in the product, so {@code DRAFT → ACTIVE} is a legal move and review is a detour.~~<br>
 * ~~<strong>{@code CLOSED} only from {@code ACTIVE}.</strong> Closing says a release was executed
 * and is over. <strong>The two acts partition the space</strong>: delete a plan nobody executed,
 * close one somebody did.~~
 *
 * <p>→ <strong>superseded 2026-08-05 (M7).</strong> Both states describe a plan being <em>run</em>,
 * which is {@code test_run}'s business — see {@link PlanStatus} for the whole argument. Two
 * consequences are worth naming rather than leaving to be rediscovered:
 *
 * <ul>
 *   <li><strong>§82's gating rule goes with them, and not as collateral.</strong> It bound on
 *       exactly one edge, {@code DRAFT → ACTIVE}, and after M7 there is no such edge. §82's finding
 *       — <em>"the plan rule is an EDGE, not a target"</em>, which cost a slice to discover and
 *       whose naive target-keyed reading made every closed plan on a gated project permanently
 *       un-reopenable — becomes <strong>moot rather than wrong</strong>. It is written down because
 *       a finding that expensive should not vanish silently with the states it was about.</li>
 *   <li><strong>{@link PlanDeleteService}'s partition goes too.</strong> With no {@code CLOSED},
 *       a plan somebody executed against has no terminal state, and delete stays refused while a
 *       run names it. Such a plan is permanently {@code APPROVED}, which is correct under M7: its
 *       execution state lives on its runs.</li>
 * </ul>
 *
 * <p>What survives unchanged is the one gate §6g asked for: <strong>{@code APPROVED} only from
 * {@code IN_REVIEW}</strong>. §6g: <em>"the state machine has exactly one gate"</em>, and
 * {@code CaseBulkService} made the identical call for a case — without it, approval is reachable
 * without ever passing review and the gate guards a door standing beside an open one.
 *
 * <h2>The gate is decided by the EDGE, not by the target</h2>
 *
 * <p>§6g says submitting for review <em>"needs only edit rights"</em> and only Admin/Test Lead may
 * perform the transition into {@code APPROVED}. Reading that as "the target decides" — the shape
 * {@code BulkOperations.permission} uses for a case — is not enough here, and the gap is the whole
 * reason {@link #permissionFor} takes both ends:
 *
 * <p><strong>Leaving an approval is an approval act too.</strong> {@code APPROVED → DRAFT} under a
 * target-decides rule would need only {@code plan:edit}, and a Test Engineer who holds it could
 * silently rescind a lead's sign-off — the release-readiness signature §6g calls <em>"often the gate
 * that matters most"</em> — by pressing the button that returns it to draft. So {@code plan:approve}
 * is required to <em>grant</em> the sign-off and to <em>take it back</em>; {@code plan:edit} carries
 * a plan forward through the states nobody has signed off. This is roles spec §A4 principle 1
 * applied unchanged — a Test Engineer is never blocked from producing, only from unmaking — and it
 * is why {@code run:close}, {@code suite:delete} and {@code plan:delete} are all Lead-only.
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

    /** What the request does to the plan. Sealed, so the caller's switch is exhaustive. */
    sealed interface Decision {

        /** A real move. The service applies it and audits it. */
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

    /**
     * The permission the move from {@code current} to {@code requested} needs.
     *
     * <p>Total over all nine pairs on purpose, including the pairs {@link #decide} goes on to
     * refuse: the gate runs <em>before</em> the transition is judged (the placement §72 argued for),
     * so a caller who may not approve is told 403 rather than being handed a 409 that describes a
     * ladder they may not climb. A pair that is both illegal and approval-shaped therefore answers
     * {@link #APPROVE_PLAN} here and is refused afterwards — in that order.
     */
    static String permissionFor(PlanStatus current, PlanStatus requested) {
        if (requested == PlanStatus.APPROVED) {
            // Granting the sign-off.
            return APPROVE_PLAN;
        }
        // Taking it back: a move down the ladder out of a plan that has been signed off. See the
        // class header — this is the arm a target-decides rule misses.
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
     *
     * <p><strong>{@code APPROVED → DRAFT} is legal and {@code APPROVED → IN_REVIEW} is not.</strong>
     * Withdrawing a sign-off returns the plan to its author, who resubmits it; a plan sent straight
     * back into review would be one nobody edited, sitting in a reviewer's queue a second time with
     * the same content. That is §6g's one gate read forwards.
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
            case DRAFT -> new Decision.Move(PlanStatus.DRAFT);
        };
    }

    /**
     * Where a status sits <strong>relative to review</strong> — the translation
     * {@code project.ReviewService} needs, and the whole of what the reviewer apparatus knows about
     * this enum.
     *
     * <p>{@code LifecycleLadder} carries the same method for the case-and-suite enum, and the two
     * are not a duplicated rule: each maps a <em>different</em> enum, which is precisely why the
     * review record takes a standing rather than a status.
     *
     * <p>~~This one has the arm the other cannot — a plan that is {@code ACTIVE} or {@code CLOSED}
     * is being executed, which is not a review state at all.~~ → <strong>gone with M7</strong>: with
     * three states the mapping is total and unremarkable, and the two enums now differ only in that
     * a case may be {@code DEPRECATED}.
     */
    static ReviewStanding standingOf(PlanStatus status) {
        return switch (status) {
            case DRAFT -> ReviewStanding.OUTSIDE_REVIEW;
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
     * that this order is <em>the domain's own</em> — DRAFT before review before approval is what the
     * ladder means — and it is written out rather than read off {@code Enum#compareTo}, so
     * re-ordering the enum's declaration cannot silently move a gate.
     */
    private static int rank(PlanStatus status) {
        return switch (status) {
            case DRAFT -> 0;
            case IN_REVIEW -> 1;
            case APPROVED -> 2;
        };
    }
}
