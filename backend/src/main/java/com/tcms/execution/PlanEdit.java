package com.tcms.execution;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
 */
record PlanEdit(
        String name,
        String description,
        String milestoneName,
        LocalDate milestoneDate,
        List<UUID> suiteIds,
        List<UUID> caseIds) {

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
     * @param liveSuiteIds of the requested suite ids, those naming a live suite in this project
     * @param liveCaseIds  likewise for cases
     */
    static Outcome validateCreate(
            PlanWriteRequest request, Set<UUID> liveSuiteIds, Set<UUID> liveCaseIds) {

        Map<String, List<String>> errors = new LinkedHashMap<>();

        String name = trimToNull(request.name());
        if (name == null) {
            error(errors, "name", "Enter a plan name.");
        } else if (name.length() > MAX_NAME) {
            error(errors, "name", "Keep the name under " + MAX_NAME + " characters.");
        }

        String description = trimToNull(request.description());
        if (description != null && description.length() > MAX_DESCRIPTION) {
            error(errors, "description",
                    "Keep the description under " + MAX_DESCRIPTION + " characters.");
        }

        String milestoneName = trimToNull(request.milestoneName());
        if (milestoneName != null && milestoneName.length() > MAX_MILESTONE_NAME) {
            error(errors, "milestoneName",
                    "Keep the milestone name under " + MAX_MILESTONE_NAME + " characters.");
        }

        // Deduplicated before counting and before insertion. Two identical scope items are two rows
        // saying one thing, and the expansion already dedupes what it counts — so leaving them would
        // put rows in the table that no read can distinguish from a mistake.
        List<UUID> suiteIds = distinct(request.suiteIds());
        List<UUID> caseIds = distinct(request.caseIds());

        if (suiteIds.size() + caseIds.size() > MAX_SCOPE_ITEMS) {
            error(errors, "suiteIds",
                    "Add at most " + MAX_SCOPE_ITEMS + " suites and cases to a plan's scope.");
        } else {
            for (UUID suiteId : suiteIds) {
                if (!liveSuiteIds.contains(suiteId)) {
                    // The id is not echoed. It is either a folder in a project the caller may not
                    // hold or one that has been deleted, and the actionable sentence is the same
                    // either way — reload the picker, which is where the id came from.
                    error(errors, "suiteIds",
                            "One of the selected suites is not in this project any more.");
                    break;
                }
            }
            for (UUID caseId : caseIds) {
                if (!liveCaseIds.contains(caseId)) {
                    error(errors, "caseIds",
                            "One of the selected cases is not in this project any more.");
                    break;
                }
            }
        }

        return errors.isEmpty()
                ? new Outcome.Valid(new PlanEdit(
                        name, description, milestoneName, request.milestoneDate(),
                        suiteIds, caseIds))
                : new Outcome.Invalid(errors);
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
