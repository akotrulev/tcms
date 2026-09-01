package com.tcms.tracker;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Validation, and <strong>the depth rule</strong> — the one thing that makes Epic → Story → Task
 * mean anything.
 *
 * <p>Pure: no Spring, no {@code EntityManager}, no clock. The caller resolves the two type rows and
 * hands them in, so every branch below is reachable from a unit test with no container — which is
 * what makes the eight cases in {@code IssueEditTest} cheap enough to have written first.
 *
 * <h2>The rule is about levels, and that is not a stylistic preference</h2>
 *
 * <p>Written against type <em>names</em> — "Task parents to Story" — this would have to be edited in
 * code the day a fourth story-level type is wanted. Written against {@link IssueTypeRow#level()},
 * <strong>a new type is a row</strong>: adding {@code Chore} or {@code Tech Debt} at {@code STORY}
 * needs no migration and no change here. Same reasoning {@link IssueStatus} follows by categorising
 * on {@code kind} rather than on a status's name (D2).
 *
 * @param acceptanceCriteria never null — an issue with none carries an empty list, which stores as
 *     {@code []} rather than as SQL {@code NULL}, because the column is {@code NOT NULL} and a
 *     nullable checklist would give "no criteria" two spellings
 */
record IssueEdit(
        String title,
        String description,
        List<AcceptanceCriterion> acceptanceCriteria,
        Priority priority,
        Double estimateHours,
        LocalDate startDate,
        LocalDate targetDate,
        LocalDate dueDate,
        UUID assigneeId,
        UUID releaseId) {

    /** {@code issue.title}'s practical ceiling, and the contract's {@code maxLength}. */
    private static final int MAX_TITLE = 500;

    /** What happened. Sealed, so the service's mapping over it is exhaustive at compile time. */
    sealed interface Outcome {

        /** Field name to messages, for the form to land on its inputs. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        record Valid(IssueEdit edit) implements Outcome {
        }
    }

    /**
     * @param type       the row {@code request.typeId()} resolved to, or null if it resolved to
     *                   nothing — which is a validation failure rather than a 404, because the id
     *                   came from a form the client chose it in
     * @param parentType the row {@code request.parentKey()}'s issue resolved to, or null when the
     *                   key names nothing <em>or</em> when no key was sent; the two are told apart
     *                   by {@code parentKey} itself, never by this being null
     */
    static Outcome validateCreate(IssueInput request, IssueTypeRow type, IssueTypeRow parentType) {
        Map<String, List<String>> errors = new LinkedHashMap<>();

        if (request.projectId() == null) {
            add(errors, "projectId", "Choose a project.");
        }
        if (type == null) {
            add(errors, "typeId", "Choose an issue type from this project's own set.");
        }
        String title = checkTitle(errors, request.title());
        checkEstimate(errors, request.estimateHours());

        if (type != null) {
            checkParent(errors, type, request.parentKey(), parentType);
            checkDates(errors, type, request.startDate(), request.targetDate());
        }

        if (!errors.isEmpty()) {
            return new Outcome.Invalid(errors);
        }
        return new Outcome.Valid(new IssueEdit(
                title,
                blankToNull(request.description()),
                AcceptanceCriteria.fromText(request.acceptanceCriteria()),
                request.priority() == null ? Priority.MEDIUM : request.priority(),
                request.estimateHours(),
                request.startDate(),
                request.targetDate(),
                request.dueDate(),
                request.assigneeId(),
                // Existence is a lookup and this class is pure, so IssueService checks that the
                // release belongs to this project. Carried through untouched.
                request.releaseId()));
    }

    /**
     * The PATCH, over the issue's current values.
     *
     * <p>{@code current} is what an absent field means. It is the caller's job to have built it from
     * the stored row and the {@link com.tcms.api.PatchBody}, which is where absent and explicitly
     * null are told apart — this class sees only the merged result, so it can stay pure.
     */
    static Outcome validateUpdate(IssueEdit current, IssueTypeRow type, String parentKey,
            IssueTypeRow parentType) {

        Map<String, List<String>> errors = new LinkedHashMap<>();
        String title = checkTitle(errors, current.title());
        checkEstimate(errors, current.estimateHours());
        checkParent(errors, type, parentKey, parentType);
        checkDates(errors, type, current.startDate(), current.targetDate());

        if (!errors.isEmpty()) {
            return new Outcome.Invalid(errors);
        }
        return new Outcome.Valid(new IssueEdit(
                title,
                blankToNull(current.description()),
                current.acceptanceCriteria() == null ? List.of() : current.acceptanceCriteria(),
                current.priority() == null ? Priority.MEDIUM : current.priority(),
                current.estimateHours(),
                current.startDate(),
                current.targetDate(),
                current.dueDate(),
                current.assigneeId(),
                current.releaseId()));
    }

    /**
     * The depth rule, in one place, expressed entirely in {@link IssueLevel#depth()}.
     *
     * <p>Four answers, and the third is the one that is easy to get wrong: a story-level type with
     * no parent is <em>valid</em>. Requiring an epic would force a junk epic per stray bug.
     */
    private static void checkParent(
            Map<String, List<String>> errors,
            IssueTypeRow type,
            String parentKey,
            IssueTypeRow parentType) {

        int depth = type.level().depth();

        if (depth == IssueLevel.EPIC.depth() && parentKey != null) {
            add(errors, "parentKey", "An Epic is top level and cannot sit under anything.");
            return;
        }
        if (depth == IssueLevel.TASK.depth() && parentKey == null) {
            add(errors, "parentKey", "A " + type.name() + " needs a story-level parent — it is a "
                    + "breakdown of something. For work that stands on its own, use the "
                    + "story-level Task type instead.");
            return;
        }
        if (parentKey == null) {
            // A story-level type without an epic is a normal story (scope §4).
            return;
        }
        if (parentType == null) {
            add(errors, "parentKey", "No issue in this project has the key " + parentKey + ".");
            return;
        }
        if (parentType.level().depth() != depth - 1) {
            add(errors, "parentKey", "A " + type.name() + " may only sit under a type one level "
                    + "above it, and " + parentKey + " is a " + parentType.name() + ".");
        }
    }

    /**
     * The two epic-only dates.
     *
     * <p>Refused rather than ignored on a story: a value the column accepts and no screen renders is
     * one somebody will eventually report as lost data, and there is no way to tell them it was
     * never stored.
     */
    private static void checkDates(
            Map<String, List<String>> errors, IssueTypeRow type, LocalDate start, LocalDate target) {

        if (type.level() != IssueLevel.EPIC) {
            if (start != null) {
                add(errors, "startDate", "Only an Epic has a start date — a "
                        + type.name() + " has a due date.");
            }
            if (target != null) {
                add(errors, "targetDate", "Only an Epic has a target date — a "
                        + type.name() + " has a due date.");
            }
            return;
        }
        if (start != null && target != null && target.isBefore(start)) {
            add(errors, "targetDate", "The target date is before the start date.");
        }
    }

    private static String checkTitle(Map<String, List<String>> errors, String submitted) {
        String title = submitted == null ? "" : submitted.strip();
        if (title.isEmpty()) {
            add(errors, "title", "Give the issue a title.");
        } else if (title.length() > MAX_TITLE) {
            add(errors, "title", "A title is at most " + MAX_TITLE + " characters; this one is "
                    + title.length() + ".");
        }
        return title;
    }

    /**
     * Zero is accepted and negative is not.
     *
     * <p>"I looked, it is nothing" is a real answer, and refusing it would push people to leave the
     * field empty instead — which D8 reads as <em>no estimate</em> and which silently flips the
     * whole epic's basis from {@code ESTIMATE} to {@code COUNT}.
     */
    private static void checkEstimate(Map<String, List<String>> errors, Double hours) {
        if (hours != null && hours < 0) {
            add(errors, "estimateHours", "An estimate is in hours and cannot be negative.");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static void add(Map<String, List<String>> errors, String field, String message) {
        errors.computeIfAbsent(field, ignored -> new ArrayList<>()).add(message);
    }
}
