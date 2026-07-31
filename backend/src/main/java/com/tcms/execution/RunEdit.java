package com.tcms.execution;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A validated, normalized create of one run — and, with it, the <strong>resolved case set</strong>
 * the run will materialise an execution for.
 *
 * <p>The case set is part of the validated value rather than something the service recomputes,
 * because deciding it is most of what validating a run means: it is what "is this run creatable"
 * actually asks. Splitting the decision from the check is how a run gets created with a scope
 * different from the one that was checked.
 *
 * <h2>Where the cases come from, in order</h2>
 *
 * <ol>
 *   <li><strong>{@code caseIds}, when the body carries them.</strong> An explicit selection wins,
 *       and it is how an ad-hoc run of three cases is created — the SPA's "run selected cases"
 *       affordance. Every id must be a live case in this project.</li>
 *   <li><strong>Otherwise the plan's scope, expanded.</strong> The same expansion the plan's own
 *       {@code caseCount} displays ({@link CaseFacts#caseIdsInPlanScope}), so a run created from a
 *       plan has the length the previous screen said it would.</li>
 * </ol>
 *
 * <p>Not the union of the two. A body carrying both a plan and a case list means "this run is
 * against that plan, and here is the subset we are doing this pass" — which is a real thing to want
 * during a stabilisation — and unioning them would silently add back the cases somebody explicitly
 * narrowed away. The run still records its {@code planId}, so the narrowing is visible as a run
 * shorter than its plan rather than as a lost association.
 *
 * <h2>A run with nothing to execute is refused</h2>
 *
 * <p>Unlike a plan, whose empty scope is an ordinary intermediate state. A run is a commitment to
 * test a specific set on a specific build; an empty one has no checklist, so every count on it is
 * zero and it renders <strong>as though it had finished</strong> — 0 of 0, 100%, complete. The
 * person who created it would have to notice the absence of rows to know it never started. Refusing
 * at the field the caller can act on is the honest answer, and it is the one shape of "successful
 * write that produced nothing usable" this module can still avoid.
 */
record RunEdit(
        String name,
        UUID planId,
        String releaseVersion,
        String buildNumber,
        UUID assigneeId,
        List<UUID> caseIds) {

    /** A run name is a label — "Regression 4.3.0", "Smoke, build 4471". */
    static final int MAX_NAME = 200;

    /** {@code release_version} and {@code build_number} are both short identifiers, not prose. */
    static final int MAX_RELEASE = 100;

    /**
     * The most executions one create will materialise.
     *
     * <p>Every one is an inserted row inside a single transaction, and the transaction holds the
     * company's {@code key_sequence} lock for its whole duration — so an unbounded run create is an
     * unbounded stall on <em>every other creation in the company</em>. 5,000 is past any manual
     * regression pass and short of an import, which is the bulk slice's problem and has ERD §5's
     * range allocation waiting for it.
     */
    static final int MAX_EXECUTIONS = 5000;

    /** Sealed so the caller's mapping is exhaustive. */
    sealed interface Outcome {

        /** Field name, as spelled on {@code TestRunInput}, to its messages. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        record Valid(RunEdit edit) implements Outcome {
        }
    }

    /**
     * Validates a create against facts the service has already looked up.
     *
     * @param planExists     whether {@code planId} names a live plan <em>in this project</em>. False
     *                       both for a plan that is not there and for one in a sibling project — the
     *                       distinction is the one §6t refuses to publish, and the field error is
     *                       the same sentence either way.
     * @param liveCaseIds    of the requested {@code caseIds}, those naming a live case here
     * @param resolvedCases  the case set this run would cover, already resolved in the order it will
     *                       be materialised
     * @param assigneeExists whether {@code assigneeId} names a member of this company
     * @param pinnableCases  of {@code resolvedCases}, those that have a current version row to pin
     */
    static Outcome validateCreate(
            RunWriteRequest request,
            boolean planExists,
            Set<UUID> liveCaseIds,
            List<UUID> resolvedCases,
            boolean assigneeExists,
            Set<UUID> pinnableCases) {

        Map<String, List<String>> errors = new java.util.LinkedHashMap<>();

        String name = PlanEdit.trimToNull(request.name());
        if (name == null) {
            PlanEdit.error(errors, "name", "Enter a run name.");
        } else if (name.length() > MAX_NAME) {
            PlanEdit.error(errors, "name", "Keep the name under " + MAX_NAME + " characters.");
        }

        if (request.planId() != null && !planExists) {
            PlanEdit.error(errors, "planId", "That plan is not in this project any more.");
        }

        String releaseVersion = PlanEdit.trimToNull(request.releaseVersion());
        if (releaseVersion != null && releaseVersion.length() > MAX_RELEASE) {
            PlanEdit.error(errors, "releaseVersion",
                    "Keep the release version under " + MAX_RELEASE + " characters.");
        }

        String buildNumber = PlanEdit.trimToNull(request.buildNumber());
        if (buildNumber != null && buildNumber.length() > MAX_RELEASE) {
            PlanEdit.error(errors, "buildNumber",
                    "Keep the build number under " + MAX_RELEASE + " characters.");
        }

        if (request.assigneeId() != null && !assigneeExists) {
            PlanEdit.error(errors, "assigneeId", "That person is not a member of this company.");
        }

        List<UUID> requestedCases = PlanEdit.distinct(request.caseIds());
        for (UUID caseId : requestedCases) {
            if (!liveCaseIds.contains(caseId)) {
                PlanEdit.error(errors, "caseIds",
                        "One of the selected cases is not in this project any more.");
                break;
            }
        }

        if (resolvedCases.isEmpty()) {
            // On planId when the scope came from a plan, on caseIds otherwise — the field the caller
            // can actually change. A message on the wrong field is one the form has nowhere to put.
            String field = request.planId() != null && requestedCases.isEmpty()
                    ? "planId" : "caseIds";
            PlanEdit.error(errors, field,
                    "This run would have nothing to execute. Choose cases, or a plan whose scope "
                            + "still has some.");
        } else if (resolvedCases.size() > MAX_EXECUTIONS) {
            PlanEdit.error(errors, "caseIds",
                    "A run covers at most " + MAX_EXECUTIONS + " cases. Split it, or narrow the "
                            + "plan's scope.");
        } else if (!pinnableCases.containsAll(resolvedCases)) {
            // Refused rather than silently shortened: an execution must name the version it ran, so
            // a case with no version row cannot be in this run at all — and a checklist quietly
            // missing cases is one nobody can tell from a checklist that is complete. Unreachable
            // for any case authored since case-create wrote v1 with the case (§29).
            PlanEdit.error(errors, "caseIds",
                    "Some of these cases have no saved version yet, so a run cannot record which "
                            + "version it tested. Open and save them first.");
        }

        return errors.isEmpty()
                ? new Outcome.Valid(new RunEdit(
                        name, request.planId(), releaseVersion, buildNumber, request.assigneeId(),
                        resolvedCases))
                : new Outcome.Invalid(errors);
    }
}
