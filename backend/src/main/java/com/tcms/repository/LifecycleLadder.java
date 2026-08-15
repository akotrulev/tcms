package com.tcms.repository;

import com.tcms.project.ReviewStanding;

/**
 * Plan §6g's authoring ladder over {@link LifecycleStatus} — <strong>one transition table and one
 * permission rule, shared by the two aggregates that share the enum.</strong>
 *
 * <p>Pure and static, the shape {@code PlanLifecycle}, {@code RunLifecycle}, {@link BulkOperations}
 * and {@code PageContent} use: the decision is a property of two statuses, so it needs no row, no
 * gate and no transaction, and a test can enumerate all sixteen pairs without a database.
 *
 * <h2>Why this is one class and not two</h2>
 *
 * <p>{@link LifecycleStatus} is the status of <em>a suite or a case</em> — its own javadoc says so,
 * and {@code V1} spells the identical four-value check constraint on both tables. §6g draws one
 * diagram and then lists what review <em>means</em> per artifact, never a different set of moves.
 * So a second table would be a hand-maintained copy of a neighbour under an instruction to keep them
 * in step, which is §58's E1 and §70's verb list — both of which were already drifted by the time
 * anybody looked.
 *
 * <p>The plan's ladder is deliberately <em>not</em> folded in. {@code PlanStatus} carries
 * {@code ACTIVE} and {@code CLOSED}, an execution phase these four do not have, so unifying them
 * would need a supertype that does not exist and would give this table two arms no case can reach.
 * The shape is shared; the ranks are not.
 *
 * <h2>What this closes</h2>
 *
 * <p>{@code suite.lifecycle_status} had exactly one writer — {@code Suite.create}, writing
 * {@code DRAFT} — so three of its four values were unreachable and <strong>every suite on every real
 * instance has been a draft for its entire life</strong>. {@code TestPlan}'s finding one aggregate
 * over, and {@code TestRun}'s before it.
 *
 * <h2>The gate is decided by the EDGE, not by the target</h2>
 *
 * <p>This is the rule {@code PlanLifecycle} derived in §73, and its header named
 * {@code BulkOperations.permission} as the weaker shape it was correcting — <em>"the shape
 * BulkOperations.permission uses for a case"</em> — without then going back for it. On a case it was
 * still live: the request-level gate asked {@code case:approve} only when the target was
 * {@code APPROVED}, so a Test Engineer holding {@code case:edit} could select two hundred approved
 * cases and move them to {@code DRAFT}, <strong>rescinding a lead's sign-off with the permission
 * they use every day</strong>. Leaving an approval is an approval act too.
 *
 * <p>Hence {@link #permissionFor} takes both ends. <del>Roles spec §A4 principle 1, unchanged: a
 * Test Engineer is never blocked from producing, only from unmaking.</del> → <strong>§A4 principle 1
 * was rewritten by {@code V51} (§207) and this class follows it in §208</strong>: a Test Engineer has
 * full CRUD over <em>content</em>, and what stays Lead-only is shared structure and judgements about
 * the work. "Unmaking" collapsed two different things, and the {@code DEPRECATED} gate was where the
 * collapse showed — see {@link #permissionFor}.
 */
final class LifecycleLadder {

    private LifecycleLadder() {
    }

    /**
     * The two permission keys one aggregate's ladder is gated on.
     *
     * <p>A record rather than two {@code String} parameters, because two adjacent strings of the
     * same type are a swap the compiler cannot see and {@code -Werror} will not catch — and the
     * swapped version gates approval on the edit key, which is the defect this class exists to
     * close, silently reintroduced.
     *
     * @param edit    what carrying the artifact <em>through the states nobody has signed off</em>
     *                costs — submitting a draft for review (§6g: <em>"needs only edit rights"</em>),
     *                withdrawing that submission, retiring an unapproved artifact, and reviving a
     *                retired one
     * @param approve what granting the sign-off costs, and what touching an artifact that already
     *                has one costs — <em>whatever</em> the move's target happens to be
     */
    record Keys(String edit, String approve) {
    }

    /** What the request does to the artifact. Sealed, so the caller's switch is exhaustive. */
    sealed interface Decision {

        /** A real move. The caller applies it, stamps it and audits it. */
        record Move(LifecycleStatus target) implements Decision {
        }

        /**
         * Already in the requested state. Not an error at either call site: a status endpoint
         * answers 200 with the artifact ({@code PlanLifecycle}'s and {@code DocPageWriteService}'s
         * call — two leads approving the same thing is agreement, not a conflict), and a bulk
         * operation counts the row as unchanged rather than skipping it. Nothing is stamped and
         * nothing is audited, or a repeated click buries the change that actually happened.
         */
        record Unchanged() implements Decision {
        }

        /**
         * A move this ladder does not have. A <strong>409</strong> from a status endpoint rather
         * than a 400 — the payload is well-formed and the permission is held, it is the artifact's
         * current state that refuses — and a skip with the reason from a bulk operation, which is
         * {@code CaseBulkService}'s standing choice: one wrong row must not fail four hundred right
         * ones.
         *
         * @param message written for the person looking at the artifact, naming the way forward
         */
        record Refused(String message) implements Decision {
        }
    }

    /**
     * The transition table, in the one place both aggregates read it.
     *
     * <p>Four rules, and the two that refuse are the ones §6g argues for:
     *
     * <ul>
     *   <li><strong>{@code IN_REVIEW} only from {@code DRAFT}.</strong> §6g's diagram draws one
     *       submit edge and it starts at DRAFT; there is no APPROVED → IN_REVIEW arrow, because an
     *       approved artifact that needs another look has to lose its approval first — otherwise the
     *       trail shows a thing that was under review while it was also signed off.
     *       {@code PlanLifecycle} made the identical call.</li>
     *   <li><strong>{@code APPROVED} only from {@code IN_REVIEW}.</strong> §6g: <em>"the state
     *       machine has exactly one gate"</em>. Without this the gate guards a door standing beside
     *       an open one, and review is advisory in a workflow whose whole purpose is that it is
     *       not. This is the one rule {@code CaseBulkService} already had.</li>
     *   <li><strong>{@code DRAFT} from anywhere.</strong> Withdrawing a submission, rescinding an
     *       approval and reviving a retired artifact are three different acts that all land here,
     *       and each has to be reachable or a mis-click is unrepairable (§68's and §69's bound).
     *       What they cost differs, and that is {@link #permissionFor}'s question, not this one.</li>
     *   <li><strong>{@code DEPRECATED} from anywhere.</strong> Retiring is always available,
     *       including to a draft nobody ever reviewed — the alternative is a folder full of cases
     *       that can neither be deleted (the delete refuses a non-empty suite) nor retired, which
     *       is a dead end reachable by ordinary use. §208 is that argument carried into
     *       {@link #permissionFor}: the move being <em>legal</em> from a draft while costing a key
     *       no contributor holds left the same dead end one layer up, reachable by the same
     *       ordinary use.</li>
     * </ul>
     *
     * <p><strong>A retired artifact returns as a DRAFT, never straight to review or approval.</strong>
     * That is why {@code DEPRECATED} gets its own sentence rather than falling into the generic one:
     * §6g's clone rule makes the same argument — <em>a clone of an approved case is a draft, because
     * approval attaches to reviewed content by a named person and carrying it to something nobody
     * read is exactly the laundered sign-off an audit is meant to catch</em>. Telling that caller to
     * "submit it for review first" names a step they cannot take from where they are.
     *
     * @param noun what to call the artifact in a refusal — {@code "suite"} or {@code "case"}. The
     *             messages are the ladder's rather than each caller's, because the same failure must
     *             not read as two different problems depending on which screen reached it (§52).
     *             ~~And the mock has to be able to copy exactly one wording.~~ → the mock was
     *             deleted on 2026-08-06; §52's reason never needed the second one.
     */
    static Decision decide(LifecycleStatus current, LifecycleStatus requested, String noun) {
        if (current == requested) {
            return new Decision.Unchanged();
        }
        return switch (requested) {
            case DRAFT, DEPRECATED -> new Decision.Move(requested);

            case IN_REVIEW -> switch (current) {
                case DRAFT -> new Decision.Move(LifecycleStatus.IN_REVIEW);
                case DEPRECATED -> new Decision.Refused(revived(noun));
                case APPROVED -> new Decision.Refused(
                        "Only a draft " + noun + " can be submitted for review. Return this "
                                + noun + " to draft first.");
                // Unreachable — current == requested was answered Unchanged above. Written out
                // rather than left to a default arm so that a fifth LifecycleStatus is a compile
                // error in this table instead of quietly acquiring a transition nobody chose.
                case IN_REVIEW -> new Decision.Unchanged();
            };

            case APPROVED -> switch (current) {
                case IN_REVIEW -> new Decision.Move(LifecycleStatus.APPROVED);
                case DEPRECATED -> new Decision.Refused(revived(noun));
                case DRAFT -> new Decision.Refused(
                        "A " + noun + " is approved after it has been reviewed. Submit it for "
                                + "review first.");
                case APPROVED -> new Decision.Unchanged();
            };
        };
    }

    /** Said to anyone trying to take a retired artifact back up the ladder without passing DRAFT. */
    private static String revived(String noun) {
        return "This " + noun + " has been deprecated. Return it to draft before taking it through "
                + "review again.";
    }

    /**
     * The permission the move from {@code current} to {@code requested} needs.
     *
     * <p>Total over all sixteen pairs on purpose, including the pairs {@link #decide} goes on to
     * refuse: the gate runs <em>before</em> the transition is judged (the placement §72 argued for),
     * so a caller who may not approve is told 403 rather than handed a 409 describing a ladder they
     * may not climb. A pair that is both illegal and approval-shaped therefore answers the approve
     * key here and is refused afterwards — in that order.
     *
     * <p><strong>The whole rule is one sentence: the approve key buys a sign-off, and it buys
     * touching an artifact that has one.</strong> Two acts, and the second is the one a
     * target-decides rule misses:
     *
     * <ol>
     *   <li><strong>Granting the sign-off</strong> — {@code requested == APPROVED}. §6g: only Admin
     *       and Test Lead may perform the final transition.</li>
     *   <li><strong>Moving an artifact that is currently {@code APPROVED}</strong>, wherever it is
     *       going — back to {@code DRAFT}, back to {@code IN_REVIEW}, or out to {@code DEPRECATED}.
     *       Every one of those ends a lead's sign-off, and the target it happens to name says
     *       nothing about that. This is the arm that was missing on a case until §75.</li>
     * </ol>
     *
     * <h2>What {@code DEPRECATED} costs, decided on its own terms (§208)</h2>
     *
     * <p><del>Retiring is the soft form of a delete, and the delete is Lead-only
     * ({@code suite:delete} outright, {@code case:delete} {@code OWN}-qualified), so leaving the
     * retire at the edit key would route around both.</del> → <strong>superseded 2026-08-15.</strong>
     * {@code V51} (§207) made {@code case:delete} a plain grant every Test Engineer holds over every
     * case, which left the premise half gone and the conclusion inverted: a contributor could
     * <em>delete</em> a colleague's case outright and could not <em>retire</em> it — the stronger act
     * cheaper than the weaker one. §207 recorded that and did not repair it, on the grounds that the
     * repair belongs to the approval ladder rather than to a CRUD requirement.
     *
     * <p><strong>Retiring is now decided by what it is, not by what a delete costs.</strong> Retiring
     * an artifact <em>nobody has signed off</em> takes it out of use and changes no judgement anybody
     * made — it is the same kind of act as withdrawing a submission, and it costs the edit key. It is
     * also the only way out of a folder that cannot be deleted while it holds cases, which is the
     * dead end {@link #decide}'s {@code DEPRECATED}-from-anywhere rule already exists to avoid.
     * Retiring an artifact that <em>is</em> approved falls under rule 2 above and is unchanged.
     *
     * <p><del>Roles spec §A4 principle 1, as {@code V51} rewrote it, is what makes this hold on both
     * aggregates: a Test Engineer has full CRUD over content, and what stays Lead-only is shared
     * structure and judgements.</del> → <strong>{@code V52} (§210) deleted that principle a day
     * after {@code V51} restated it</strong>, and the rule above does not need it. Retiring costs
     * the edit key because retiring <em>is</em> an edit — on a case {@code case:edit}, on a suite
     * {@code suite:manage}, the key a contributor already uses to create, rename and file into
     * folders. Removing is still {@code suite:delete}, a separate key held by the same people, so
     * the two acts stay separately auditable and neither reading routes around anything. The
     * argument that had to go was the one about <em>who</em>; the argument about <em>what</em> is
     * the one that was doing the work.
     *
     * <p><strong>Reviving is symmetric, and that is a decision rather than a fallout</strong>
     * (2026-08-15, at the user's direction). {@code DEPRECATED → DRAFT} costs the edit key too. The
     * ladder cannot see whether the artifact was approved <em>before</em> it was retired — the pair
     * is the whole of its input — so the question is which way to resolve the ambiguity, and the
     * answer is that there is nothing left to unmake: {@link #standingOf} maps {@code DEPRECATED} to
     * {@link ReviewStanding#OUTSIDE_REVIEW}, so the sign-off ended at the retirement, performed by
     * somebody who held the approve key to do it. What comes back is a {@code DRAFT} that must pass
     * review again. The alternative — revival at the approve key — makes retiring a one-way door for
     * the contributor who may now walk through it, which is §68's and §69's bound and the argument
     * {@link #decide} already makes for {@code DRAFT}-from-anywhere.
     *
     * <p><strong>{@code rank} went with the old rule and its absence is part of the decision.</strong>
     * The private ordinal existed for this method alone and put {@code DEPRECATED} at 3 — <em>above</em>
     * approval — which is precisely what made retiring reachable only from the ladder's top and
     * reviving read as an unmaking. With the gate turning on one status rather than on an order
     * between two there is nothing left to compare, and a private method with no caller is §152's
     * shape. {@code PlanLifecycle#rank} is not the same copy and stays: {@code PlanStatus} has no
     * {@code DEPRECATED}, so its three values really are a line.
     */
    static String permissionFor(LifecycleStatus current, LifecycleStatus requested, Keys keys) {
        if (requested == LifecycleStatus.APPROVED) {
            return keys.approve();
        }
        return current == LifecycleStatus.APPROVED ? keys.approve() : keys.edit();
    }

    /**
     * Where a status sits <strong>relative to review</strong> — the translation
     * {@code project.ReviewService} needs, and the whole of what the reviewer apparatus knows about
     * this enum.
     *
     * <p>One mapping for both aggregates, for the same reason this class is one table: a case and a
     * suite share the enum, so a second copy in {@code SuiteStatusService} and a third in
     * {@code CaseBulkService} would be two hand-maintained readings of one four-value switch.
     *
     * <p>{@code DEPRECATED} maps to {@link ReviewStanding#OUTSIDE_REVIEW} together with
     * {@code DRAFT}, and that is deliberate rather than incidental: retiring an artifact <em>while
     * it is under review</em> ends the review, and it ends it the same way withdrawing does. Nobody
     * judged the content — the question was simply taken off the table.
     */
    static ReviewStanding standingOf(LifecycleStatus status) {
        return switch (status) {
            case DRAFT, DEPRECATED -> ReviewStanding.OUTSIDE_REVIEW;
            case IN_REVIEW -> ReviewStanding.IN_REVIEW;
            case APPROVED -> ReviewStanding.APPROVED;
        };
    }

}
