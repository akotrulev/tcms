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
 * <h2>The two fields this endpoint refuses — it was six</h2>
 *
 * <p>{@code ownerId} and {@code automationTags} are on the contract's {@code TestCaseInput} and are
 * <strong>rejected with a 400 when present</strong> rather than ignored. Each needs a write path no
 * slice has built yet — a membership check for the owner, and a {@code case_automation_tag}
 * reconcile for the selection tags, which are a different table and a different job from the
 * organisational {@code tags} (plan §6c).
 *
 * <p><strong>{@code classificationValueIds} left this list in §68,
 * {@code preconditions}/{@code postconditions} in §95, and {@code tags} in §96</strong> — which is what the
 * paragraph below always said would happen to each of them in turn: the field is accepted,
 * validated, and persisted, and the boundary is deleted in the one place it was written. The
 * conditions are validated against {@link CaseEdit#validate}'s {@code acceptableConditionIds} — the
 * same union rule §64 settled for a shared step — and the reconcile that applies them is
 * {@link CaseConditions#reconcile}.
 *
 * <p><strong>Only the reference form is accepted.</strong> An inline body is still refused, and
 * {@link CaseConditionInput} states why: nothing can render one, so storing it would be a
 * successful save the author can never see again.
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

        // Absent (null) means "leave the classification alone"; an empty list means "clear it".
        // The two are the same Java value for every OTHER nullable field on this record — see the
        // header — and they are distinguishable here only because the field is a collection, where
        // "not sent" and "sent empty" have different representations. That is worth having: the
        // bulk actions reconstruct a request without touching classification, and a null here is
        // how they say so without having to carry the case's stored values through code that has
        // no interest in them.
        List<UUID> classificationValueIds,

        // Absent (null) means "leave the tags alone"; an empty list means "clear them". The same
        // split classificationValueIds uses and it matters more here, because the bulk actions
        // reconstruct a request without mentioning tags: reading absent as "clear" would have every
        // MOVE_TO_SUITE quietly strip a case out of every tag-resolved suite it was in.
        //
        // NOT on CaseEdit, and therefore NOT in CaseVersions#hash — the one field this endpoint
        // accepts that is deliberately not authored content. See CaseEdit.Outcome.Valid.
        List<String> tags,

        // --- the refused two. Declared so they can be REJECTED rather than silently swallowed by
        // Jackson's unknown-property handling, which would make them indistinguishable from a
        // typo and let the caller believe they were applied.
        UUID ownerId,
        List<String> automationTags,
        // Absent (null) means "leave the conditions alone"; an empty list means "detach them all".
        // The same split `classificationValueIds` above uses, and for the same reason: they are
        // collections, so "not sent" and "sent empty" have different representations. The bulk
        // actions reconstruct a request without touching conditions, and a null here is how they
        // say so without carrying the case's stored attachments through code with no interest in
        // them. See CaseEdit.of.
        List<CaseConditionInput> preconditions,
        List<CaseConditionInput> postconditions) {

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
