package com.tcms.execution;

import java.util.List;
import java.util.UUID;

/**
 * {@code PlanSection} on the wire — one named sub-feature of a plan, with what defines it and how
 * much that currently comes to.
 *
 * <h2>The references are served, not just the count</h2>
 *
 * <p>{@code caseIds}, {@code suiteIds} and {@code tags} are what the author wrote; {@code caseCount}
 * is what they resolve to today. Both are needed and neither substitutes for the other: a section
 * showing only its count cannot be edited (§68's rule — a field a {@code PATCH} accepts must be
 * readable from the {@code GET} that precedes it), and one showing only its references makes the
 * reader expand a tag in their head to find out whether the section holds anything.
 *
 * <p><strong>Which is also what makes the live-selector behaviour visible.</strong> A section whose
 * only reference is the tag {@code smoke} and whose count is 12 says both halves of D7′ on one row:
 * this is the rule, and this is what the rule catches right now.
 *
 * @param position  author order, contiguous from 0 as served
 * @param caseCount the deduped live cases this section resolves to. <strong>The sections' counts do
 *                  not sum to the plan's</strong> — a case reachable two ways is one case in the
 *                  plan and one in each section that reaches it
 */
public record PlanSectionView(
        String name,
        int position,
        List<UUID> caseIds,
        List<UUID> suiteIds,
        List<String> tags,
        long caseCount) {
}
