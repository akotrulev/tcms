package com.tcms.project;

import java.util.UUID;

/**
 * The contract's {@code ReviewAssignmentInput} — one request to review, addressed to a person or to
 * a team.
 *
 * <p>Two nullable fields with an exactly-one rule, rather than a {@code (kind, id)} pair. The pair
 * would need its own enum, its own parse failure and its own field error for "kind says TEAM and the
 * id names a person", all to express what two typed fields express by existing. It is also the shape
 * {@code RoleGrantRequest} already uses for the identical "a user or a team" question one module
 * over, so this is the contract's existing answer rather than a second one.
 *
 * <p>There is deliberately no {@code assignedBy} and no {@code assignedAt}: the asker is the
 * authenticated caller and the instant is the server's, for the reason
 * {@link ReviewParticipationRequest} gives about the record this is kept beside.
 *
 * <p>There is also no {@code note}. §6g's Mechanics ask for reviewers and for comments as two
 * separate things, and a message attached to a request would be a third place a reviewer has to look
 * for what they were asked to do — one that no thread can be resolved against.
 *
 * @param userId one person, who must be able to <em>see the project</em>; see
 *               {@link ReviewAssigneeEdit} for why that is the bar and not company membership
 * @param teamId one team in this company, any member of which may action the request (§6g)
 */
public record ReviewAssignmentRequest(UUID userId, UUID teamId) {
}
