package com.tcms.repository;

import java.util.List;
import java.util.UUID;

/**
 * {@code TestCaseInput} in {@code docs/specs/openapi.yaml}, exactly as it arrives on the wire.
 *
 * <h2>Every field is a boxed reference, and that is not laziness</h2>
 *
 * <p>This is a {@code PATCH}, so <strong>absent and null are different instructions</strong>:
 * omitting {@code objective} means "leave it alone" and sending {@code null} means "clear it". A
 * primitive {@code int} for {@code estimatedDurationSec} would arrive as {@code 0} for both, and
 * clearing an estimate would silently become an estimate of zero seconds. Jackson gives absent and
 * null the same Java value here — both are {@code null} — which is why {@link CaseEdit} resolves
 * them against the stored case rather than this record trying to tell them apart.
 *
 * <p><strong>Consequence, stated because it is a real limitation:</strong> a field cannot be
 * distinguished from a field explicitly set to null, so this endpoint treats null as "clear it" for
 * every nullable field and requires the client to send the whole editable set. The SPA does exactly
 * that — its editor submits every field it renders on every save — so the semantics match the only
 * client. A caller wanting true partial-update semantics needs {@code JsonNullable} or a presence
 * map, which is a shape change on the contract and not something to infer from one field's absence.
 *
 * <h2>The six fields this endpoint refuses</h2>
 *
 * <p>{@code ownerId}, {@code classificationValueIds}, {@code tags}, {@code automationTags},
 * {@code preconditions} and {@code postconditions} are on the contract's {@code TestCaseInput} and
 * are <strong>rejected with a 400 when present</strong> rather than ignored. Each needs a write path
 * this slice does not build — cardinality validation against the project's dimensions for
 * classification, the library-versus-inline shape for conditions, a membership check for the owner.
 *
 * <p>Accepting them and dropping them is the failure the SPA's own mock handler comments on: a save
 * that reports success and discards a value the user typed. The user then has no way to discover it
 * except by reloading, and the value they discover missing is one they will retype. Refusing loudly
 * is worse UX than working and better than lying, and it is a boundary a later slice deletes in one
 * place.
 */
public record CaseUpdateRequest(
        String title,
        UUID suiteId,
        String description,
        String objective,
        String expectedOutcome,
        StepFormat stepFormat,
        String gherkinSource,
        Priority priority,
        Priority severity,
        AutomationStatus automationStatus,
        String automationKey,
        String notAutomatableReason,
        Integer estimatedDurationSec,
        List<StepInput> steps,
        String changeComment,

        // --- the refused six. Declared so they can be REJECTED rather than silently swallowed by
        // Jackson's unknown-property handling, which would make them indistinguishable from a
        // typo and let the caller believe they were applied.
        UUID ownerId,
        List<UUID> classificationValueIds,
        List<String> tags,
        List<String> automationTags,
        List<Object> preconditions,
        List<Object> postconditions) {

    /**
     * {@code TestStepInput} — one step as submitted.
     *
     * @param id null for a step being added; the existing {@code test_step.id} for one being kept.
     *           <strong>This field is what makes reordering safe</strong>: {@code step_result} rows
     *           reference these ids, so a step that keeps its id keeps its evidence (handoff §3
     *           load-bearing detail 4). A client that drops the ids turns every edit into a
     *           delete-and-recreate and orphans every past result — see {@link CaseSteps}.
     */
    public record StepInput(
            UUID id,
            String keyword,
            String action,
            String testData,
            String expectedResult,
            UUID sharedStepId) {
    }
}
