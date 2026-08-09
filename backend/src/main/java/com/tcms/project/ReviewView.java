package com.tcms.project;

import com.tcms.api.UserRef;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One review, as the contract's {@code Review}.
 *
 * <p>The subject is served back as the triple it is stored as — type, id, version — and nothing
 * resolves it to a case key or a plan name. The client asked about a subject it already has open;
 * expanding it here would mean this module reading {@code repository} and {@code execution} tables
 * to render a title the caller is already displaying above the panel.
 *
 * @param subjectVersionNo the version that was submitted, which the client compares against the
 *                         artifact's current version to show §6g's "this has changed since it was
 *                         submitted". The server does not compute that comparison, because only the
 *                         client holds both numbers at once
 * @param submittedBy      null for a row whose account no longer resolves — see
 *                         {@link ReviewParticipationView}
 * @param approvedBy       null unless {@code state} is {@code APPROVED}, and null on an approved
 *                         review whose approver has been offboarded. The pair with
 *                         {@code approvedAt} is what an auditor reads
 * @param assignees        who was <strong>asked</strong> to review, oldest request first — §6g's
 *                         reviewer assignment. Distinct from {@code participations}, which is who
 *                         <em>spoke</em>: the two lists overlap when the workflow is working and
 *                         diverge in exactly the way a lead wants to see, since a name in one and
 *                         not the other is a review nobody has got to
 * @param comments         §6g's threads, roots only — each carrying its own replies. Served with
 *                         the review rather than from an endpoint of their own, for the reason the
 *                         participations are: the panel reads a review as one thing, and a second
 *                         call would let it render a trail and a conversation that disagree about
 *                         which review they belong to
 */
public record ReviewView(
        UUID id,
        ReviewSubjectType subjectType,
        UUID subjectId,
        int subjectVersionNo,
        ReviewState state,
        UserRef submittedBy,
        Instant createdAt,
        UserRef approvedBy,
        Instant approvedAt,
        List<ReviewAssigneeView> assignees,
        List<ReviewParticipationView> participations,
        List<ReviewCommentView> comments) {
}
