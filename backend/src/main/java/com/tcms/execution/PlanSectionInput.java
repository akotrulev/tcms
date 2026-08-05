package com.tcms.execution;

import java.util.List;
import java.util.UUID;

/**
 * {@code PlanSectionInput} on the wire — one named sub-feature of a plan's cases, as submitted.
 *
 * <h2>No id, so the whole list is replaced</h2>
 *
 * <p>A section is plan-local: it has no key, no lifecycle, no versions and no existence outside its
 * plan, which is what makes it not a suite. So there is nothing to match a partial update against,
 * and {@code sections} present means <em>this is the section list now</em>. Absent leaves the
 * sections alone; {@code []} removes them all, taking their references out of the plan rather than
 * moving them into the unsectioned group — a reference belongs to exactly one place, and silently
 * relocating it would make "remove this section" mean two different things depending on what was in
 * it.
 *
 * <h2>The three arms are the same three the unsectioned group holds</h2>
 *
 * <p>Cases by id, suites by id, tags by text — because a section is a subdivision of the plan's
 * cases and not a different kind of thing. Each is a <em>reference</em>, re-resolved on every read
 * (D7′), so a section claiming {@code smoke} gains a case the moment somebody tags one, with nothing
 * written to the plan.
 *
 * @param name      required; the section's identity within the plan, so two may not share one
 * @param caseIds   individually named cases
 * @param suiteIds  folders, each meaning the folder and everything beneath it at read time
 * @param tags      §96's {@code case_tag}, matched exactly — the same arm a suite's tag rule uses
 */
public record PlanSectionInput(
        String name,
        List<UUID> caseIds,
        List<UUID> suiteIds,
        List<String> tags) {
}
