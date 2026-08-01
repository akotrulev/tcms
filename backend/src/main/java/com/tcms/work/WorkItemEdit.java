package com.tcms.work;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A validated, normalized create of one task.
 *
 * <p>The shape {@code project.ProjectEdit}, {@code repository.SuiteEdit} and
 * {@code execution.PlanEdit} established: the request is normalized once, before anything acts on
 * it, so no service, entity or query re-derives whether the input was acceptable. A rule enforced
 * half here and half in the entity is a rule with two answers, and the one that wins is whichever
 * ran second.
 *
 * <h2>The assignee is validated against the project's members, not merely against {@code app_user}</h2>
 *
 * <p>{@code work_item.assignee_id} is a foreign key, so the database refuses an id naming nobody and
 * RLS refuses another company's user. Neither refuses a <strong>colleague in this company who holds
 * no grant on this project</strong> — and that id is trivially obtainable, because it appears in
 * every assignee picker the caller has open in another tab.
 *
 * <p>The result would be a card assigned to somebody who cannot open the project it is in: it shows
 * on their personal board, they click it, and they get a 404. Worse, it is invisible to the person
 * who assigned it, because their board renders the assignee's display name perfectly well. So the
 * candidate set is computed by the caller and an id outside it is a field error.
 */
record WorkItemEdit(
        String title,
        String description,
        Priority priority,
        LocalDate dueDate,
        UUID assigneeId) {

    /**
     * The longest task title the board will accept.
     *
     * <p>Matches the contract's {@code maxLength: 300}. A card is a fixed-width box in a
     * four-column grid — a title past this does not truncate gracefully, it pushes the priority and
     * the due date out of the first screenful of the column.
     */
    static final int MAX_TITLE = 300;

    /** Same ceiling as a project description, and for the same reason. */
    static final int MAX_DESCRIPTION = 2000;

    /** Sealed so the caller's mapping is exhaustive, like {@code GateResult}. */
    sealed interface Outcome {

        /** Field name, as spelled on {@code WorkItemInput}, to its messages. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        record Valid(WorkItemEdit edit) implements Outcome {
        }
    }

    /**
     * Validates a create.
     *
     * @param assignableUserIds the users who may be assigned — members of this company holding a
     *                          grant on this project. Passed in rather than looked up here, for
     *                          {@code PlanEdit}'s reason: this type stays a pure function of its
     *                          inputs, and the query lives with the transaction that will insert.
     */
    static Outcome validateCreate(WorkItemWriteRequest request, Set<UUID> assignableUserIds) {
        Map<String, List<String>> errors = new LinkedHashMap<>();

        String title = trimToNull(request.title());
        if (title == null) {
            error(errors, "title", "Enter a title.");
        } else if (title.length() > MAX_TITLE) {
            error(errors, "title", "Keep the title under " + MAX_TITLE + " characters.");
        }

        String description = trimToNull(request.description());
        if (description != null && description.length() > MAX_DESCRIPTION) {
            error(errors, "description",
                    "Keep the description under " + MAX_DESCRIPTION + " characters.");
        }

        UUID assigneeId = request.assigneeId();
        if (assigneeId != null && !assignableUserIds.contains(assigneeId)) {
            // The id is not echoed, for PlanEdit's reason: it is either somebody outside the
            // project or somebody removed from it, and the actionable sentence is the same either
            // way — reopen the picker the id came from.
            error(errors, "assigneeId", "That person is not on this project any more.");
        }

        // A due date in the past is ACCEPTED, deliberately. It is how you record something that is
        // already late, and the board has an `overdue` flag whose entire job is displaying exactly
        // that. Refusing it would make the most common real reason for typing a task — it should
        // have been done last week — the one thing the form will not let you say.

        if (!errors.isEmpty()) {
            return new Outcome.Invalid(errors);
        }

        // MEDIUM rather than a required field: priority is the one property people leave alone, and
        // a required select on a two-field form is friction buying nothing. The contract marks it
        // optional and the column defaults to the same value.
        Priority priority = request.priority() == null ? Priority.MEDIUM : request.priority();

        return new Outcome.Valid(
                new WorkItemEdit(title, description, priority, request.dueDate(), assigneeId));
    }

    private static void error(Map<String, List<String>> errors, String field, String message) {
        errors.computeIfAbsent(field, key -> new ArrayList<>()).add(message);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
