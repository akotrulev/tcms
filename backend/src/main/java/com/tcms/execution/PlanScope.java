package com.tcms.execution;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A plan's scope, normalized — <strong>the one definition of what "the same scope" means</strong>.
 *
 * <p>Three places ask that question and they must agree: the response the editor reads, the snapshot
 * {@link PlanVersions} hashes, and the set {@link PlanUpdateService} writes. A second normalization
 * anywhere in that triangle is the defect {@code repository.CaseVersions} names — a save that
 * changed nothing writes a version, because the two sides spelled the same scope differently.
 *
 * <h2>Three arms and two levels, since §97</h2>
 *
 * <p>A scope line names a <strong>suite</strong>, a <strong>case</strong> or a <strong>tag</strong>
 * (D8), and it sits either in the unsectioned group — every plan written before §97 has only that —
 * or in a named sub-feature {@link Section}. The two levels hold the same three kinds of reference
 * because a section is a subdivision of the plan's cases and not a different sort of thing; a case
 * belongs to exactly one of them, so there is no overlap to reconcile and no second way to write the
 * same membership.
 *
 * <p><strong>A section is a live selector, not a snapshot</strong> (D7′). What is stored is the
 * reference; what it holds is a query, re-resolved on every read, exactly as a suite's membership
 * has been since {@code V31}. TestNG's {@code <test>} block is the shape that decided it.
 *
 * <h2>Sorted, because the storage order does not exist</h2>
 *
 * <p>Within a group, a scope is a <em>set</em>: a folder listed before another does not mean
 * anything, so a read returns the rows in whatever order the plan chose that day — which makes
 * insertion order unrecoverable and any hash over it a value that can change without the plan
 * changing. Ids sort by id rather than by name for the same reason {@code ltree} labels are derived
 * from ids (see {@code project.ClassificationValue}): a name is renameable, and the ordering of a
 * hash input must not move when somebody edits a folder in another screen.
 *
 * <p><strong>Sections are the exception and they are ordered</strong>, because their order is
 * authored — "Create an object", then "Edit", then "Navigation" is a narrative somebody arranged,
 * and re-sorting it alphabetically on every read would rearrange their document. So {@code position}
 * is part of the value and part of the hash: moving a section is a change to the plan, which is what
 * a reader would expect the version history to say.
 *
 * @param suiteIds the unsectioned folders, each meaning <em>the folder and everything beneath it,
 *                 when the question is asked</em> — see {@link PlanScopeItem}
 * @param caseIds  the unsectioned individually-named cases
 * @param tags     the unsectioned tags, matched against {@code case_tag} exactly
 * @param sections the named sub-features, in author order
 */
record PlanScope(
        List<UUID> suiteIds, List<UUID> caseIds, List<String> tags, List<Section> sections) {

    /**
     * One named sub-feature of a plan.
     *
     * <p>Plan-local: no id, because it has no identity outside the plan and nothing may reference
     * one. That is also why an edit replaces the whole section list rather than reconciling it —
     * there is nothing to match a partial update against.
     *
     * @param position author order, contiguous from 0 as served and assigned by the server from the
     *                 order submitted rather than trusted from the client
     */
    record Section(String name, int position, List<UUID> suiteIds, List<UUID> caseIds,
                   List<String> tags) {

        /** Whether this section holds no references at all — legal, and kept. See {@link #ofItems}. */
        boolean isEmpty() {
            return suiteIds.isEmpty() && caseIds.isEmpty() && tags.isEmpty();
        }
    }

    /** The empty scope — a plan somebody has named and not yet filled, which is an ordinary state. */
    static PlanScope empty() {
        return new PlanScope(List.of(), List.of(), List.of(), List.of());
    }

    /**
     * Normalizes the unsectioned arms and an already-ordered section list: nulls dropped, duplicates
     * collapsed, ids and tags sorted.
     *
     * <p>Duplicates are collapsed rather than refused for {@link PlanEdit#distinct}'s reason — two
     * identical scope items are two rows saying one thing, and the expansion already dedupes what it
     * counts.
     */
    static PlanScope of(
            Collection<UUID> suiteIds,
            Collection<UUID> caseIds,
            Collection<String> tags,
            List<Section> sections) {

        return new PlanScope(
                sortedIds(suiteIds),
                sortedIds(caseIds),
                sortedTags(tags),
                sections == null ? List.of() : List.copyOf(sections));
    }

    /**
     * The scope of one plan, read off its stored rows.
     *
     * <p><strong>A targetless row is a section held open</strong> and contributes nothing but its
     * name — that is the one row shape {@code ck_plan_scope_target} admits with no target, and
     * reading it as anything else would either drop the empty section from the response (so the
     * heading its author typed disappears on reload) or add a phantom reference to it.
     *
     * <p>Sections come out in {@code ordinal} order and are then renumbered contiguously from 0, so
     * a gap left by an earlier edit is not visible to the client and two plans with the same
     * sections in the same order produce the same value. The ordinal stored on the rows is the
     * server's own, so this cannot disagree with what was written.
     */
    static PlanScope ofItems(Collection<PlanScopeItem> items) {
        List<UUID> suiteIds = new ArrayList<>();
        List<UUID> caseIds = new ArrayList<>();
        List<String> tags = new ArrayList<>();

        // LinkedHashMap keyed on the section name, filled in ordinal order below — a section's name
        // is its identity within the plan, and the rows of one section are not adjacent in the
        // result set.
        Map<String, Draft> drafts = new LinkedHashMap<>();

        List<PlanScopeItem> ordered = new ArrayList<>(items);
        ordered.sort(java.util.Comparator.comparingInt(PlanScopeItem::getOrdinal)
                .thenComparing(item -> item.getSectionName() == null ? "" : item.getSectionName()));

        for (PlanScopeItem item : ordered) {
            if (item.getSectionName() == null) {
                if (item.getSuiteId() != null) {
                    suiteIds.add(item.getSuiteId());
                } else if (item.getCaseId() != null) {
                    caseIds.add(item.getCaseId());
                } else if (item.getTag() != null) {
                    tags.add(item.getTag());
                }
                continue;
            }
            Draft draft = drafts.computeIfAbsent(item.getSectionName(), ignored -> new Draft());
            if (item.getSuiteId() != null) {
                draft.suiteIds.add(item.getSuiteId());
            } else if (item.getCaseId() != null) {
                draft.caseIds.add(item.getCaseId());
            } else if (item.getTag() != null) {
                draft.tags.add(item.getTag());
            }
            // A targetless row adds nothing — its whole contribution is having created the draft.
        }

        List<Section> sections = new ArrayList<>(drafts.size());
        int position = 0;
        for (Map.Entry<String, Draft> entry : drafts.entrySet()) {
            Draft draft = entry.getValue();
            sections.add(new Section(
                    entry.getKey(),
                    position++,
                    sortedIds(draft.suiteIds),
                    sortedIds(draft.caseIds),
                    sortedTags(draft.tags)));
        }
        return of(suiteIds, caseIds, tags, sections);
    }

    /** Mutable accumulator for {@link #ofItems}; never escapes it. */
    private static final class Draft {
        private final List<UUID> suiteIds = new ArrayList<>();
        private final List<UUID> caseIds = new ArrayList<>();
        private final List<String> tags = new ArrayList<>();
    }

    /**
     * How many lines this scope writes — not how many cases it covers, which is an expansion.
     *
     * <p>Sections count their own references and an <em>empty</em> section counts as one, because it
     * writes one row. That keeps {@link PlanEdit#MAX_SCOPE_ITEMS} a bound on what is inserted rather
     * than on what happens to have a target, which is what the cap exists to bound.
     */
    int itemCount() {
        int count = suiteIds.size() + caseIds.size() + tags.size();
        for (Section section : sections) {
            count += section.isEmpty()
                    ? 1
                    : section.suiteIds().size() + section.caseIds().size() + section.tags().size();
        }
        return count;
    }

    /** Every suite id in the scope, both levels — what the live-id check has to cover. */
    List<UUID> allSuiteIds() {
        List<UUID> all = new ArrayList<>(suiteIds);
        sections.forEach(section -> all.addAll(section.suiteIds()));
        return all;
    }

    /** Every case id in the scope, both levels. */
    List<UUID> allCaseIds() {
        List<UUID> all = new ArrayList<>(caseIds);
        sections.forEach(section -> all.addAll(section.caseIds()));
        return all;
    }

    private static List<UUID> sortedIds(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream().filter(Objects::nonNull).distinct().sorted().toList();
    }

    /**
     * Tags sort as text, which is the only order they have — unlike an id, a tag <em>is</em> its own
     * name, so there is no rename that could move it and nothing to prefer a stable key to.
     */
    private static List<String> sortedTags(Collection<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        return tags.stream().filter(Objects::nonNull).distinct().sorted().toList();
    }
}
