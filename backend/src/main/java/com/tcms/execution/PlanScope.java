package com.tcms.execution;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * A plan's scope, normalized — <strong>the one definition of what "the same scope" means</strong>.
 *
 * <p>Three places ask that question and they must agree: the response the editor reads, the snapshot
 * {@link PlanVersions} hashes, and the set {@link PlanUpdateService} writes. A second normalization
 * anywhere in that triangle is the defect {@code repository.CaseVersions} names — a save that
 * changed nothing writes a version, because the two sides spelled the same scope differently.
 *
 * <h2>Sorted, because the storage order does not exist</h2>
 *
 * <p>{@code plan_scope_item} has no ordinal column and never needed one: a scope is a <em>set</em>,
 * and a folder listed before another does not mean anything. So a read returns the rows in whatever
 * order the plan chose that day — which makes insertion order unrecoverable and any hash over it a
 * value that can change without the plan changing.
 *
 * <p>Sorting by id rather than by name for the same reason {@code ltree} labels are derived from ids
 * (see {@code project.ClassificationValue}): a name is renameable, and the ordering of a hash input
 * must not move when somebody edits a folder in another screen.
 *
 * @param suiteIds the folders in scope, each meaning <em>the folder and everything beneath it, when
 *                 the question is asked</em> — see {@link PlanScopeItem}
 * @param caseIds  the individually named cases
 */
record PlanScope(List<UUID> suiteIds, List<UUID> caseIds) {

    /** The empty scope — a plan somebody has named and not yet filled, which is an ordinary state. */
    static PlanScope empty() {
        return new PlanScope(List.of(), List.of());
    }

    /**
     * Normalizes two raw id lists: nulls dropped, duplicates collapsed, both sorted.
     *
     * <p>Duplicates are collapsed rather than refused for {@link PlanEdit#distinct}'s reason — two
     * identical scope items are two rows saying one thing, and the expansion already dedupes what it
     * counts.
     */
    static PlanScope of(Collection<UUID> suiteIds, Collection<UUID> caseIds) {
        return new PlanScope(sorted(suiteIds), sorted(caseIds));
    }

    /**
     * The scope of one plan, read off its stored rows.
     *
     * <p>{@code ck_plan_scope_target} guarantees exactly one of the two ids is set, so the branch
     * below is total — a row that is neither is unreachable through the constraint and through
     * {@link PlanScopeItem}'s factories, which check the same condition earlier so the failure names
     * the caller.
     */
    static PlanScope ofItems(Collection<PlanScopeItem> items) {
        List<UUID> suiteIds = new ArrayList<>();
        List<UUID> caseIds = new ArrayList<>();
        for (PlanScopeItem item : items) {
            if (item.getSuiteId() != null) {
                suiteIds.add(item.getSuiteId());
            } else {
                caseIds.add(item.getCaseId());
            }
        }
        return of(suiteIds, caseIds);
    }

    /** How many lines this scope writes — not how many cases it covers, which is an expansion. */
    int itemCount() {
        return suiteIds.size() + caseIds.size();
    }

    private static List<UUID> sorted(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream().filter(java.util.Objects::nonNull).distinct().sorted().toList();
    }
}
