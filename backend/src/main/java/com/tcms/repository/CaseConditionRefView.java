package com.tcms.repository;

import java.util.List;
import java.util.UUID;

/**
 * {@code CaseConditionRef} in {@code docs/specs/openapi.yaml} — one resolved condition on one case.
 *
 * <p>The same contract shape {@link SuiteConditionView} serves, and a deliberately separate record
 * rather than a shared one, because the two fill it in from different sources and the fields that
 * differ are exactly the ones that matter. A suite's own conditions are never inherited and can
 * never name an ancestor; a case's are inherited more often than not. One record serving both would
 * have to carry a constructor for each anyway, and the "always false here" comments that make
 * {@link SuiteConditionView} readable would become "sometimes false, depending which caller built
 * it".
 *
 * <h2>{@code inherited} is what the SPA draws a border around</h2>
 *
 * <p>{@code CaseDetailPage} marks an inherited condition and warns that editing it affects every
 * case beneath the suite. That warning is the entire safety property of suite-level inheritance
 * (plan §6f): an unmarked inherited condition reads as local, gets "fixed" locally, and the fix
 * lands on two hundred cases nobody was looking at. So the flag is derived from <em>where the row
 * came from</em> — a {@link SuiteCondition} or a {@link CaseCondition} — and never from anything a
 * writer could set inconsistently.
 *
 * @param id     the <strong>attachment's</strong> id, not the library condition's. Two cases
 *               referencing one condition are two rows, and the SPA keys its list items on this.
 *               For an inherited entry it is the {@code suite_condition} row's id, which is also
 *               what an opt-out is keyed on — so the client already holds the id it would need to
 *               decline the row.
 * @param body   the ordered steps, from the library entry. Never null; may legitimately be empty.
 * @param inline always false while inline attachments are excluded ({@link CaseConditionRepository}
 *               and {@link SuiteConditionRepository} both say why). Kept because the contract has
 *               it and a consumer branching on it is correct today and stays correct after.
 * @param inherited true ⇒ the row came from a suite at or above the case's own, not from the case.
 *               <strong>A condition attached to the case's own folder is inherited too</strong> —
 *               the case did not attach it, and editing it still reaches every sibling.
 * @param inheritedFrom the ancestor suite's display <em>name</em> ({@code 'Billing'}), because it
 *               is rendered in a sentence. Null exactly when {@link #inherited} is false; the two
 *               are set together and neither is meaningful alone.
 */
public record CaseConditionRefView(
        UUID id,
        int position,
        UUID conditionId,
        String title,
        List<String> body,
        boolean inline,
        boolean inherited,
        String inheritedFrom) {

    /** An entry the case itself attached. */
    static CaseConditionRefView own(CaseCondition attachment, Condition condition) {
        return new CaseConditionRefView(
                attachment.getId(),
                attachment.getPosition(),
                condition.getId(),
                condition.getTitle(),
                condition.getBody(),
                false,
                false,
                null);
    }

    /** An entry inherited from {@code suiteName}, at or above the case's own folder. */
    static CaseConditionRefView inheritedFrom(
            SuiteCondition attachment, Condition condition, String suiteName) {

        return new CaseConditionRefView(
                attachment.getId(),
                attachment.getPosition(),
                condition.getId(),
                condition.getTitle(),
                condition.getBody(),
                false,
                true,
                suiteName);
    }
}
