package com.tcms.project;

import com.tcms.api.TeamRef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A validated request to review — everything {@link ReviewAssignment} needs and nothing it has to
 * check.
 *
 * <p>{@link ReviewCommentEdit}'s shape and reason: the request is normalized in one place, so no
 * service, entity or query re-derives whether the input was acceptable. Like that class, the
 * <em>resolved</em> things are the arguments rather than the ids, which is what makes the cross-
 * tenant refusals unreachable instead of merely tested for.
 *
 * <h2>The four refusals</h2>
 *
 * <ol>
 *   <li><strong>Not exactly one of the two.</strong> §6b's "a user <em>or</em> a team", and
 *       {@code V25}'s {@code num_nonnulls(...) = 1} behind it. Both spellings — naming nobody and
 *       naming two — land on {@code userId} with one sentence, because the field a client fixes is
 *       the same in both cases and a form cannot highlight the absence of a choice.</li>
 *   <li><strong>A person who cannot see the project.</strong> Not company membership, which is the
 *       bar §71 used one table over, and the difference is the point: a colleague in this company
 *       with no grant on this project cannot open the artifact, so asking them produces a request
 *       that will sit on their list for ever. The check is {@link ProjectAccess#canView}, the same
 *       question the gate asks of the caller, so the set of people who can be asked is exactly the
 *       set the picker lists. It also covers the §71 defect this column inherits: {@code app_user}
 *       is instance-level, so a <strong>sibling company's</strong> account satisfies the foreign
 *       key, the company column, RLS and every constraint — and holds no grant here, so it fails
 *       this.</li>
 *   <li><strong>A team that does not resolve in this company.</strong> §64's {@code sharedStepId}
 *       from the other end: an unchecked {@code team_id} is a stored reference nothing validated.
 *       </li>
 *   <li><strong>The person who submitted it.</strong> Derived rather than invented:
 *       {@code ReviewService.standingLgtms} <em>already</em> skips the submitter's own LGTM, on the
 *       stated ground that a peer review by the person who asked for it is not a second opinion. So
 *       a request naming the submitter is one the product will ignore by construction — an entry on
 *       a list that can never be discharged. Note this is the <em>submitter</em>, not the artifact's
 *       author: on a case written by an engineer and put forward by their lead, the engineer is a
 *       perfectly good reviewer and the lead is not.</li>
 * </ol>
 *
 * <h2>Why (2) and (3) carry no detail about what the id named</h2>
 *
 * <p>Each is one sentence covering both "no such row" and "a row you may not reach". Telling them
 * apart would make the endpoint an oracle for which user accounts and which teams exist on the
 * instance, answerable by anybody holding {@code *:review} on one project — §6t's rule, and the
 * same argument {@link ReviewCommentEdit} makes about its two parent refusals.
 */
record ReviewAssigneeEdit(UUID userId, UUID teamId) {

    /** Sealed, so the service's mapping over it is exhaustive — {@link GateResult}'s reason. */
    sealed interface Outcome {

        /** Field name, as spelled on {@link ReviewAssignmentRequest}, to its messages. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        record Valid(ReviewAssigneeEdit edit) implements Outcome {
        }
    }

    /**
     * @param review             the open review the request is against, read only for its submitter
     * @param userReachesProject whether {@code request.userId()} may see this project — computed by
     *                           the service through {@link ProjectAccess}, and meaningless (and
     *                           unread) when no {@code userId} was sent
     * @param team               what {@code request.teamId()} resolved to in this company, or null
     *                           when none was sent or none resolved. The two are told apart by
     *                           reading the request, which is why this takes both —
     *                           {@link ReviewCommentEdit#validate}'s arrangement
     */
    static Outcome validate(
            ReviewAssignmentRequest request,
            Review review,
            boolean userReachesProject,
            TeamRef team) {

        Map<String, List<String>> errors = new LinkedHashMap<>();

        UUID userId = request == null ? null : request.userId();
        UUID teamId = request == null ? null : request.teamId();

        if ((userId == null) == (teamId == null)) {
            errors.computeIfAbsent("userId", key -> new ArrayList<>())
                    .add("Ask either one person or one team to review, not both.");
            return new Outcome.Invalid(errors);
        }

        if (userId != null) {
            if (!userReachesProject) {
                errors.computeIfAbsent("userId", key -> new ArrayList<>())
                        .add("That person cannot see this project, so they cannot review it.");
            } else if (userId.equals(review.getCreatedBy())) {
                errors.computeIfAbsent("userId", key -> new ArrayList<>())
                        .add("They submitted this for review, so their own review would not count "
                                + "towards it.");
            }
        } else if (team == null) {
            errors.computeIfAbsent("teamId", key -> new ArrayList<>())
                    .add("That team no longer exists.");
        }

        return errors.isEmpty()
                ? new Outcome.Valid(new ReviewAssigneeEdit(userId, teamId))
                : new Outcome.Invalid(errors);
    }
}
