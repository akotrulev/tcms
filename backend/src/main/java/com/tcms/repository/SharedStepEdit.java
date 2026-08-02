package com.tcms.repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A validated, normalized write to one library entry — everything {@link SharedStep} needs and
 * nothing it has to check.
 *
 * <p>Same shape and the same reason as {@link SuiteEdit} and {@link CaseEdit}: the request is
 * normalized once, before anything acts on it, so no service, entity or query re-derives whether
 * the input was acceptable.
 *
 * @param body never empty, and every element non-blank — see {@link #validateCreate}
 */
record SharedStepEdit(String title, List<String> body) {

    /**
     * The longest title the picker will accept.
     *
     * <p>The column is unbounded {@code TEXT}, so this is a product rule like {@link
     * SuiteEdit#MAX_NAME}: the title is rendered as one line of a typeahead result and as the
     * action text of every step referencing it, so a title that does not fit either is not a
     * title. Matched to the suite name's cap rather than the case title's 300, because both are
     * labels in a narrow list rather than a sentence on a page.
     */
    static final int MAX_TITLE = 150;

    /** Sealed so the caller's mapping is exhaustive, like {@link com.tcms.project.GateResult}. */
    sealed interface Outcome {

        /** Field name, as spelled on {@code SharedStepInput}, to its messages. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        record Valid(SharedStepEdit edit) implements Outcome {
        }
    }

    /**
     * Validates a create.
     *
     * <h2>Blank body lines are dropped, and an all-blank body is refused</h2>
     *
     * <p>The two go together. The editor this is written for appends an empty row when the author
     * clicks "Add action", so a submitted body routinely carries a trailing blank the author never
     * meant to send — storing it puts an empty line in every case that references the entry, in a
     * list whose positions are the whole structure. Dropping them silently is right for a trailing
     * row and wrong for a body that was <em>only</em> blank rows, which is a form the author has
     * not filled in: dropping those would store an empty block, and an empty library entry is a
     * picker row that inserts nothing and cannot be told apart from one that failed to load.
     *
     * <p>So: filter, then refuse the empty result, and name {@code body} in the error so the
     * message lands on the list rather than on the page.
     */
    static Outcome validateCreate(SharedStepWriteRequest request) {
        Map<String, List<String>> errors = new LinkedHashMap<>();

        String title = trimToNull(request.title());
        if (title == null) {
            errors.computeIfAbsent("title", key -> new ArrayList<>()).add("Enter a title.");
        } else if (title.length() > MAX_TITLE) {
            errors.computeIfAbsent("title", key -> new ArrayList<>())
                    .add("Keep the title under " + MAX_TITLE + " characters.");
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
                ? new Outcome.Valid(new SharedStepEdit(title, List.copyOf(body)))
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
