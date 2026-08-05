package com.tcms.execution;

/**
 * §6g's gating rules as a shared <em>vocabulary</em> — <strong>not as a shared rule</strong> (§82).
 *
 * <p>Plan §6g's mechanics end with one line: <em>"a project may require APPROVED status before a
 * case can be added to a run, or before a plan can be started. Powerful and genuinely obstructive —
 * must be an explicit choice, with a documented lead override for the 2am release."</em> That was
 * two rules over two aggregates and <strong>it is one since §97</strong>: {@link RunCreateService}
 * holds the case rule, and the plan rule went with the {@code ACTIVE} state M7 removed — a plan
 * cannot be "started" any more, so there is no edge for it to bind on ({@link PlanStatus}).
 *
 * <p>This class stays, with one caller, and that is deliberate rather than inertia. Its whole
 * subject is <strong>what the answer means</strong> — see {@link Verdict} — and the two invariants
 * below are the ones a second gating rule will get wrong when §99 adds tag-scoped runs. Collapsing
 * it into {@code RunCreateService} would put them where only that service can read them.
 *
 * <h2>What is shared, and what deliberately is not</h2>
 *
 * <p>The two rules have nothing in common as computations. One asks a {@code repository} table for
 * the statuses of up to four hundred cases and names the offenders; the other compares two enum
 * values it already holds. Folding them into one method behind a {@code Supplier<String>} would
 * abstract over the only parts that differ, which is the shape {@code CLAUDE.md} calls out and the
 * plugin rules call pattern stacking.
 *
 * <p>What they genuinely share is the <strong>meaning of the answer</strong>, and that is worth
 * having in one place because getting it wrong is silent both ways. Two byte-similar records named
 * the same thing in two services, each with its own comment about what {@code overridden} means, is
 * §58's E1 — and §76 records what that costs: {@code CaseOwners} and {@code ExecutionPeople} were
 * allowed to become two copies under a comment saying they must not disagree, and neither module
 * could import the other to fix it. Here both callers are in one module, so the copy is prevented
 * rather than mourned.
 */
final class ApprovalGating {

    private ApprovalGating() {
    }

    /**
     * What §6g's gate decided about one request.
     *
     * <p>Each caller builds this in three lines against its own facts. The two invariants it exists
     * to hold are stated once, here:
     *
     * <p><strong>{@code refusal == null} means proceed</strong>, and never means "refused with
     * nothing to say". A refusal has to name what is wrong — the cases, or the status the plan is
     * in — because the whole cost of §6g's <em>"genuinely obstructive"</em> rule falls on somebody
     * who is trying to start work and needs to know what to do next. Null rather than an
     * {@code Optional} because {@link #overridden} is only meaningful when there is no refusal, and
     * two {@code Optional}s on one record invite a caller to read them independently.
     *
     * <p><strong>{@code overridden} means the gate FIRED and was overridden</strong>, which is
     * strictly narrower than "the caller sent the flag". A request that sets it on a project with
     * gating switched off, or on a plan that is approved anyway, changed nothing — and auditing it
     * as an override would put a line in the record saying somebody bypassed a policy they in fact
     * complied with. That distinction is the entire value of §6g's word <em>documented</em>: the
     * audit field is only ever written when it is true.
     */
    record Verdict(String refusal, boolean overridden) {

        /** The gate does not bind, or binds and is satisfied. */
        static final Verdict PROCEED = new Verdict(null, false);

        /**
         * The gate binds and is not satisfied.
         *
         * @param mayOverride the caller asked to override <em>and</em> holds the artifact's approve
         *                    key. Both halves, and evaluated by the caller so the permission
         *                    question short-circuits away on the ordinary request that did not ask
         *                    — it is asked only on a path that is already failing, which is
         *                    {@code CaseBulkService}'s placement and its reason.
         * @param refusal     what to say when {@code mayOverride} is false. Also what a caller who
         *                    asked to override and may not is told, deliberately, rather than a
         *                    403: they are authorized to make this request and are not authorized
         *                    to ignore the project's policy, so the policy's own sentence — which
         *                    names the way forward — is the honest answer, where a 403 would send
         *                    them to an administrator about a grant they already hold.
         */
        static Verdict fired(boolean mayOverride, String refusal) {
            return mayOverride ? new Verdict(null, true) : new Verdict(refusal, false);
        }
    }
}
