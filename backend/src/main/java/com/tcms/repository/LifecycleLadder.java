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
 * <p>Hence {@link #permissionFor} takes both ends. Roles spec §A4 principle 1, unchanged: a Test
 * Engineer is never blocked from producing, only from unmaking.
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
     * @param edit    what carrying the artifact forward costs — §6g: submitting for review
     *                <em>"needs only edit rights"</em>
     * @param approve what granting the sign-off, retiring the artifact, or taking either back costs
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
     *       is a dead end reachable by ordinary use.</li>
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
     * <p>Three acts cost the approve key, and the third is the one a target-decides rule misses:
     *
     * <ol>
     *   <li><strong>Granting the sign-off</strong> — {@code APPROVED}. §6g: only Admin and Test Lead
     *       may perform the final transition.</li>
     *   <li><strong>Retiring the artifact</strong> — {@code DEPRECATED}. The soft form of a delete,
     *       and <del>the delete is Lead-only ({@code suite:delete} outright, {@code case:delete}
     *       {@code OWN}-qualified), so leaving the retire at the edit key would route around
     *       both</del>.
     *
     *       <p><strong>KNOWN INCOHERENCE SINCE {@code V51} (§207), LEFT STANDING DELIBERATELY AND
     *       NOT ENDORSED.</strong> Half that premise is gone: {@code case:delete} is now a plain
     *       grant every Test Engineer holds over every case. So a contributor may <em>delete</em> a
     *       colleague's case outright and may <em>not</em> deprecate it — the stronger act is
     *       cheaper than the weaker one, and the "routes around the delete" argument now runs
     *       backwards on a case. It still holds for a suite, where {@code suite:delete} is Lead-only
     *       and unchanged.
     *
     *       <p>Not repaired here because §207's requirement is about CRUD, and moving this gate is a
     *       change to the <em>approval ladder</em> — the key it costs is {@code case:approve}, by
     *       §72's placement decision, and that ladder was explicitly out of scope. The repair, when
     *       someone takes it, is to decide whether retiring is an edit or an approval on its own
     *       terms rather than by reference to what a delete costs. Recorded so the next reader does
     *       not take the surviving prose as a live argument. Handoff §207 carries it as a named
     *       follow-up.
     *
     *       <p>The rest is unchanged: this tightens what a Test Engineer could do to a <em>draft</em>
     *       case through the bulk endpoint, deliberately. <del>The {@code OWN}-qualified refinement —
     *       an author may retire their own — is the natural next rung.</del> → no longer available as
     *       a rung: no grant carries {@code OWN} after {@code V51}, so a refinement built on it would
     *       be reintroducing authorship-scoped writes that §207 removed.</li>
     *   <li><strong>Taking either back</strong> — any move down out of a state at or above
     *       {@code APPROVED}. This is the arm that was missing on a case.</li>
     * </ol>
     */
    static String permissionFor(LifecycleStatus current, LifecycleStatus requested, Keys keys) {
        if (requested == LifecycleStatus.APPROVED || requested == LifecycleStatus.DEPRECATED) {
            return keys.approve();
        }
        boolean unmaking =
                rank(requested) < rank(current) && rank(current) >= rank(LifecycleStatus.APPROVED);
        return unmaking ? keys.approve() : keys.edit();
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

    /**
     * How far along the ladder a status sits — used only by {@link #permissionFor}, and deliberately
     * <em>not</em> by {@link #decide}, which spells its table out.
     *
     * <p>{@code PlanLifecycle#rank}'s reasoning, unchanged: an ordinal comparison decides an
     * authorization question here, which is what {@code PermissionQualifier.strongest} was rewritten
     * in §61 to stop doing. The difference is that this order is <em>the domain's own</em> — drafted
     * before reviewed before approved before retired — and it is written out rather than read off
     * {@code Enum#compareTo}, so re-ordering the enum's declaration cannot silently move a gate.
     */
    private static int rank(LifecycleStatus status) {
        return switch (status) {
            case DRAFT -> 0;
            case IN_REVIEW -> 1;
            case APPROVED -> 2;
            case DEPRECATED -> 3;
        };
    }
}
