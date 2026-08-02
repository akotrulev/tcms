package com.tcms.project;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A validated, normalized remark — everything {@link ReviewComment} needs and nothing it has to
 * check.
 *
 * <p>{@code ClassificationValueEdit}'s shape and reason: the request is normalized in one place, so
 * no service, entity or query re-derives whether the input was acceptable.
 *
 * <h2>The parent is the argument, and it carries the two refusals</h2>
 *
 * <p>{@link #validate} takes the parent <em>row</em> rather than the id, already resolved through
 * {@link ReviewCommentRepository#findInReview}, which is what makes both of these unreachable:
 *
 * <ol>
 *   <li>a {@code parentId} naming a comment on <em>another review</em> — the same family as §64's
 *       {@code sharedStepId} and §68's classification value, and here the consequence is a remark
 *       threaded onto a conversation in a project the author holds no grant on;</li>
 *   <li>a {@code parentId} naming a <em>reply</em>. Threads are one level deep: a tree has no
 *       bottom to render and no natural place to resolve, and §6g asks for threads.</li>
 * </ol>
 *
 * <p>Both are field errors on {@code parentId} rather than 404s, for {@code SuiteEdit}'s reason —
 * the review resolved fine and the client named a comment inside it that will not do. They carry
 * the <strong>same</strong> sentence, deliberately: telling them apart tells a caller that the id
 * they guessed names a real comment somewhere they cannot see.
 *
 * <h2>Retracting is not built, and this is where it would go</h2>
 *
 * <p>There is no delete and no edit of {@link ReviewComment#getBody()}. §6g does not ask for one,
 * and the record exists to show an auditor who said what — an entry that can be rewritten after
 * somebody answered it proves nothing. What a retraction would need is a tombstone (the row stays,
 * the body is replaced by "withdrawn by its author", the replies survive), not a delete, because
 * removing a root would orphan every reply under it. Named here so the slice that wants it does not
 * start from the cheap version.
 */
record ReviewCommentEdit(String body, UUID parentId) {

    /**
     * The longest remark the panel will accept.
     *
     * <p>The column is unbounded {@code TEXT}, so this is a product rule. Generous compared with
     * {@code CaseEdit.MAX_TITLE} (300) because this is prose and not a label — a reviewer
     * explaining what is wrong with a twelve-step case needs room — and bounded at all because an
     * unbounded body is a request-size question rather than a review question, and the panel
     * renders every comment of every review inline.
     */
    static final int MAX_BODY = 5_000;

    /** Sealed, so the service's mapping over it is exhaustive — {@link GateResult}'s reason. */
    sealed interface Outcome {

        /** Field name, as spelled on {@link ReviewCommentRequest}, to its messages. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        record Valid(ReviewCommentEdit edit) implements Outcome {
        }
    }

    /**
     * @param parent the row {@code request.parentId()} resolved to <em>within this review</em>, or
     *               null when none was asked for or none resolved. The two are told apart by
     *               reading the request, which is why this takes both
     */
    static Outcome validate(ReviewCommentRequest request, ReviewComment parent) {
        Map<String, List<String>> errors = new LinkedHashMap<>();

        String body = trimToNull(request == null ? null : request.body());
        if (body == null) {
            errors.computeIfAbsent("body", key -> new ArrayList<>()).add("Write a comment.");
        } else if (body.length() > MAX_BODY) {
            errors.computeIfAbsent("body", key -> new ArrayList<>())
                    .add("Keep the comment under " + MAX_BODY + " characters.");
        }

        UUID parentId = request == null ? null : request.parentId();
        if (parentId != null && (parent == null || !parent.isRoot())) {
            errors.computeIfAbsent("parentId", key -> new ArrayList<>())
                    .add("That comment cannot be replied to.");
            parentId = null;
        }

        return errors.isEmpty()
                ? new Outcome.Valid(new ReviewCommentEdit(body, parentId))
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
