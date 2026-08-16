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
 * clearing an estimate would silently become an estimate of zero seconds.
 *
 * <p>~~Jackson gives absent and null the same Java value here — both are {@code null} — so this
 * endpoint treats null as "clear it" for every nullable field and requires the client to send the
 * whole editable set. A caller wanting true partial-update semantics needs {@code JsonNullable} or
 * a presence map, which is a shape change on the contract.~~ → <strong>superseded by B1: the two
 * ARE distinguished, and the paragraph above is now true of this endpoint rather than of HTTP in
 * general.</strong> It cost neither a dependency nor a shape change on the wire — the update path
 * binds {@link com.tcms.api.PatchBody} beside this record and {@link #overlay} lays what was sent
 * over the case as it stands, so what a caller did not mention is what is already stored. The two
 * clients this changes are the ones that are not forms: {@code tcms case edit --title x} means what
 * it says now, and a round-tripped case is a no-op.
 *
 * <p><strong>The four required fields are still required</strong>, which is the plan's shape and not
 * an oversight: {@code title}, {@code stepFormat}, {@code priority} and {@code automationStatus} are
 * {@code NOT NULL} columns whose contract declares them required, and leave-alone semantics make a
 * missing one <em>resolvable</em> without making it meaningless. A body omitting them is accepted
 * and keeps the stored values; a body sending one as null is refused by name, which is the same
 * answer it always gave.
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
 * <p>Accepting them and dropping them is the failure the SPA's own mock handler used to comment on,
 * and it does not need the attribution: a save that reports success and discards a value the user
 * typed. The user then has no way to discover it
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
        List<CaseConditionInput> postconditions,

        // The INHERITED attachments this case declines (§187) — `suite_condition` ids, served back
        // as TestCase.declinedPreconditions[].id. Absent (null) is "leave the declines alone" and an
        // empty list is "decline nothing", the same split the three collections above use.
        //
        // It is the FOURTH field on this record to arrive by the route the header describes for the
        // refused two: the boundary was never a refusal here, it was a table with no writer at all.
        // `case_condition_optout` shipped in V1 with a reader, a filter and prose in four files
        // describing rows no deployment could contain — the writerless shape §116, §117, §132 and
        // §186 each found one instance of.
        List<UUID> declinedConditionIds) {

    /**
     * This request laid over the case as it stands — <strong>B1's leave-alone semantics, in one
     * place</strong>.
     *
     * <h2>What changed, and what the old reading cost</h2>
     *
     * <p>The header above records the limitation this method removes: Jackson gives an absent field
     * and an explicit {@code null} the same Java value, so the update path had to pick one meaning
     * for both and picked <em>clear it</em>. Correct for the SPA, which submits every field it
     * renders, and a trap with no symptom for everybody else — {@code {"title": "x"}} renamed a case
     * and emptied four columns beside it, answering 200. The client-side answer
     * ({@code @tcms/client}'s {@code core/rewrite.ts}) was a read-modify-write, which is the right
     * shape in the wrong place: it makes every client that is not a form hold a copy of the rule.
     *
     * <p>{@link com.tcms.api.PatchBody} is what makes the two distinguishable, so the reading is now
     * the one the verb implies: <strong>absent leaves alone, an explicit null clears</strong>, and a
     * list is unchanged — null leaves alone, {@code []} clears, which is what §68 settled and what a
     * list's own empty spelling always made available.
     *
     * <h2>The four required fields are NOT resolved from the stored case, and that is the shape</h2>
     *
     * <p>{@code title}, {@code stepFormat}, {@code priority} and {@code automationStatus} come from
     * the body as sent, so omitting one is the 400 it has always been. This is deliberately the
     * <em>plan's</em> arrangement — {@code TestPlanUpdateInput} requires a {@code name} and leaves
     * everything else alone — and it is what B1 asked for in as many words.
     *
     * <p>The alternative, resolving them too, makes this a fully partial {@code PATCH} and costs
     * something that is not obvious: {@code TestCaseInput.required} is one schema serving both the
     * create and the update, so a server that stopped enforcing the four here would leave that
     * array true of {@code POST} and false of {@code PATCH} — and
     * {@code CaseUpdateEndpointTest.contractRequiredMatchesWhatTheServerEnforces}, which derives the
     * array by dropping each field in turn from a body this endpoint accepts, would discover
     * nothing at all. Splitting the schema in two is the honest way to have both, and it is a
     * contract change with a generated-type consequence (§144's defect, in reverse) rather than a
     * line here. Named as not done, with the reason.
     *
     * <h2>Three fields the base deliberately does not supply</h2>
     *
     * <ul>
     *   <li>{@code changeComment} is about <em>this save</em>, so an absent one is no comment and
     *       never the last save's. Inheriting it would attach one author's sentence to another's
     *       edit and — because the comment is hashed — write a version whose diff is empty.</li>
     *   <li>{@code ownerId} and {@code automationTags} are the two the update path refuses. Absent
     *       is left absent so a caller who never mentioned them is never refused for them; a caller
     *       who sends them is judged on what they sent (see {@code CaseEdit.refuseUnsupported},
     *       which since B2 refuses only a <em>change</em>).</li>
     * </ul>
     *
     * @param stored the case as it is, from {@link CaseEdit#asRequest}
     * @param sent what the caller's body bound to — every absent field is null here, which is
     *     exactly why it cannot be used alone
     * @param body the same body, still able to say which fields it carried
     */
    static CaseUpdateRequest overlay(
            CaseUpdateRequest stored, CaseUpdateRequest sent, com.tcms.api.PatchBody body) {

        return new CaseUpdateRequest(
                // The four the schema REQUIRES are taken from the body as sent, so an absent one is
                // still the 400 it has always been. See the javadoc: this is the plan's shape, and
                // `CaseUpdateEndpointTest.contractRequiredMatchesWhatTheServerEnforces` is what
                // holds the two together — resolving them from the stored case would empty that
                // check's population and leave `TestCaseInput.required` a claim nothing makes.
                sent.title(),
                body.has("suiteId") ? sent.suiteId() : stored.suiteId(),
                body.has("description") ? sent.description() : stored.description(),
                body.has("objective") ? sent.objective() : stored.objective(),
                body.has("expectedOutcome") ? sent.expectedOutcome() : stored.expectedOutcome(),
                sent.stepFormat(),
                body.has("gherkinSource") ? sent.gherkinSource() : stored.gherkinSource(),
                sent.priority(),
                body.has("severity") ? sent.severity() : stored.severity(),
                sent.automationStatus(),
                body.has("automationKey") ? sent.automationKey() : stored.automationKey(),
                body.has("notAutomatableReason")
                        ? sent.notAutomatableReason() : stored.notAutomatableReason(),
                body.has("estimatedDurationSec")
                        ? sent.estimatedDurationSec() : stored.estimatedDurationSec(),
                // The one collection whose absence is NOT already "leave alone": a null `steps`
                // normalizes to an empty list, so an absent one would delete the procedure. The
                // stored steps are the base, which makes an unmentioned procedure survive an edit
                // to the title — and `[]` still clears, because a list keeps its own empty
                // spelling here as everywhere else.
                body.has("steps") ? sent.steps() : stored.steps(),
                // Never inherited — see the javadoc.
                sent.changeComment(),
                // The five below already read null as "leave alone", so the overlay has nothing to
                // add: an absent field binds to null and null is the instruction. They are written
                // out rather than defaulted so that adding a sixth collection means answering this
                // question for it.
                sent.classificationValueIds(),
                sent.tags(),
                sent.ownerId(),
                sent.automationTags(),
                sent.preconditions(),
                sent.postconditions(),
                sent.declinedConditionIds());
    }

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
