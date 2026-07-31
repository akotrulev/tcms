package com.tcms.repository;

import java.util.List;
import java.util.UUID;

/**
 * {@code CaseConditionRef} in {@code docs/specs/openapi.yaml} — one condition attached to a suite.
 *
 * <h2>{@code inherited} is false here, and that is not a stub</h2>
 *
 * <p>The field distinguishes a condition a <em>case</em> owns from one it inherited from a suite
 * above it (plan §6f). Read on a suite, every condition in the list is that suite's own — the
 * inheritance runs downward to the cases beneath, which is what the contract's description means
 * by "inherited by every case beneath". The walk up {@code suite.path} that resolves a case's full
 * condition set belongs to the case endpoint, and doing it here as well would be the second
 * implementation of a resolution order that must have exactly one.
 *
 * <h2>Two contract gaps this shape has, recorded rather than worked around</h2>
 *
 * <ol>
 *   <li><strong>An inline attachment has no title, and {@code title} is required.</strong>
 *       {@code suite_condition} stores either a {@code condition_id} or an {@code inline_body}
 *       and nothing else — there is no column a title could come from, and {@code
 *       CaseConditionInput} has no field a writer could send one in. So the inline form is a state
 *       the API cannot describe. Inventing a title (the first body line, "Inline condition") would
 *       be the response making a claim the data never made, which is the mistake §22's dangling
 *       "Your role:" and §23's fallback status colour were both fixed by <em>not</em> making. The
 *       rows are therefore excluded at the query
 *       ({@link SuiteConditionRepository#findForSuites}) and this is the gap's record. It closes
 *       with the condition-authoring endpoint that first makes an inline row reachable, which is
 *       also the first moment the question has a real answer: either the contract grows an inline
 *       title or {@code title} becomes nullable. Nothing can create one today.</li>
 *   <li><strong>{@code kind} (PRE / POST) has nowhere to go.</strong> The column exists and the
 *       distinction is real — a postcondition is cleanup, not setup — but {@code CaseConditionRef}
 *       carries no field for it, so the list arrives undifferentiated. Adding one is an
 *       {@code openapi.yaml} change with a consumer on the other side, and there is no consumer of
 *       suite conditions at all yet.</li>
 * </ol>
 *
 * <p>A third case drops out quietly and is worth naming: an attachment whose library condition has
 * been <em>retired</em> resolves to nothing and is omitted. Serving it would offer a withdrawn
 * precondition as current; the honest fix is the impact warning §6f specifies at retirement time,
 * which is a write-side surface.
 *
 * @param id       the attachment's id, not the library condition's — two suites referencing one
 *                 condition are two rows, and the SPA keys list items on this
 * @param body     the ordered steps, from the library entry. Never null; may be empty.
 * @param inline   always false while inline rows are excluded — kept because the contract has it
 *                 and a consumer branching on it is correct today and stays correct after
 * @param inheritedFrom null here by construction: it names the ancestor suite a case's condition
 *                 came from, and a suite's own conditions came from nowhere
 */
public record SuiteConditionView(
        UUID id,
        int position,
        UUID conditionId,
        String title,
        List<String> body,
        boolean inline,
        boolean inherited,
        String inheritedFrom) {

    static SuiteConditionView of(SuiteCondition attachment, Condition condition) {
        return new SuiteConditionView(
                attachment.getId(),
                attachment.getPosition(),
                condition.getId(),
                condition.getTitle(),
                condition.getBody(),
                false,
                false,
                null);
    }
}
