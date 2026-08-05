package com.tcms.repository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A validated, normalized write to one suite — everything {@link Suite} needs and nothing it has to
 * check.
 *
 * <p>Same shape and the same reason as {@link CaseEdit}: the request is normalized in one place
 * before anything acts on it, so no service, entity or query re-derives whether the input was
 * acceptable. A rule enforced half here and half in the entity is a rule with two answers.
 *
 * <p>Two entry points rather than one {@code validate} with a nullable "current suite", because the
 * rules genuinely differ and the difference is the one an author gets wrong: on a create
 * {@code parentId} <em>chooses</em> the parent, and on a rename it may only repeat the stored one.
 * A single method taking a nullable current would make "no current" — the create case — silently
 * skip the check that a rename depends on.
 */
record SuiteEdit(
        String name,
        UUID parentId,
        String description,
        // Null for "leave them alone", which the rename dialog sends and the create path never
        // does. Both are normalized by #normalizeTagRules / #normalizeCaseIds before they get here.
        List<String> tagRules,
        List<UUID> caseIds) {

    /**
     * The longest name the tree will accept.
     *
     * <p>The column is unbounded {@code TEXT}, so the cap is a product rule rather than a schema
     * one: a folder label is rendered in a tree rail a few hundred pixels wide, and a name that
     * cannot be read in it is not a name. Shorter than a case title's 300 for that reason.
     */
    static final int MAX_NAME = 150;

    /**
     * The longest tag rule. {@link CaseEdit}'s cap for a tag, and it must stay the same number: a
     * rule longer than a tag can be is a rule that can never match anything, and it would be
     * accepted here and silently useless.
     */
    static final int MAX_TAG = 60;

    /** Sealed so the controller's mapping is exhaustive, like {@link com.tcms.project.GateResult}. */
    sealed interface Outcome {

        /** Field name, as spelled on {@code SuiteInput}, to its messages. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        record Valid(SuiteEdit edit) implements Outcome {
        }
    }

    /**
     * Validates a create.
     *
     * @param liveSuiteIds the project's live suite ids. A {@code parentId} outside this set is
     *                     refused rather than accepted: an unchecked parent puts the new folder
     *                     under another <em>project's</em> suite (or a deleted one), and since every
     *                     read is scoped by {@code project_id} the folder would then appear in no
     *                     tree at all — created successfully and immediately invisible.
     */
    static Outcome validateCreate(
            SuiteWriteRequest request, Set<UUID> liveSuiteIds, Set<UUID> liveCaseIds) {
        Map<String, List<String>> errors = new LinkedHashMap<>();

        String name = validName(request, errors);

        UUID parentId = request.parentId();
        if (parentId != null && !liveSuiteIds.contains(parentId)) {
            // Not 404: the project resolved fine and the caller submitted a value this project does
            // not offer, so the message belongs on the parent picker rather than on the page.
            errors.computeIfAbsent("parentId", key -> new ArrayList<>())
                    .add("That suite is not in this project.");
        }

        List<String> tagRules = normalizeTagRules(request.tagRules(), errors);
        List<UUID> caseIds = normalizeCaseIds(request.caseIds(), liveCaseIds, errors);

        return errors.isEmpty()
                ? new Outcome.Valid(new SuiteEdit(
                        name, parentId, trimToNull(request.description()), tagRules, caseIds))
                : new Outcome.Invalid(errors);
    }

    /**
     * Validates a rename.
     *
     * <h2>A differing {@code parentId} is refused, not ignored</h2>
     *
     * <p>Moving a suite rewrites every descendant's {@code path} and needs cycle prevention — see
     * {@link Suite#getPath()}, which calls that a slice of its own. Until it exists, the only two
     * honest answers to a payload asking for a move are "done" and "refused". Dropping the field
     * silently is the third one, and it is the one that lets a client show a folder in its new place,
     * reload, and find it back where it was — with the server having reported success.
     *
     * <p>Null is accepted as "unchanged" as well as matching a null parent, because the SPA's rename
     * dialog edits a name and has no parent to send. Only a <em>differing non-null</em> value is a
     * move.
     *
     * @param currentParentId the suite's stored parent, null for a root
     */
    static Outcome validateRename(
            SuiteWriteRequest request, UUID currentParentId, Set<UUID> liveCaseIds) {
        Map<String, List<String>> errors = new LinkedHashMap<>();

        String name = validName(request, errors);

        UUID parentId = request.parentId();
        if (parentId != null && !parentId.equals(currentParentId)) {
            errors.computeIfAbsent("parentId", key -> new ArrayList<>())
                    .add("Moving a suite is not supported yet. Leave the parent unchanged.");
        }

        List<String> tagRules = normalizeTagRules(request.tagRules(), errors);
        List<UUID> caseIds = normalizeCaseIds(request.caseIds(), liveCaseIds, errors);

        return errors.isEmpty()
                ? new Outcome.Valid(new SuiteEdit(
                        name, currentParentId, trimToNull(request.description()),
                        tagRules, caseIds))
                : new Outcome.Invalid(errors);
    }

    /**
     * The suite's tag rules: trimmed, blanks dropped, duplicates collapsed, order kept.
     *
     * <p>Null in, null out — "leave them alone", the one instruction a reconcile must be able to
     * tell from "clear them", and the one the rename dialog sends by not mentioning the field.
     *
     * <p><strong>A tag nothing carries is accepted.</strong> There is no vocabulary to check
     * against, and writing the rule before the cases are tagged is an ordinary way to work — a
     * refusal here would be a validator inventing a workflow order.
     */
    private static List<String> normalizeTagRules(
            List<String> submitted, Map<String, List<String>> errors) {

        if (submitted == null) {
            return null;
        }

        List<String> distinct = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : submitted) {
            String tag = trimToNull(raw);
            if (tag == null) {
                continue;
            }
            if (tag.length() > MAX_TAG) {
                errors.computeIfAbsent("tagRules", key -> new ArrayList<>())
                        .add("Keep each tag under " + MAX_TAG + " characters.");
                continue;
            }
            if (seen.add(tag)) {
                distinct.add(tag);
            }
        }
        return List.copyOf(distinct);
    }

    /**
     * The cases added to the suite individually, de-duplicated and bounded to this project.
     *
     * <p>The bound is the point, and it is {@code suiteId}'s argument on {@link CaseEdit} inverted:
     * a case id from a <em>sibling project</em> satisfies {@code suite_case}'s foreign key, the
     * company column and RLS, so without this check it would be stored — and every later read of
     * this suite would resolve a case through a project the reader was never granted.
     *
     * <p>A deleted case is refused the same way, since {@code liveCaseIds} carries only live ones:
     * adding a case that no read returns would put a permanent invisible member in a collection
     * whose count is on screen.
     */
    private static List<UUID> normalizeCaseIds(
            List<UUID> submitted, Set<UUID> liveCaseIds, Map<String, List<String>> errors) {

        if (submitted == null) {
            return null;
        }

        List<UUID> distinct = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (UUID caseId : submitted) {
            if (caseId == null) {
                continue;
            }
            if (!liveCaseIds.contains(caseId)) {
                errors.computeIfAbsent("caseIds", key -> new ArrayList<>())
                        .add("That case is not in this project.");
                continue;
            }
            if (seen.add(caseId)) {
                distinct.add(caseId);
            }
        }
        return List.copyOf(distinct);
    }

    /** The one name rule, so create and rename cannot disagree about what a name is. */
    private static String validName(SuiteWriteRequest request, Map<String, List<String>> errors) {
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
     * Blank is absent.
     *
     * <p>A description submitted as {@code "   "} stored as-is is a description that renders as an
     * empty paragraph and reads as data loss; null renders as nothing, which is what was meant.
     */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
