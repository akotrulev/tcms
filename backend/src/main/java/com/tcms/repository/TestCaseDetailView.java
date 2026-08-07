package com.tcms.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code TestCase} in {@code docs/specs/openapi.yaml} — one whole case, the detail screen's body.
 *
 * <h2>Flat, because {@code allOf} is flat</h2>
 *
 * <p>The contract declares {@code allOf: [TestCaseSummary, {…}]}, which is one JSON object with
 * both sets of keys at the top level — not a summary nested under a field. So the summary's
 * seventeen components are repeated here rather than held as a {@link TestCaseSummaryView} member.
 *
 * <p>The repetition is deliberate and the two alternatives are both worse. {@code @JsonUnwrapped}
 * on a nested record produces the right JSON by a mechanism nothing in this codebase uses, and
 * whose failure mode is a silently nested response. Composing the response as a {@code Map} gives
 * up the compile-time check that {@code openapi.yaml}'s required fields are all present, which is
 * the check that catches a renamed field. What matters is that the <em>values</em> are not
 * re-derived: {@link #of} takes a {@link TestCaseSummaryView} built by {@link CaseSummaries} — the
 * same object the grid renders — and copies it component by component, so a grid row and a detail
 * page cannot disagree about a case. The copying is mechanical; the resolution is shared.
 *
 * <h2>Two fields the contract has and this response does not</h2>
 *
 * <ol>
 *   <li><strong>{@code relatedDocuments}.</strong> Sourced from {@code doc_link} against
 *       {@code doc_page} / {@code doc_block}, which are the {@code documentation} module — and that
 *       module does not exist. Nothing can create a link, so no case has one. The contract does not
 *       mark the field required and the SPA already renders its absence as an empty panel, which is
 *       exactly true. Serializing {@code []} would say the same thing while claiming the question
 *       had been asked.</li>
 *   <li><strong>{@code lastResult}.</strong> Sourced from {@code test_execution}; the
 *       {@code execution} module does not exist either. Same call as {@link TestCaseSummaryView}
 *       makes, and made the same way for the same reason — the SPA reads a missing
 *       {@code lastResult} as "Never run".</li>
 * </ol>
 *
 * @param gherkinSource null for a {@code STRUCTURED} case; {@link #steps} is empty for a
 *              {@code GHERKIN} one. The two are alternatives (plan §6a) and the SPA branches on
 *              {@link #stepFormat} rather than on which of them is populated — which is what keeps
 *              a case that somehow carries both from rendering twice.
 * @param steps  never null. Empty is a real and common state: a case can be created with a title
 *              and filled in later, and the SPA has an empty state that says so.
 * @param preconditions resolved through {@link CaseConditions} — inherited entries first, marked,
 *              naming the suite they came from.
 * @param currentVersionNo the authored version, {@code v7} beside the title. Not
 *              {@link #lockVersion}; see {@link TestCase#getCurrentVersionNo()} for why showing the
 *              wrong one puts a number beside the title that no history entry matches.
 * @param lockVersion on the wire because the SPA sends it back on a PATCH, so a concurrent edit is
 *              a 409 rather than a silent overwrite (plan §6j). The one internal that earns its
 *              place in a response.
 * @param flake the case's flake evidence and quarantine decision, or <strong>null when no
 *              {@code case_flake} row exists at all</strong> — the recompute has never reached this
 *              case. That is a third state and the SPA renders all three differently: no row is
 *              "nobody has looked at this test", a row with a null {@code flakeScore} is "not enough
 *              evidence to say", and a score of zero is "evidently stable". Two of those three are
 *              easy to collapse and mean opposite things.
 *
 *              <p>Detail only, never {@link TestCaseSummaryView}: the grid draws what a case
 *              <em>is</em>, and this is read while deciding what to do about one case. A per-row
 *              join on the list endpoint would buy a column nobody asked for.
 *
 *              <p><strong>It lands in the version snapshot, and that is accepted rather than
 *              unnoticed.</strong> {@link CaseVersions} serializes this whole view as the authored
 *              version's body, so a v7 row records the flake measurement as it stood the moment
 *              somebody saved. {@code healthSignals} above is derived and has been in that snapshot
 *              since M1, so this follows an established line rather than crossing a new one — and
 *              critically the content HASH is computed over {@link CaseEdit}, not over this view, so
 *              a nightly recompute still cannot cause a save that changed nothing to write a
 *              version. Making the snapshot authored-only is a real cleanup and it is a separate
 *              slice; doing it here would mean the response and the snapshot stop being one render,
 *              which is the invariant {@link CaseRenderer} exists to hold.
 */
public record TestCaseDetailView(
        UUID id,
        String key,
        long keySeq,
        String title,
        UUID suiteId,
        String suiteName,
        Priority priority,
        LifecycleStatus lifecycleStatus,
        AutomationStatus automationStatus,
        StepFormat stepFormat,
        UserRefView owner,
        // WHO WROTE THE CASE. Required by TestCaseSummary, which `TestCase` is an allOf of, and
        // MISSING HERE until 2026-08-02 — §66 put it on the wire for the grid and this view, which
        // shares the contract schema, kept omitting the key.
        //
        // It is not a cosmetic omission: the case detail is the only screen with a delete
        // affordance, its gate is `case:delete` qualified OWN, and the SPA passes
        // `data.createdBy?.id ?? null`. An absent key reads as null, null is a SUPPLIED author that
        // matches nobody, and the gate therefore refused a Test Engineer their own case — the exact
        // inverse of what §66 shipped. Nothing caught it: `tsc` allows optional chaining on a
        // required field, and the page's own test supplies a fixture that has the key.
        UserRefView createdBy,
        String testType,
        String testLevel,
        List<String> features,
        // The same assignments `features`/`testType`/`testLevel` render by name, as ids — what an
        // editor needs and a name cannot supply, since the Feature tree may hold two leaves with
        // one name and the company vocabularies are renameable. Detail only: the grid draws names
        // and would pay four ids a row for nothing.
        //
        // Filtered by CaseClassifications#liveValueIdsOf, which is the SAME rule CaseSummaries
        // applies to the names — one implementation, so the ids and the names on one response can
        // never disagree about what the case is classified as.
        List<UUID> classificationValueIds,
        List<String> tags,
        List<HealthSignal> healthSignals,
        Instant updatedAt,
        String description,
        String objective,
        String expectedOutcome,
        Priority severity,
        Integer estimatedDurationSec,
        List<String> automationTags,
        String automationKey,
        String notAutomatableReason,
        String gherkinSource,
        List<TestStepView> steps,
        List<CaseConditionRefView> preconditions,
        List<CaseConditionRefView> postconditions,
        int currentVersionNo,
        long lockVersion,
        CaseFlakeView flake) {

    static TestCaseDetailView of(
            TestCase row,
            TestCaseSummaryView summary,
            List<String> automationTags,
            List<UUID> classificationValueIds,
            List<TestStepView> steps,
            CaseConditions.Resolved conditions,
            CaseFlakeView flake) {

        return new TestCaseDetailView(
                summary.id(),
                summary.key(),
                summary.keySeq(),
                summary.title(),
                summary.suiteId(),
                summary.suiteName(),
                summary.priority(),
                summary.lifecycleStatus(),
                summary.automationStatus(),
                summary.stepFormat(),
                summary.owner(),
                summary.createdBy(),
                summary.testType(),
                summary.testLevel(),
                summary.features(),
                classificationValueIds,
                summary.tags(),
                summary.healthSignals(),
                summary.updatedAt(),
                row.getDescription(),
                row.getObjective(),
                row.getExpectedOutcome(),
                row.getSeverity(),
                row.getEstimatedDurationSec(),
                automationTags,
                row.getAutomationKey(),
                row.getNotAutomatableReason(),
                row.getGherkinSource(),
                steps,
                conditions.preconditions(),
                conditions.postconditions(),
                row.getCurrentVersionNo(),
                row.getLockVersion(),
                flake);
    }
}
