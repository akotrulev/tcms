package com.tcms.repository;

import java.util.List;

/**
 * What {@code migrate-gherkin-source} did, or would do — one entry per case that still holds its
 * procedure in {@code test_case.gherkin_source}.
 *
 * <h2>Why the report is a value and not a log</h2>
 *
 * <p>Initiative {@code 002-} drops the column, and its gate is <em>"the backfill has been run on
 * every deployed instance and its report shows zero cases left on the blob"</em>
 * ({@code data-model.md} §4). A gate somebody has to read out of a log is a gate somebody
 * misreads, so the count that decides it — {@link #stillOnTheBlob()} — is computed here and is
 * also what the command's exit code is derived from.
 *
 * @param dryRun whether anything was written. The entries are identical either way, which is the
 *               property that makes a dry run worth running: an operator sees the same list before
 *               and after, and a difference between the two is a real change of state rather than a
 *               difference in what the two modes report.
 */
public record GherkinBackfillReport(boolean dryRun, List<Entry> entries) {

    /** One case's fate. */
    public enum Outcome {

        /** Parsed, and its rows were written — or would have been, under {@code --dry-run}. */
        MIGRATED,

        /**
         * It already has {@code test_step} rows, so there is nothing to do.
         *
         * <p>Not filtered out of the query, deliberately: a replay that silently listed nothing
         * would be indistinguishable from a run against the wrong company. This is the outcome
         * every case takes on the second run, and it is <strong>not</strong> counted against
         * {@link #stillOnTheBlob()} — its procedure is in {@code test_step}, whatever the column
         * still says.
         */
        ALREADY_MIGRATED,

        /**
         * It was left alone, and a human has to act. The blob is unchanged and the SPA's
         * not-yet-migrated fallback still shows it, so nothing is lost — but {@code 002-} cannot
         * drop the column while any of these exist.
         */
        NOT_MIGRATED
    }

    /**
     * @param steps  how many rows were written, or would be. Zero for the two other outcomes.
     * @param reason why, for anything that is not {@link Outcome#MIGRATED}; null otherwise.
     * @param note   something the operator should know about a case that <em>was</em> migrated —
     *               today only {@code background-skipped}. Carried beside the outcome rather than
     *               turning the case into a refusal, because a scenario with a {@code Background:}
     *               migrates perfectly well and only its preconditions are left behind
     *               ({@code data-model.md} §4). Null when there is nothing to say.
     */
    public record Entry(String caseKey, Outcome outcome, int steps, String reason, String note) {

        static Entry migrated(String caseKey, int steps, String note) {
            return new Entry(caseKey, Outcome.MIGRATED, steps, null, note);
        }

        static Entry alreadyMigrated(String caseKey) {
            return new Entry(caseKey, Outcome.ALREADY_MIGRATED, 0, "already has steps", null);
        }

        static Entry notMigrated(String caseKey, String reason) {
            return new Entry(caseKey, Outcome.NOT_MIGRATED, 0, reason, null);
        }
    }

    public long countOf(Outcome outcome) {
        return entries.stream().filter(entry -> entry.outcome() == outcome).count();
    }

    /**
     * The cases whose procedure is still <em>only</em> in the blob after this run — {@code 002-}'s
     * gate, as a number.
     *
     * <p>Under {@code --dry-run} it is what a real run would leave, which is the whole reason to
     * do a dry run first.
     */
    public long stillOnTheBlob() {
        return countOf(Outcome.NOT_MIGRATED);
    }
}
