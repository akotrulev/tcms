package com.tcms.repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A validated, normalized write to one library condition — everything {@link Condition} needs and
 * nothing it has to check.
 *
 * <p>Same shape and the same reason as {@link SharedStepEdit}, {@link SuiteEdit} and
 * {@link CaseEdit}: the request is normalized once, before anything acts on it, so no service,
 * entity or query re-derives whether the input was acceptable.
 *
 * <h2>One validator for create and edit, and that is a decision</h2>
 *
 * <p>{@link SharedStepEdit} spells its entry point {@code validateCreate}, leaving room for an edit
 * with different rules. There is no such room here and pretending otherwise would be the more
 * dangerous shape: an edit propagates to every case attaching the entry, so a rule enforced on
 * create and relaxed on edit would let an entry reach — through the back door and across hundreds
 * of cases — a state the create refused. The two paths validate identically by construction.
 *
 * @param body never empty, and every element non-blank — see {@link #validate}
 */
record ConditionEdit(String title, ConditionType type, List<String> body) {

    /**
     * The longest title the picker will accept.
     *
     * <p>The column is unbounded {@code TEXT}, so this is a product rule, matched to
     * {@link SharedStepEdit#MAX_TITLE} because both are one line of a narrow typeahead result
     * rather than a sentence on a page. Stated as its own constant rather than borrowed, so that
     * changing one library's cap does not silently move the other's.
     */
    static final int MAX_TITLE = 150;

    /** Sealed so the caller's mapping is exhaustive, like {@link com.tcms.project.GateResult}. */
    sealed interface Outcome {

        /** Field name, as spelled on {@code ConditionInput}, to its messages. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        record Valid(ConditionEdit edit) implements Outcome {
        }
    }

    /**
     * Validates a create or an edit.
     *
     * <h2>Blank body lines are dropped, and an all-blank body is refused</h2>
     *
     * <p>{@link SharedStepEdit#validateCreate}'s rule, and it transfers exactly: the editor appends
     * an empty row as you type, so a submitted body routinely carries a trailing blank the author
     * never meant to send. Storing it puts an empty line into every case attaching the entry, in a
     * list whose positions are the whole structure. Dropping them silently is right for a trailing
     * row and wrong for a body that was <em>only</em> blank rows, which is a form nobody filled in.
     *
     * <p>The stakes are higher here than for a shared step, which is worth saying rather than
     * leaving to the reader: a shared step's empty body is one bad picker row, while a condition's
     * is an empty precondition block on every case that attaches it.
     *
     * <h2>{@code type} is required and has no default</h2>
     *
     * <p>Defaulting it to {@code BOTH} would be the accommodating choice and the wrong one: an
     * entry typed {@code BOTH} appears in <em>both</em> pickers, so a caller who forgot the field
     * gets an entry offered as a postcondition it was never written to be. A missing value is a
     * field error naming {@code type}. An <em>unparseable</em> one never reaches here — Jackson
     * refuses it at binding, which is the same treatment the case list's {@code type} filter gets
     * and for the reason {@link ConditionController} states: a dropped enum filter offers
     * postconditions in a precondition picker, and every one of them looks plausible.
     */
    static Outcome validate(ConditionWriteRequest request) {
        Map<String, List<String>> errors = new LinkedHashMap<>();

        String title = trimToNull(request.title());
        if (title == null) {
            errors.computeIfAbsent("title", key -> new ArrayList<>()).add("Enter a title.");
        } else if (title.length() > MAX_TITLE) {
            errors.computeIfAbsent("title", key -> new ArrayList<>())
                    .add("Keep the title under " + MAX_TITLE + " characters.");
        }

        if (request.type() == null) {
            errors.computeIfAbsent("type", key -> new ArrayList<>())
                    .add("Choose whether this is a precondition, a postcondition, or both.");
        }

        List<String> body = new ArrayList<>();
        if (request.body() != null) {
            for (String line : request.body()) {
                String action = trimToNull(line);
                if (action != null) {
                    body.add(action);
                }
            }
        }
        if (body.isEmpty()) {
            errors.computeIfAbsent("body", key -> new ArrayList<>()).add("Add at least one action.");
        }

        return errors.isEmpty()
                ? new Outcome.Valid(new ConditionEdit(title, request.type(), List.copyOf(body)))
                : new Outcome.Invalid(errors);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
