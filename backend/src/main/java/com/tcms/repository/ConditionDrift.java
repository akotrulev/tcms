package com.tcms.repository;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Derives {@link HealthSignal#CONDITION_DRIFT} — <em>"a referenced shared condition changed since
 * the snapshot"</em> (plan §6o's signal table, §6f's mechanism).
 *
 * <h2>It is deliberately not called {@code ConditionFacts}</h2>
 *
 * <p>The {@code *Facts} suffix means one thing in this module and it is worth keeping it meaning
 * that: rows <strong>another module owns</strong>, read through exactly one file. {@code
 * ExecutionFacts} is the strict case — ArchUnit <em>forbids</em> the {@code repository} ↔ {@code
 * execution} edge, so the one-file rule is what keeps the crossing findable — and {@code
 * ApprovalFacts} is the weaker one, a convention over an edge toward {@code project} that is
 * permitted. Every table named here belongs to {@code repository} itself: {@code test_case},
 * {@code test_case_version} and {@code condition} are all this module's own. A third {@code
 * *Facts} would tell a reader there is a third crossing to account for, and there is not; §157
 * had to spend a paragraph saying the first two are not the same kind, which is the warning that
 * a fourth reading of one suffix is one too many.
 *
 * <p>Native SQL all the same, for the reason the other two give: the answer is never needed as a
 * value. One boolean per live case per company on an hourly job is a fact to fold into the
 * statement that consumes it, not a row per case to move through the application — and the
 * comparison is between a JSON document and a table, which is a thing the database does in one
 * pass and Java does in N.
 */
@Component
class ConditionDrift {

    private final EntityManager entities;

    ConditionDrift(EntityManager entities) {
        this.entities = entities;
    }

    /**
     * Re-derive {@link HealthSignal#CONDITION_DRIFT} for every live case in the currently-open
     * company, in one statement.
     *
     * <h2>What drifts, and against what</h2>
     *
     * <p>A case's conditions are <strong>references while it is live and a copy once it is
     * versioned</strong> — that split is the whole of §6f. {@code test_case_version.snapshot} holds
     * the case <em>as the API served it</em>, with both condition lists resolved
     * ({@code CaseVersions}' header: "a shared condition edited today must not change what a tester
     * was told to do last March"), while {@code GET /cases/&#123;id&#125;} resolves the live library
     * row every time it is asked. So the two can disagree, and this signal is the one place that
     * notices: <strong>for every condition named in the newest snapshot, is the library entry it
     * names still saying the same thing?</strong>
     *
     * <p>The newest snapshot and not the approved one. An approved version is a narrower reference
     * point that most projects do not have — §6g's review apparatus is opt-in and off by default,
     * so pinning to it would report a confident zero for every project that uses the status ladder
     * without reviewers, which is the defect {@code STALE_AUTOMATION} spent from {@code V1} to M3
     * in. "Since the snapshot" is also what plan §6o says, and the snapshot a case has is the one
     * its last version wrote.
     *
     * <h2>The comparison is content, and the timestamp spelling cannot self-heal</h2>
     *
     * <p>The short version of this statement — {@code condition.updated_at > version.created_at} —
     * is one join shorter, reads as obviously equivalent, and is wrong in a way that matters for an
     * ERROR-severity signal: <strong>it can never go false.</strong> Editing a condition and
     * changing it straight back leaves {@code updated_at} moved for ever, so the case would carry a
     * drift flag until somebody happened to save it, and no action available to the person looking
     * at the flag would clear it. That is the "no button for lying" rule inverted — a signal you
     * cannot fix by fixing its cause is exactly the sync-job rot plan §6o refuses to build ({@code
     * case_health}'s table comment: "they self-heal when the underlying condition is fixed").
     * Comparing the text heals in both directions, and it also does not fire on a save that changed
     * nothing.
     *
     * <h2>A deleted entry is the loudest drift there is, which is why the join is LEFT</h2>
     *
     * <p>{@code CaseConditions.forCase} <em>drops</em> an attachment whose entry no longer resolves,
     * and {@code ConditionRepository}'s every read filters {@code deleted_at IS NULL}. So
     * soft-deleting a library condition silently removes a step from every live case referencing it,
     * while the snapshot still records the step a tester was told to perform. An inner join here
     * would make that case — the one where the case lost content outright — the one that reports
     * clean, because the row it would have compared against is not selectable. {@code lib.id IS
     * NULL} is therefore a term in the expression and not an accident of the join type.
     *
     * <h2>Title as well as body</h2>
     *
     * <p>Both are rendered: {@code CaseConditionRefView} carries the title because the contract
     * requires one on every condition reference, and the case detail shows it above the steps.
     * Renaming "Log in as standard user" to "Log in as admin" without touching the body changes what
     * the case tells a tester to do quite as much as editing the steps does, and a comparison over
     * {@code body} alone would call it clean.
     *
     * <h2>What this does NOT report, stated because the omission is deliberate</h2>
     *
     * <p><strong>Attachment changes.</strong> Whether a condition is attached at all is authored
     * content — {@code CaseEdit} carries {@code preconditionIds} and {@code postconditionIds}, so
     * attaching or detaching one moves {@code CaseVersions.hash} and writes a new version, whose
     * snapshot is rendered from the live library. The case's own arm therefore cannot drift by
     * attachment; it re-baselines. The <em>inherited</em> arm can — a suite-level attach or detach
     * writes no case version — and that is a gap this signal leaves open rather than closes,
     * because "the set of conditions this case inherits has changed" is a different question from
     * "an entry changed", needs the suite chain walked per case, and would fold a second predicate
     * into a signal whose name says content.
     *
     * <p><strong>Cases with no version row at all.</strong> Every case that predates §6j's write
     * path has none ({@code CaseVersions}' "the v1 gap is real"), so there is no snapshot to have
     * drifted from and the answer is false. That is the honest reading and not a hole: the signal
     * asks whether the case has changed since it was recorded, and a case nothing recorded has no
     * such moment.
     *
     * <h2>{@code ON CONFLICT} names two columns</h2>
     *
     * <p>{@code condition_drift} and {@code computed_at}. The row is shared with three other
     * writers in two modules and the columns are not — {@code ExecutionFacts} states the rule and
     * this is now the fourth statement it binds.
     *
     * @param now stamped into {@code computed_at} — what tells the dashboard "nobody has looked"
     *            apart from "nothing is wrong" (see {@code CaseHealth}'s header).
     * @return how many cases were re-derived, for the caller's log line.
     */
    int recomputeConditionDrift(Instant now) {
        return entities.createNativeQuery("""
                INSERT INTO case_health (
                        case_id, company_id, project_id,
                        condition_drift, computed_at)
                SELECT c.id, c.company_id, c.project_id,
                       coalesce(drift.drifted, false),
                       :now
                  FROM test_case c
                  LEFT JOIN LATERAL (
                           SELECT v.snapshot
                             FROM test_case_version v
                            WHERE v.case_id = c.id
                            ORDER BY v.version_no DESC
                            LIMIT 1
                       ) newest ON true
                  LEFT JOIN LATERAL (
                           SELECT bool_or(
                                      lib.id IS NULL
                                      OR lib.title IS DISTINCT FROM snap.entry ->> 'title'
                                      OR lib.body  IS DISTINCT FROM snap.entry ->  'body'
                                  ) AS drifted
                             FROM jsonb_array_elements(
                                      coalesce(newest.snapshot -> 'preconditions', '[]'::jsonb)
                                   || coalesce(newest.snapshot -> 'postconditions', '[]'::jsonb)
                                  ) AS snap(entry)
                             LEFT JOIN condition lib
                                    ON lib.id = CAST(snap.entry ->> 'conditionId' AS uuid)
                                   AND lib.deleted_at IS NULL
                            WHERE snap.entry ->> 'conditionId' IS NOT NULL
                       ) drift ON true
                 WHERE c.deleted_at IS NULL
                    ON CONFLICT (case_id) DO UPDATE
                   SET condition_drift = EXCLUDED.condition_drift,
                       computed_at     = EXCLUDED.computed_at
                """)
                .setParameter("now", now)
                .executeUpdate();
    }
}
