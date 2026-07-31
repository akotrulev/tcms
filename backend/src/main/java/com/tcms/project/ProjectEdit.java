package com.tcms.project;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A validated, normalized create of one project — everything {@link Project} needs and nothing it
 * has to check.
 *
 * <p>Same shape and the same reason as {@code SuiteEdit} in the {@code repository} module: the
 * request is normalized in one place before anything acts on it, so no service, entity or query
 * re-derives whether the input was acceptable. A rule enforced half here and half in the entity is
 * a rule with two answers.
 *
 * <h2>The key is the client's, and that is a decision rather than an omission</h2>
 *
 * <p>Every other identifier a user sees in this product is allocated: {@code TC-1}, {@code TS-2},
 * {@code DOC-7}, each from the company's {@code key_sequence} by the one non-racing statement
 * (handoff §29). A project key is not one of those. It labels a <em>whole client product</em>, a
 * company holds a handful of them, and the label appears in URLs and in conversation — V1's own
 * column comment says it is "suggested from the name at creation", which only makes sense for a
 * value a human then edits. Allocating {@code P-3} would replace the one identifier in the system
 * that is meant to be memorable, and it would do so to solve a contention problem that does not
 * exist at a handful of rows per company.
 *
 * <p>The cost of a client-supplied key is that it can collide, and that is checked here rather than
 * left to {@code uq_project_key}: a constraint violation is a 500 with a message naming an index.
 * The check is a lookup and the constraint is still the guarantee — see
 * {@link ProjectCreateService} for the race between them and what answers it.
 */
record ProjectEdit(String key, String name, String description) {

    /**
     * {@code ck_project_key_format} in V1, and {@code Project.key}'s {@code pattern} in the
     * contract. Restated here for the reason {@code ProvisionCompanyRequest} restates it for the
     * CLI: a CHECK violation surfaces as a driver exception naming a constraint, and the person
     * looking at the form deserves to be told which field is wrong.
     */
    private static final Pattern KEY = Pattern.compile("^[A-Z0-9]{2,10}$");

    /**
     * The longest project name the pickers will accept.
     *
     * <p>The column is unbounded {@code TEXT}, so this is a product rule: the name is rendered in
     * the project card, the rail and the page header, and a name that cannot be read in any of them
     * is not a name. Longer than a suite's 150 because a project name is a full product name
     * ("Acme Online Banking — Retail and Wealth"), not a folder label.
     */
    static final int MAX_NAME = 200;

    /** Long enough for a paragraph on the project card, short enough not to be a document. */
    static final int MAX_DESCRIPTION = 2000;

    /** Sealed so the caller's mapping is exhaustive, like {@link GateResult}. */
    sealed interface Outcome {

        /** Field name, as spelled on {@code ProjectInput}, to its messages. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        record Valid(ProjectEdit edit) implements Outcome {
        }
    }

    /**
     * Validates a create.
     *
     * <p>The key is <strong>not</strong> upper-cased on the way in. Accepting {@code "olb"} and
     * storing {@code "OLB"} is a save that silently returns something other than what was typed,
     * and the caller's very next screen is the one that shows the key back to them — so a lower-case
     * key is a field error naming the rule. The SPA is free to upper-case as the user types, where
     * they can see it happen.
     *
     * @param keyAlreadyUsed whether this company already has a project with this key. Passed in
     *                       rather than looked up here, so this type stays a pure function of its
     *                       inputs and the one query lives with the transaction that will insert.
     */
    static Outcome validateCreate(ProjectWriteRequest request, boolean keyAlreadyUsed) {
        Map<String, List<String>> errors = new LinkedHashMap<>();

        String key = trimToNull(request.key());
        if (key == null) {
            error(errors, "key", "Enter a project key.");
        } else if (!KEY.matcher(key).matches()) {
            error(errors, "key", "Use 2–10 upper-case letters or digits, like OLB.");
        } else if (keyAlreadyUsed) {
            // A 400 on the field rather than a 409 on the request: the caller's next action is to
            // type a different key, and a form has somewhere to put this sentence. It also says
            // nothing about a project the caller may not see — the check is company-scoped, and a
            // caller who can reach this endpoint can see every project in their company anyway.
            error(errors, "key", "Another project in this company already uses that key.");
        }

        String name = trimToNull(request.name());
        if (name == null) {
            error(errors, "name", "Enter a project name.");
        } else if (name.length() > MAX_NAME) {
            error(errors, "name", "Keep the name under " + MAX_NAME + " characters.");
        }

        String description = trimToNull(request.description());
        if (description != null && description.length() > MAX_DESCRIPTION) {
            error(errors, "description", "Keep the description under " + MAX_DESCRIPTION + " characters.");
        }

        return errors.isEmpty()
                ? new Outcome.Valid(new ProjectEdit(key, name, description))
                : new Outcome.Invalid(errors);
    }

    private static void error(Map<String, List<String>> errors, String field, String message) {
        errors.computeIfAbsent(field, ignored -> new ArrayList<>()).add(message);
    }

    /**
     * Blank is absent.
     *
     * <p>A description of {@code "   "} stored as-is renders as an empty paragraph that the card
     * still reserves space for, and reads as a project someone described with whitespace. Null is
     * what "not described" means in the column and in the contract.
     */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
