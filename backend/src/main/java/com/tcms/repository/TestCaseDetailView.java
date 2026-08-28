package com.tcms.repository;

import com.tcms.api.UserRef;
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
 * <h2><del>Two fields</del><del>One field</del> No field the contract has and this response does
 * not</h2>
 *
 * <p><strong>Both reasons here were rewritten by §165, and both used to blame a module for not
 * existing.</strong> {@code documentation} is 57 files and {@code execution} is 119. What actually
 * blocked each was the <em>module boundary</em> — {@code repository} may not import either, which
 * this package's {@code package-info} has stated correctly the whole time — so each closed as a
 * read model fed by events (§3 lesson 4), not by a join and not by waiting. An argument that
 * misnames the obstacle deters the repair, which is §160's finding at a different field.
 *
 * <p><strong>§166 acted on that for the first of the two and §167 for the second, which is the
 * whole of the evidence that the rewrite was worth doing.</strong> {@code lastResult} is carried
 * through from {@link TestCaseSummaryView} by {@link #of} like every other summary component and
 * derived by {@code ExecutionFacts} into {@code case_health}; {@code relatedDocuments} is
 * {@link #relatedDocuments} below, mirrored by {@code DocumentationFacts} into
 * {@code case_doc_link}. Both took the shape the rewritten paragraph prescribed, one section apart,
 * and neither needed anything the module boundary forbids.
 *
 * <p><strong>Nothing is left in {@code MEMBER_DIVERGENCE_BY_DECISION} for this schema.</strong> The
 * two {@code lastResult} entries went in §166 and the {@code relatedDocuments} one in §167, and in
 * every case the entries were <em>deleted</em> rather than edited — that map is pinned in both
 * directions, so closing a divergence and keeping its argument fails the build.
 *
 * @param gherkinSource <strong>null unless this case has no step rows at all</strong>, and then
 *              the stored blob. §255 made {@link #stepFormat} a lens over one storage rather than
 *              a switch between two, so a Gherkin case is step rows like any other and nothing
 *              writes this column any more.
 *
 *              <p>The population it still serves is finite and shrinking: cases authored before
 *              §255 whose scenario {@code migrate-gherkin-source} has not yet turned into rows. The
 *              SPA renders those read-only with a note naming the command, which is why the
 *              condition is <em>zero rows</em> and not <em>{@code stepFormat == GHERKIN}</em> —
 *              serving both would put a scenario and its own migrated rows on one screen, saying
 *              the same thing twice and disagreeing the moment somebody edits one of them.
 *              <strong>The field leaves the contract in {@code 002-}.</strong>
 * @param importedFrom the {@code .feature} file and line this case was imported from, or null.
 *              Null for every case today: {@code V69} adds the columns and the import endpoint is
 *              what fills them.
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
 * @param relatedDocuments the specification points this case claims to verify — never null, empty
 *              being the ordinary state and, since §167, a state the server has actually looked at.
 *              It had been declared in the contract and served by nothing since M0, so the
 *              traceability panel said <em>"Not linked to any specification"</em> about every case
 *              in the product, including cases somebody had linked from the doc page an hour
 *              earlier.
 *
 *              <p>Read from {@code case_doc_link} — a read model this module owns, mirrored out of
 *              {@code doc_link} by {@code DocumentationFacts} on the
 *              {@code documentation.links_changed} event and on the hourly recompute. Nothing on
 *              this request path names a {@code documentation} table, which is the property the
 *              module rule was ever about.
 *
 *              <p><strong>Detail only</strong>, on {@code flake}'s and {@code attachments}' terms:
 *              the grid draws what a case <em>is</em>, and traceability is read while deciding what
 *              to do about one case. It lands in the version snapshot with them, and here that is
 *              closer to right than merely accepted — a v7 that records which statements the case
 *              claimed to verify when somebody saved it is describing the version honestly. The
 *              content hash is computed over {@link CaseEdit}, so a link created on a doc page
 *              cannot by itself write a version of a case nobody touched.
 * @param attachments files on the case's <strong>definition</strong> — never null, empty being the
 *              ordinary state. Detail only, for {@link #flake}'s reason: the grid draws what a case
 *              <em>is</em>, and a per-row join for a paperclip count is a column nobody asked for.
 *
 *              <p>Unlike {@code flake} there is no third state. No {@code case_flake} row means
 *              "nobody has looked"; no {@code attachment} row means nothing is attached, and those
 *              are not two claims. A nullable list would give the SPA two spellings of nothing to
 *              tell apart, which is exactly the defect §121 phase 4 found one field up.
 *
 *              <p>It lands in the version snapshot on the same terms {@code flake} does, and here
 *              that is arguably <em>right</em> rather than merely accepted: an attachment is
 *              authored content, so a v7 that records which files the case carried when somebody
 *              saved it is describing the version honestly. The content hash is still computed over
 *              {@link CaseEdit}, so attaching a file does not by itself write a version.
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
        UserRef owner,
        // WHO WROTE THE CASE. Required by TestCaseSummary, which `TestCase` is an allOf of, and
        // MISSING HERE until 2026-08-02 — §66 put it on the wire for the grid and this view, which
        // shares the contract schema, kept omitting the key.
        //
        // It was not a cosmetic omission WHILE THE GATE READ IT: the case detail is the only screen
        // with a delete affordance, its gate was `case:delete` qualified OWN, and the SPA passes
        // `data.createdBy?.id ?? null`. An absent key reads as null, null is a SUPPLIED author that
        // matches nobody, and the gate therefore refused a Test Engineer their own case — the exact
        // inverse of what §66 shipped. Nothing caught it: `tsc` allows optional chaining on a
        // required field, and the page's own test supplies a fixture that has the key.
        //
        // V51 (§207) removed the qualifier, so that defect can no longer occur and the key is no
        // longer load-bearing for authorization. It stays REQUIRED anyway, for two reasons that
        // outlive the gate: the contract declares it on TestCaseSummary and this view shares that
        // schema, and the author is a display field in its own right — not the owner, and the two
        // diverge the moment a case is reassigned.
        UserRef createdBy,
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
        List<RaisedSignalView> healthSignals,
        // Carried from the summary, in the contract's position. It lands in the version snapshot
        // (see `flake` below for the full account) on the same terms `healthSignals` has since M1:
        // a v7 row records the case's last result as it stood when somebody saved. Derived, not
        // authored, and the content hash is computed over CaseEdit — so a recompute that changes
        // this value still cannot cause a save that changed nothing to write a version.
        LastResultView lastResult,
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
        ImportedFromView importedFrom,
        List<TestStepView> steps,
        List<CaseConditionRefView> preconditions,
        List<CaseConditionRefView> postconditions,
        // The inherited attachments this case has TURNED OFF (§187). Beside `preconditions` rather
        // than flagged inside it, because this record IS the version snapshot and CaseFacts pins a
        // run's checklist out of that snapshot's `preconditions` — see CaseConditions.Resolved for
        // the whole argument. They land in the snapshot too, which is correct and, unlike
        // `flake`/`lastResult` above, they are authored: the ids are in CaseEdit and therefore in
        // CaseVersions#hash, so turning a rule off writes a version that says so.
        List<CaseConditionRefView> declinedPreconditions,
        List<CaseConditionRefView> declinedPostconditions,
        List<DocLinkView> relatedDocuments,
        int currentVersionNo,
        long lockVersion,
        CaseFlakeView flake,
        List<CaseAttachmentView> attachments) {

    static TestCaseDetailView of(
            TestCase row,
            TestCaseSummaryView summary,
            List<String> automationTags,
            List<UUID> classificationValueIds,
            List<TestStepView> steps,
            CaseConditions.Resolved conditions,
            CaseFlakeView flake,
            List<CaseAttachmentView> attachments,
            List<DocLinkView> relatedDocuments) {

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
                summary.lastResult(),
                summary.updatedAt(),
                row.getDescription(),
                row.getObjective(),
                row.getExpectedOutcome(),
                row.getSeverity(),
                row.getEstimatedDurationSec(),
                automationTags,
                row.getAutomationKey(),
                row.getNotAutomatableReason(),
                // The zero-row condition, not a format check — see the field's javadoc. `steps`
                // is the list this same call is about to serve, so the response cannot disagree
                // with itself about whether the case has a procedure.
                steps.isEmpty() ? row.getGherkinSource() : null,
                ImportedFromView.of(row),
                steps,
                conditions.preconditions(),
                conditions.postconditions(),
                conditions.declinedPreconditions(),
                conditions.declinedPostconditions(),
                relatedDocuments,
                row.getCurrentVersionNo(),
                row.getLockVersion(),
                flake,
                attachments);
    }
}
