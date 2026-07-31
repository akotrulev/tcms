package com.tcms.repository;

import java.util.List;
import java.util.UUID;

/**
 * {@code Condition} in {@code docs/specs/openapi.yaml} — one entry in a project's reusable
 * pre/post condition library (plan §6f, ERD §4).
 *
 * <p>Distinct from {@link SuiteConditionView}, which is the contract's {@code CaseConditionRef}:
 * that shape describes an <em>attachment</em> — where a condition is used, in what position, and
 * whether it was inherited — while this one describes the library entry itself. The two carry the
 * same title and body and are otherwise about different things, which is why they are two records
 * rather than one with half its fields null. {@code SuiteConditionView} exposes the attachment's
 * own id; this exposes the library entry's, and a picker that confused them would attach the wrong
 * row.
 *
 * @param id         the library entry's id — what {@code CaseConditionInput.conditionId} takes
 * @param body       the ordered steps. Never null; may legitimately be empty
 * @param usageCount zero on every row until something maintains it — see {@link Condition}
 */
public record ConditionView(
        UUID id,
        String title,
        ConditionType type,
        List<String> body,
        int usageCount) {

    static ConditionView of(Condition condition) {
        return new ConditionView(
                condition.getId(),
                condition.getTitle(),
                condition.getType(),
                condition.getBody(),
                condition.getUsageCount());
    }
}
