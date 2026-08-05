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
 * <h2>Two counts, and they must not be added together</h2>
 *
 * <p>Both are derived as of §95 — the stored {@code condition.usage_count} was unmapped, see
 * {@link Condition}. They are separate fields because they count different things:
 * {@code usageCount} is <em>cases</em> attaching the entry directly, {@code suiteUsageCount} is
 * <em>suites</em> attaching it, and a suite attachment reaches every case beneath it by
 * inheritance. Their sum is a number about nothing. The client is expected to render the second as
 * "attached to N suites — every case beneath inherits it" rather than converting it into a case
 * figure it has no basis to compute.
 *
 * @param id              the library entry's id — what {@code CaseConditionInput.conditionId} takes
 * @param body            the ordered steps. Never null; may legitimately be empty
 * @param usageCount      distinct live cases attaching it directly
 * @param suiteUsageCount live suites attaching it — each propagating to its whole subtree
 * @param lockVersion     what the client sends back as {@code If-Match} on the {@code PUT}
 */
public record ConditionView(
        UUID id,
        String title,
        ConditionType type,
        List<String> body,
        int usageCount,
        int suiteUsageCount,
        long lockVersion) {

    static ConditionView of(Condition condition, long cases, long suites) {
        return new ConditionView(
                condition.getId(),
                condition.getTitle(),
                condition.getType(),
                condition.getBody(),
                Math.toIntExact(cases),
                Math.toIntExact(suites),
                condition.getLockVersion());
    }
}
