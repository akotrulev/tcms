package com.tcms.repository;

/**
 * The body of {@code PUT /projects/{projectId}/cases/{caseId}/quarantine} —
 * {@code CaseQuarantineInput}.
 *
 * <h2>An absolute state, not a verb — and this is a PUT for that reason</h2>
 *
 * <p>{@code quarantined: true} and {@code quarantined: false} rather than {@code POST /quarantine}
 * and {@code DELETE /quarantine}, which is the other obvious shape. The deciding difference is what
 * happens when a client sends it twice: the two-verb form makes releasing a case that is not
 * quarantined a 404 and quarantining one that already is a 409, and neither is a fact the caller
 * cares about — they asked for a state and the case is in it. {@code SuiteStatusRequest} and
 * {@code RunStatusRequest} take the same shape for the same reason.
 *
 * <p>It is also the shape a checkbox on a screen produces, and a client that has to translate a
 * toggle into two different HTTP methods is where the "quarantine turned itself off" bug comes from.
 *
 * <h2>{@code reason} is required when quarantining and ignored when releasing</h2>
 *
 * <p>Not enforced here as a {@code @NotBlank}, because it is conditional on the sibling field and a
 * bean-validation message cannot say <em>which</em> field made it required.
 * {@link CaseQuarantineService} raises it as a field error the form can land on, and
 * {@code case_flake_quarantine_complete} refuses the row underneath regardless of what any caller
 * believes.
 *
 * <p>A {@code Boolean} rather than a {@code boolean}: an absent field must be a 400 naming the
 * field, not a silent {@code false} that releases a quarantine the caller never mentioned. That is
 * the one shape of this request that could destroy a decision by omission.
 *
 * @param quarantined the state to move to
 * @param reason      why — required when {@code quarantined} is true
 */
public record CaseQuarantineRequest(Boolean quarantined, String reason) {
}
