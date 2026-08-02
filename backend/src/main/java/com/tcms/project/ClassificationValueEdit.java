package com.tcms.project;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A validated, normalized write to one classification value — everything
 * {@link ClassificationValue} needs and nothing it has to check.
 *
 * <p>The shape and the reason are {@code repository.SuiteEdit}'s, one vocabulary over: the request
 * is normalized in one place before anything acts on it, so no service, entity or query re-derives
 * whether the input was acceptable. Two entry points rather than one method with a nullable
 * "current value", for the same reason that class gives — on a create {@code parentId}
 * <em>chooses</em> the parent, and on an edit it may only repeat the stored one, and a single
 * method taking a nullable current would silently skip the second check for the first case.
 *
 * <h2>The sibling list is the argument, and it is what makes three defects unreachable</h2>
 *
 * <p>Both methods take {@code siblings} — every <strong>live</strong> value of every dimension that
 * applies to this project, the same list {@code ProjectConfigurationService} serves. Three separate
 * refusals fall out of having it, and every one of them is a value that satisfies the foreign key
 * and stores cleanly without it:
 *
 * <ol>
 *   <li>a {@code parentId} naming a value of <em>another dimension</em> — a Feature parented under
 *       "Functional", which no tree can render and no reader can interpret;</li>
 *   <li>a {@code parentId} naming <em>another project's</em> Feature value — the third instance of
 *       the family {@code sharedStepId} (§64) and {@code classificationValueIds} (§68) each closed,
 *       where a stored reference resolves through a project the reader holds no grant on;</li>
 *   <li>a duplicate name among live siblings, which makes a picker with two identical checkboxes
 *       and a facet that splits one feature across two rows.</li>
 * </ol>
 *
 * <p>Only <em>live</em> siblings, deliberately, and that is the same bound §68's reconcile has: a
 * retired value is served to nobody, so blocking a new "Payments" because a retired one exists
 * would refuse a name with an explanation the client cannot verify and cannot clear. {@code V20}'s
 * partial index says the same thing in the schema.
 */
record ClassificationValueEdit(String name, UUID parentId, String color) {

    /**
     * The longest name the vocabulary will accept.
     *
     * <p>The column is unbounded {@code TEXT}, so this is a product rule: a classification value is
     * rendered as a chip on a case row and as a checkbox label in a picker, and a name that wraps
     * to three lines in either is not a label. Matches {@code SuiteEdit.MAX_NAME} because both are
     * the same kind of thing — a node in a tree read at a glance.
     */
    static final int MAX_NAME = 150;

    /** Six hex digits after a hash. The one spelling {@code CaseStatus} colours already use. */
    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");

    /** Sealed, so the write service's mapping over it is exhaustive — {@link GateResult}'s reason. */
    sealed interface Outcome {

        /** Field name, as spelled on {@code ClassificationValueInput}, to its messages. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        record Valid(ClassificationValueEdit edit) implements Outcome {
        }
    }

    /**
     * Validates a create.
     *
     * @param dimensionId the dimension the create resolved to — already checked to apply to this
     *                    project by the gate, which answered 404 if it did not
     * @param siblings    every live value of every dimension applicable to this project
     */
    static Outcome validateCreate(
            ClassificationValueInput request,
            UUID dimensionId,
            List<ClassificationValue> siblings) {

        Map<String, List<String>> errors = new LinkedHashMap<>();

        String name = validName(request, errors);
        UUID parentId = request.parentId();
        ClassificationValue parent = validParent(parentId, dimensionId, siblings, errors);
        String color = validColor(request, errors);

        // Only once the parent is known to be legal: a duplicate check against an unresolved
        // parent asks the question about the wrong set of siblings, and would refuse a legitimate
        // name while the real refusal is on the field above it.
        if (name != null && (parentId == null || parent != null)) {
            refuseDuplicate(name, dimensionId, parentId, null, siblings, errors);
        }

        return errors.isEmpty()
                ? new Outcome.Valid(new ClassificationValueEdit(name, parentId, color))
                : new Outcome.Invalid(errors);
    }

    /**
     * Validates an edit — a rename, a recolour, or both.
     *
     * <h2>A differing {@code parentId} or {@code dimensionId} is refused, not ignored</h2>
     *
     * <p>{@code SuiteEdit.validateRename} refuses a move because reparenting rewrites every
     * descendant's {@code path} and needs cycle prevention. That argument holds here and there is a
     * second one: the values below this one are what authored cases are <em>classified by</em>, so
     * moving a subtree silently re-states what every case beneath it was asserting. A move belongs
     * to a slice that can show its work.
     *
     * <p>A differing {@code dimensionId} is refused for a reason that is not about difficulty at
     * all: a value that changes axis is a different value. "Regression" moved from Execution Phase
     * to Test Type does not relabel anything — it makes every case carrying it claim something it
     * never claimed.
     *
     * <p>Null is accepted as "unchanged" on both, as well as matching a null parent, because a
     * rename dialog edits a name and has no tree position to send. Only a <em>differing non-null</em>
     * value is a move.
     */
    static Outcome validateEdit(
            ClassificationValueInput request,
            ClassificationValue current,
            List<ClassificationValue> siblings) {

        Map<String, List<String>> errors = new LinkedHashMap<>();

        String name = validName(request, errors);
        String color = validColor(request, errors);

        if (request.parentId() != null && !request.parentId().equals(current.getParentId())) {
            errors.computeIfAbsent("parentId", key -> new ArrayList<>())
                    .add("Moving a value is not supported yet. Leave the parent unchanged.");
        }
        if (request.dimensionId() != null && !request.dimensionId().equals(current.getDimensionId())) {
            errors.computeIfAbsent("dimensionId", key -> new ArrayList<>())
                    .add("A value cannot change dimension. Retire it and add a new one.");
        }

        if (name != null) {
            refuseDuplicate(
                    name, current.getDimensionId(), current.getParentId(),
                    current.getId(), siblings, errors);
        }

        return errors.isEmpty()
                ? new Outcome.Valid(
                        new ClassificationValueEdit(name, current.getParentId(), color))
                : new Outcome.Invalid(errors);
    }

    /** The one name rule, so create and edit cannot disagree about what a name is. */
    private static String validName(
            ClassificationValueInput request, Map<String, List<String>> errors) {

        String name = trimToNull(request.name());
        if (name == null) {
            errors.computeIfAbsent("name", key -> new ArrayList<>()).add("Enter a name.");
        } else if (name.length() > MAX_NAME) {
            errors.computeIfAbsent("name", key -> new ArrayList<>())
                    .add("Keep the name under " + MAX_NAME + " characters.");
        }
        return name;
    }

    /**
     * The parent, or null when there is none or it was refused.
     *
     * <p>A 400 rather than a 404, for {@code SuiteEdit}'s reason: the project resolved fine and the
     * caller submitted a value this project does not offer, so the message belongs on the picker
     * rather than on the page. Both failures — wrong dimension, and not in this project at all —
     * carry the <em>same</em> sentence, because telling them apart tells a caller that the id they
     * guessed names a real value in some other project.
     */
    private static ClassificationValue validParent(
            UUID parentId,
            UUID dimensionId,
            List<ClassificationValue> siblings,
            Map<String, List<String>> errors) {

        if (parentId == null) {
            return null;
        }
        for (ClassificationValue candidate : siblings) {
            if (candidate.getId().equals(parentId)
                    && candidate.getDimensionId().equals(dimensionId)) {
                return candidate;
            }
        }
        errors.computeIfAbsent("parentId", key -> new ArrayList<>())
                .add("That value is not in this dimension.");
        return null;
    }

    private static String validColor(
            ClassificationValueInput request, Map<String, List<String>> errors) {

        String color = trimToNull(request.color());
        if (color != null && !COLOR.matcher(color).matches()) {
            errors.computeIfAbsent("color", key -> new ArrayList<>())
                    .add("Use a six-digit hex colour, like #b7791f.");
        }
        return color;
    }

    /**
     * Refuses a name another live sibling already uses.
     *
     * <p>Case-insensitive and locale-independent — {@code Locale.ROOT}, because
     * {@code "I".toLowerCase()} in a Turkish locale is not {@code "i"} and a validator whose verdict
     * depends on the server's locale is one that disagrees with {@code V20}'s {@code lower(name)}
     * index on exactly the rows nobody tests with. The index is what catches the race this check
     * cannot; they have to agree on what a duplicate is, or the race handler reports a field error
     * for a name the field check accepted.
     *
     * @param exclude the value being edited, so renaming it to its own current name is not a
     *                duplicate of itself — the defect every edit-side uniqueness check has by
     *                default, and it refuses a recolour that leaves the name alone
     */
    private static void refuseDuplicate(
            String name,
            UUID dimensionId,
            UUID parentId,
            UUID exclude,
            List<ClassificationValue> siblings,
            Map<String, List<String>> errors) {

        String folded = name.toLowerCase(Locale.ROOT);
        for (ClassificationValue candidate : siblings) {
            if (candidate.getId().equals(exclude)
                    || !candidate.getDimensionId().equals(dimensionId)
                    || !Objects.equals(candidate.getParentId(), parentId)) {
                continue;
            }
            if (candidate.getName().toLowerCase(Locale.ROOT).equals(folded)) {
                errors.computeIfAbsent("name", key -> new ArrayList<>())
                        .add("Another value here already has that name.");
                return;
            }
        }
    }

    /** Blank is absent — {@code SuiteEdit.trimToNull}'s reason. */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
