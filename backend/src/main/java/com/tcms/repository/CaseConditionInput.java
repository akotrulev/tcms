package com.tcms.repository;

import java.util.List;

/**
 * {@code CaseConditionInput} in {@code docs/specs/openapi.yaml} — one condition attached to a case,
 * as submitted.
 *
 * <h2>Only the reference form is accepted, and the inline half is refused rather than ignored</h2>
 *
 * <p>{@code V1}'s {@code case_condition} is a reference <em>or</em> an inline body, never both and
 * never neither ({@code CHECK ((condition_id IS NULL) <> (inline_body IS NULL))}). The contract's
 * schema carries both fields, and §95 implements <strong>only {@code conditionId}</strong>.
 *
 * <p>That is not an oversight and it is not laziness — it is the same boundary
 * {@link CaseUpdateRequest} draws around its refused fields, for a reason specific to this one:
 * <strong>nothing can render an inline attachment.</strong> The contract requires a {@code title} on
 * every {@code CaseConditionRef}, an inline row has no column one could come from, and both readers
 * exclude them at the query ({@link CaseConditionRepository#findForCase},
 * {@link SuiteConditionRepository#findForSuites}) with that stated as the reason. So an accepted
 * inline body would be written, would satisfy the check constraint, and would then be **invisible in
 * every response** — a condition the author typed, saved successfully, and can never see again.
 *
 * <p>Refusing it with a field error is the honest answer until a reader exists. When one does, this
 * record is where the second arm lands and {@link CaseEdit} is where it is validated.
 *
 * @param conditionId the library entry to attach. Required — see above
 * @param inlineBody  declared so it can be <strong>refused</strong> rather than silently swallowed
 *                    by Jackson's unknown-property handling, which would make it indistinguishable
 *                    from a typo and let the caller believe it was applied
 */
public record CaseConditionInput(java.util.UUID conditionId, List<String> inlineBody) {
}
