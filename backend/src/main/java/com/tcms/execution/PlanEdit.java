package com.tcms.execution;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A validated, normalized create of one plan.
 *
 * <p>The shape {@code project.ProjectEdit} and {@code repository.SuiteEdit} established: the request
 * is normalized once, before anything acts on it, so no service, entity or query re-derives whether
 * the input was acceptable. A rule enforced half here and half in the entity is a rule with two
 * answers, and the one that wins is whichever ran second.
 *
 * <h2>The scope is validated against the project, not merely against the schema</h2>
 *
 * <p>{@code plan_scope_item.suite_id} and {@code case_id} are foreign keys, so the database already
 * refuses an id naming nothing, and RLS already refuses another company's row. Neither refuses a
 * suite or a case from a <strong>sibling project in the caller's own company</strong> — and a Test
 * Lead who holds both projects can produce that id honestly, by copying it from the other tab. The
 * result would be a plan whose scope silently spans two products and whose {@code caseCount} counts
 * cases nobody in this project can see on its own case list.
 *
 * <p>So the live-id sets are computed by {@link CaseFacts} and passed in, and an id outside them is
 * a field error. Passed in rather than looked up here for {@code ProjectEdit}'s reason: this type
 * stays a pure function of its inputs, and the queries live with the transaction that will insert.
 *
 * <h2>An empty scope is allowed, and that is not the same decision as an empty run</h2>
 *
 * <p>A plan with no scope items is an ordinary intermediate state — someone names the plan and the
 * milestone, then adds folders to it. {@code caseCount} answers 0 and the plan page says so. A
 * <em>run</em> with nothing to execute is refused instead ({@link RunEdit}), because a run is a
 * commitment to test a specific set and an empty one renders as finished.
 *
 * <h2>The create and the edit are one validator with two entry points</h2>
 *
 * <p>{@link #validateCreate} and {@link #validateUpdate} share every rule about a name, a
 * description, a milestone and a scope, and differ in exactly two places — the create cannot carry
 * criteria (nobody knows what makes a plan releasable at the moment they name it), and the update
 * resolves an absent list against what is stored. Written as two copies, the length limits would
 * agree until the day one of them changed.
 */
record PlanEdit(
        String name,
        String description,
        String milestoneName,
        LocalDate milestoneDate,
        PlanScope scope,
        List<CriterionInput> entryCriteria,
        List<CriterionInput> exitCriteria) {

    /**
     * The longest plan name the lists will accept. A plan name is a release or a milestone label —
     * "Release 4.3 regression" — not a paragraph, and it is rendered in the run picker where a long
     * one crowds out the run's own name beside it.
     */
    static final int MAX_NAME = 200;

    /** Same ceiling as a project description, and for the same reason. */
    static final int MAX_DESCRIPTION = 2000;

    /** A milestone label — "GA", "Sprint 42", "Board demo". */
    static final int MAX_MILESTONE_NAME = 200;

    /**
     * The most scope items one create will accept, counting suites and cases together.
     *
     * <p>Not a schema limit — {@code plan_scope_item} has no cap. It is the point past which the
     * request stops being a person building a plan and starts being something that should be using
     * an import: every id here is validated by an {@code IN} list and then inserted as a row, and a
     * body carrying fifty thousand ids is a slow refusal that holds a transaction open. Generous
     * enough that no real plan hits it.
     */
    static final int MAX_SCOPE_ITEMS = 2000;

    /**
     * The most entry or exit criteria one plan may carry, per list.
     *
     * <p>A release gate somebody reads in a meeting, not a rule engine. Every one of these is
     * evaluated on each statistics request and rendered as a row on the plan page, so a plan with
     * hundreds is one nobody can act on — and the column is {@code JSONB} read whole on every plan
     * list. Generous against how many gates a real release has, which is a handful.
     */
    static final int MAX_CRITERIA = 50;

    /** A gate's own name — "Support sign-off", not its justification. */
    static final int MAX_CRITERION_LABEL = 200;

    /** Sealed so the caller's mapping is exhaustive, like {@code GateResult}. */
    sealed interface Outcome {

        /** Field name, as spelled on {@code TestPlanInput}, to its messages. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        record Valid(PlanEdit edit) implements Outcome {
        }
    }

    /**
     * Validates a create.
     *
     * <p>Both criteria lists come out {@code null} — <em>not</em> empty — which is what
     * {@link TestPlan#create} stores and the distinction it deliberately preserved: a plan nobody
     * has written gates for has not declared there are none. Only {@link #validateUpdate} can put a
     * value in either column.
     *
     * @param liveSuiteIds of the requested suite ids, those naming a live suite in this project
     * @param liveCaseIds  likewise for cases
     */
    static Outcome validateCreate(
            PlanWriteRequest request, Set<UUID> liveSuiteIds, Set<UUID> liveCaseIds) {

        Map<String, List<String>> errors = new LinkedHashMap<>();

        Fields fields = validateFields(
                request.name(), request.description(), request.milestoneName(), errors);
        PlanScope scope = validateScope(
                request.suiteIds(), request.caseIds(), null, liveSuiteIds, liveCaseIds, errors);

        return errors.isEmpty()
                ? new Outcome.Valid(new PlanEdit(
                        fields.name(), fields.description(), fields.milestoneName(),
                        request.milestoneDate(), scope, null, null))
                : new Outcome.Invalid(errors);
    }

    /**
     * Validates an edit of a plan's content.
     *
     * <p>The three list-shaped fields resolve an <strong>absent</strong> submission against what is
     * stored rather than clearing it — {@code repository.CaseEdit.normalizeClassification}'s rule,
     * and see {@link PlanUpdateRequest} for why a list gets a different answer from a scalar.
     *
     * @param stored the plan's current scope, so an omitted {@code suiteIds}/{@code caseIds} leaves
     *               it alone. Passed in rather than looked up here, keeping this a pure function of
     *               its inputs.
     */
    static Outcome validateUpdate(
            PlanUpdateRequest request,
            PlanScope stored,
            Set<UUID> liveSuiteIds,
            Set<UUID> liveCaseIds) {

        Map<String, List<String>> errors = new LinkedHashMap<>();

        Fields fields = validateFields(
                request.name(), request.description(), request.milestoneName(), errors);
        PlanScope scope = validateScope(
                request.suiteIds(), request.caseIds(), stored, liveSuiteIds, liveCaseIds, errors);
        List<CriterionInput> entry = validateCriteria(request.entryCriteria(), "entryCriteria", errors);
        List<CriterionInput> exit = validateCriteria(request.exitCriteria(), "exitCriteria", errors);

        return errors.isEmpty()
                ? new Outcome.Valid(new PlanEdit(
                        fields.name(), fields.description(), fields.milestoneName(),
                        request.milestoneDate(), scope, entry, exit))
                : new Outcome.Invalid(errors);
    }

    /** The three free-text fields, normalized — shared so the two entry points cannot diverge. */
    private record Fields(String name, String description, String milestoneName) {
    }

    private static Fields validateFields(
            String rawName,
            String rawDescription,
            String rawMilestoneName,
            Map<String, List<String>> errors) {

        String name = trimToNull(rawName);
        if (name == null) {
            error(errors, "name", "Enter a plan name.");
        } else if (name.length() > MAX_NAME) {
            error(errors, "name", "Keep the name under " + MAX_NAME + " characters.");
        }

        String description = trimToNull(rawDescription);
        if (description != null && description.length() > MAX_DESCRIPTION) {
            error(errors, "description",
                    "Keep the description under " + MAX_DESCRIPTION + " characters.");
        }

        String milestoneName = trimToNull(rawMilestoneName);
        if (milestoneName != null && milestoneName.length() > MAX_MILESTONE_NAME) {
            error(errors, "milestoneName",
                    "Keep the milestone name under " + MAX_MILESTONE_NAME + " characters.");
        }

        return new Fields(name, description, milestoneName);
    }

    /**
     * The scope, deduplicated, sorted and checked against what this project actually holds.
     *
     * @param stored what the plan's scope is now, or null on a create. An absent submission of
     *               <em>both</em> lists leaves it alone; submitting one and omitting the other
     *               resolves each half independently, so "add a folder" does not silently drop the
     *               individually-named cases the API has always accepted.
     */
    private static PlanScope validateScope(
            List<UUID> submittedSuiteIds,
            List<UUID> submittedCaseIds,
            PlanScope stored,
            Set<UUID> liveSuiteIds,
            Set<UUID> liveCaseIds,
            Map<String, List<String>> errors) {

        PlanScope fallback = stored == null ? PlanScope.empty() : stored;

        // Deduplicated before counting and before insertion. Two identical scope items are two rows
        // saying one thing, and the expansion already dedupes what it counts — so leaving them would
        // put rows in the table that no read can distinguish from a mistake.
        PlanScope scope = PlanScope.of(
                submittedSuiteIds == null ? fallback.suiteIds() : submittedSuiteIds,
                submittedCaseIds == null ? fallback.caseIds() : submittedCaseIds);

        if (scope.itemCount() > MAX_SCOPE_ITEMS) {
            error(errors, "suiteIds",
                    "Add at most " + MAX_SCOPE_ITEMS + " suites and cases to a plan's scope.");
            return scope;
        }

        for (UUID suiteId : scope.suiteIds()) {
            // Anything carried over from `stored` was live when it was stored and may not be now —
            // a folder deleted since is checked here like any other id, so an edit of an unrelated
            // field surfaces it rather than silently rewriting a scope the project no longer has.
            if (!liveSuiteIds.contains(suiteId)) {
                // The id is not echoed. It is either a folder in a project the caller may not
                // hold or one that has been deleted, and the actionable sentence is the same
                // either way — reload the picker, which is where the id came from.
                error(errors, "suiteIds",
                        "One of the selected suites is not in this project any more.");
                break;
            }
        }
        for (UUID caseId : scope.caseIds()) {
            if (!liveCaseIds.contains(caseId)) {
                error(errors, "caseIds",
                        "One of the selected cases is not in this project any more.");
                break;
            }
        }
        return scope;
    }

    /**
     * One criteria list, validated against the vocabulary {@link PlanCriteria} can evaluate.
     *
     * @return null when nothing was submitted, meaning "leave the column as it is" — which is how a
     *         plan whose criteria have never been touched keeps its {@code NULL} rather than
     *         acquiring an empty array that says somebody decided there were none
     */
    private static List<CriterionInput> validateCriteria(
            List<CriterionInput> submitted, String field, Map<String, List<String>> errors) {

        if (submitted == null) {
            return null;
        }
        if (submitted.size() > MAX_CRITERIA) {
            error(errors, field, "Add at most " + MAX_CRITERIA + " criteria.");
            return List.of();
        }

        List<CriterionInput> normalized = new ArrayList<>(submitted.size());
        for (CriterionInput criterion : submitted) {
            if (criterion == null) {
                continue;
            }
            String metric = trimToNull(criterion.metric());
            String op = trimToNull(criterion.op());
            String label = trimToNull(criterion.label());

            if (metric == null || !PlanCriteria.METRICS.contains(metric)) {
                // The valid set is named, unlike a permission refusal. PlanController draws the line
                // in the same place for its aggregation policies: a closed product vocabulary that
                // is already on every response is not a secret, and withholding it leaves the author
                // guessing at a list the server knows.
                error(errors, field, "'" + criterion.metric() + "' is not a metric a plan can be "
                        + "gated on. Valid metrics: "
                        + PlanCriteria.METRICS.stream().sorted().collect(Collectors.joining(", "))
                        + ".");
                continue;
            }
            if (!PlanCriteria.isKnownOperator(op)) {
                error(errors, field, "'" + criterion.op() + "' is not a comparison this gate can "
                        + "make. Valid operators: >=, >, <=, <, =, !=.");
                continue;
            }
            if (criterion.value() == null || !Double.isFinite(criterion.value())) {
                // Boxed and checked rather than defaulted: a criterion with no threshold would
                // become "pass_rate >= 0", which is met by every release that ever runs and reads on
                // the page exactly like a gate somebody meant.
                error(errors, field, "Give " + metric + " a number to compare against.");
                continue;
            }
            if (label != null && label.length() > MAX_CRITERION_LABEL) {
                error(errors, field,
                        "Keep a criterion's label under " + MAX_CRITERION_LABEL + " characters.");
                continue;
            }
            normalized.add(new CriterionInput(metric, op, criterion.value(), label));
        }
        return List.copyOf(normalized);
    }

    /** Null-tolerant, order-preserving dedupe — the arrays are optional on the contract. */
    static List<UUID> distinct(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Set<UUID> seen = new LinkedHashSet<>();
        for (UUID id : ids) {
            if (id != null) {
                seen.add(id);
            }
        }
        return List.copyOf(seen);
    }

    static void error(Map<String, List<String>> errors, String field, String message) {
        errors.computeIfAbsent(field, ignored -> new ArrayList<>()).add(message);
    }

    /** Blank is absent — {@code ProjectEdit} carries the argument. */
    static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
