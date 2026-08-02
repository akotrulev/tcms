package com.tcms.execution;

import java.time.LocalDate;
import java.util.List;

/**
 * What a plan said it covered, at one moment — the shape {@code test_plan_version.snapshot} holds.
 *
 * <h2>Every field here is authored, which is why one value serves as both snapshot and hash input</h2>
 *
 * <p>{@code repository.CaseVersions} keeps its snapshot and its {@code content_hash} over
 * <em>different</em> shapes, and its header explains at length why: a case's snapshot is the resolved
 * case as the API served it, which carries an owner's display name, health signals derived from run
 * results and an {@code updatedAt} — values that move without anybody editing the case, so hashing
 * them would make a colleague's rename look like an edit.
 *
 * <p><strong>None of that applies here, and the difference is worth stating rather than copying the
 * shape across.</strong> A plan's scope is five authored values: the milestone, the two criteria
 * lists, and the two id sets. Not one of them is derived, resolved or refreshed by anything else, so
 * the snapshot and the thing worth hashing are the same object — and splitting them would mean two
 * definitions of "the scope changed" maintained side by side, which is the defect the split exists
 * to prevent in the first place.
 *
 * <p>Deliberately absent, each for a reason:
 *
 * <ul>
 *   <li><strong>{@code name} and {@code description}</strong> — content, not scope, so a rename
 *       writes no version ({@code TestPlanVersion}, {@code repository.SuiteVersion}).</li>
 *   <li><strong>{@code status}</strong> — {@code TestPlan.applyStatus} already declined to bump the
 *       counter in §73, because approving a plan does not change what is in it.</li>
 *   <li><strong>{@code caseCount}</strong> — an expansion of the suite ids, recomputed on every
 *       read. Storing it would make the hash move when somebody authors a case into a folder this
 *       plan happens to name, which is precisely the every-edit-bumps-every-ancestor spiral
 *       {@code suite_version}'s comment refuses.</li>
 *   <li><strong>{@code configurations}</strong> — a project's list, not a plan's. See
 *       {@link TestPlanVersion}.</li>
 * </ul>
 *
 * <p>The criteria are {@link CriterionView} rather than {@link CriterionInput} because
 * {@link PlanCriteria#parse} is the canonicalizer: it derives an absent label, coerces the value to
 * a double and drops an entry that cannot be evaluated, so two spellings of the same gate — one
 * seeded by hand in SQL, one written through the editor — produce one snapshot and one hash. Their
 * {@code met}/{@code actual} are always null on this path, which is what those fields mean on a plan
 * that is not being evaluated.
 */
record PlanSnapshot(
        String milestoneName,
        LocalDate milestoneDate,
        List<CriterionView> entryCriteria,
        List<CriterionView> exitCriteria,
        PlanScope scope) {

    /**
     * The scope of a plan as it stands, reading the criteria off the entity's own columns.
     *
     * <p>Used by the create, where the two criteria columns are always null, and by the edit
     * <em>after</em> {@code applyEdit} has written them — so in both cases this reads the values
     * that are about to be committed rather than a second rendering of the caller's intent.
     */
    static PlanSnapshot of(TestPlan plan, PlanScope scope, PlanCriteria criteria) {
        return new PlanSnapshot(
                plan.getMilestoneName(),
                plan.getMilestoneDate(),
                criteria.parse(plan.getEntryCriteria()),
                criteria.parse(plan.getExitCriteria()),
                scope);
    }
}
