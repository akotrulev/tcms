package com.tcms.repository;

import java.util.List;
import java.util.UUID;

/**
 * {@code FeatureImportPlan} — what an import <em>would</em> do, or what it just did.
 *
 * <h2>One shape for both modes, and {@code applied} is the only field that moves</h2>
 *
 * <p>{@code PREVIEW} and {@code APPLY} answer this identical record. That is what makes the page a
 * single component rather than two that drift, and it is what makes the preview trustworthy: the
 * plan is recomputed inside the {@code APPLY} transaction from the same code, so what the caller
 * approved is what runs. On a clean run the only difference between the two answers is
 * {@link #applied} and the {@link ImportRow#caseKey()} of the rows that created a case.
 *
 * <h2>A refused row never fails the request</h2>
 *
 * <p>{@code REFUSE} and {@code SKIP} are outcomes, not errors — one {@code Scenario Outline} in a
 * folder of forty files refuses that scenario and imports the rest. The whole request rolls back
 * for exactly one class of failure: a constraint violation, which means a concurrent writer took a
 * key between the plan and the insert. That is the {@code 409} in {@code api-surface.md} §5.4, and
 * it is all-or-nothing because half an imported folder is a state nobody can reason about.
 *
 * @param mode      echoes the request
 * @param applied   {@code false} for {@code PREVIEW}; {@code true} once the {@code APPLY}
 *                  transaction has committed the writes this plan describes
 * @param scenarios one row per scenario, ordered by file order and then by line — plus one row per
 *                  file that did not parse, with {@code scenario} null
 * @param suites    one entry per distinct {@code Feature:} name. Empty when the request named a
 *                  {@code suiteId}: then nothing is matched and nothing is created
 * @param conditions one entry per DISTINCT {@code Background:} step across the whole request
 *                  (amendment AM1) — the same step text in two files is one library entry
 * @param orphans   cases filed in the target suites whose automation key is not in the files.
 *                  Listed so a human can decide; never modified, never deleted
 * @param totals    the four counts, so the page can say "12 create, 3 update" without re-tallying
 */
public record FeatureImportPlan(
        FeatureImportRequest.Mode mode,
        boolean applied,
        List<ImportRow> scenarios,
        List<SuiteAction> suites,
        List<ConditionAction> conditions,
        List<Orphan> orphans,
        ImportTotals totals) {

    /**
     * {@code ImportRow} — one scenario's verdict.
     *
     * @param path          the file the caller supplied it in
     * @param line          1-based line of the {@code Scenario:} keyword; null for a whole-file
     *                      refusal, where there is no scenario to point at
     * @param feature       the {@code Feature:} name, or null when the file did not parse
     * @param scenario      the {@code Scenario:} name, or null for a whole-file refusal
     * @param automationKey {@code "<Feature>#<Scenario>"}, spelled by
     *                      {@link com.tcms.api.AutomationKeys} and by nothing else
     * @param action        DEC-1's verdict
     * @param reason        one of the fixed sentences in {@code api-surface.md} §5.4, or null when
     *                      the action needs no explanation ({@code CREATE}, {@code UPDATE}).
     *                      <strong>Clients may match on these strings</strong> — they are the
     *                      stability a {@code type} slug would carry on an error, on an outcome
     *                      that is not one
     * @param caseKey       {@code TC-n}. Present in both modes for a row that names an existing
     *                      case; filled after {@code APPLY} for a row that created one
     * @param preconditions the titles of the {@code PRE} conditions this case carries after the row
     *                      is applied, in position order: the import-owned ones from the Background
     *                      first, then (on {@code UPDATE}) the case's existing human-attached ones.
     *                      Empty for a feature with no {@code Background:}
     */
    public record ImportRow(
            String path,
            Integer line,
            String feature,
            String scenario,
            String automationKey,
            Action action,
            String reason,
            String caseKey,
            List<String> preconditions) {
    }

    /** DEC-1's four verdicts. {@code REFUSE} is a row, never a status code. */
    public enum Action {
        CREATE,
        UPDATE,
        SKIP,
        REFUSE
    }

    /**
     * {@code SuiteAction} — what happens to the suite one {@code Feature:} maps to.
     *
     * @param suiteId the matched or created suite; null on {@code REFUSE}, and null on
     *                {@code CREATE} until the {@code APPLY} has written it
     */
    public record SuiteAction(String name, SuiteVerdict action, UUID suiteId, String reason) {
    }

    /** Matched, created, or refused because the answer is ambiguous or the caller may not. */
    public enum SuiteVerdict {
        CREATE,
        REUSE,
        REFUSE
    }

    /**
     * {@code ConditionAction} — what happens to the library entry for one {@code Background:} step.
     *
     * <p>{@code UNCHANGED} is the common answer on a re-import and it is the whole point of hashing
     * the step text: the key finds the row, nothing is written, and the attachment order is the
     * file's rather than the library's.
     *
     * @param importedKey {@code "bg:"} plus 16 hex of SHA-256 over the normalised step
     * @param title       the step text with its keyword, truncated to 150 characters if it is longer
     */
    public record ConditionAction(
            String importedKey,
            String title,
            ConditionVerdict action,
            UUID conditionId,
            String reason) {
    }

    /**
     * {@code UPDATE} happens only when the title changed — a hash key means the body cannot have.
     */
    public enum ConditionVerdict {
        CREATE,
        UPDATE,
        UNCHANGED,
        REFUSE
    }

    /** {@code Orphan} — a keyed case in the target suites that these files do not name. */
    public record Orphan(String caseKey, String automationKey) {
    }

    /** {@code ImportTotals} — the four counts, one per {@link Action}. */
    public record ImportTotals(int create, int update, int skip, int refuse) {
    }
}
