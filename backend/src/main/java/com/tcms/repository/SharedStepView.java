package com.tcms.repository;

import java.util.List;
import java.util.UUID;

/**
 * {@code SharedStep} in {@code docs/specs/openapi.yaml} — one entry in a project's reusable step
 * library (plan §6f, ERD §4).
 *
 * @param id         what {@code TestStepInput.sharedStepId} takes
 * @param body       the ordered actions. Never null; may legitimately be empty on an old row
 * @param usageCount distinct live cases referencing this entry — <strong>derived</strong>, and
 *                   never the stored {@code shared_step.usage_count}. See {@link SharedStep} for
 *                   why the column is not maintained, and {@link SharedStepUsage} for why the
 *                   count is over distinct cases rather than step rows
 */
public record SharedStepView(UUID id, String title, List<String> body, int usageCount) {

    /**
     * @param usageCount the derived count, defaulted to 0 by the caller when the entry appears in
     *                   no {@link SharedStepRepository#countUsage} row — which is the normal state
     *                   of a newly created entry, not an error
     */
    static SharedStepView of(SharedStep step, long usageCount) {
        return new SharedStepView(
                step.getId(),
                step.getTitle(),
                step.getBody(),
                // A project cannot hold more than Integer.MAX_VALUE cases, and the contract types
                // the field as an integer; the cast is narrowing a count that is bounded by the
                // case table, not a value a client controls.
                (int) usageCount);
    }
}
