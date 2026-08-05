package com.tcms.execution;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * {@code TestPlanUpdateInput} on the wire — everything {@code plan:edit} may change about a plan.
 *
 * <h2>Two more fields than the create takes, and they are the point of the slice</h2>
 *
 * <p>{@code entryCriteria} and {@code exitCriteria} have <strong>never been written by
 * anything</strong>: {@code TestPlan.create} sets both to null and {@code TestPlanInput} has no
 * field for them, on the recorded grounds that <em>"criteria belong to {@code plan:edit}, which is a
 * different permission and a different slice"</em>. This is that slice, so the two arrive here and
 * nowhere else — a create still cannot set them, because a plan is named before anybody knows what
 * would make it releasable.
 *
 * <p><strong>Not {@code status}.</strong> It has its own endpoint and its own two gates
 * ({@link PlanLifecycle}), and a status an ordinary edit could set is an approval anyone grants
 * themselves by saving — {@code repository.CaseUpdateRequest}'s argument, one aggregate over. Nor
 * {@code key} (allocated, §6t), {@code configurations} (a project's list, not a plan's — see
 * {@link PlanService}) or {@code caseCount} (an expansion, derived on every read).
 *
 * <h2>Absent vs null vs empty, which is three answers and not two</h2>
 *
 * <p>Jackson gives an absent field and an explicit {@code null} the same Java value, so this record
 * cannot tell them apart — {@code repository.CaseUpdateRequest} states the same limitation. The
 * convention this codebase settled on in §68 is followed exactly, because a second reading of it
 * would be a save that quietly discards what somebody typed:
 *
 * <ul>
 *   <li><strong>Scalars</strong> — {@code null} means <em>clear it</em>. The SPA submits every field
 *       it renders on every save, so the semantics match the only client.</li>
 *   <li><strong>Lists</strong> — {@code null} means <em>leave it alone</em> and {@code []} means
 *       <em>clear it</em>, which {@code CaseEdit.normalizeClassification} already does for a case's
 *       classification. A list has an empty spelling of its own, so null does not have to carry two
 *       meanings — and this is what keeps a plan whose criteria nobody has ever touched
 *       distinguishable from one whose author deliberately removed them all.</li>
 * </ul>
 *
 * @param name          required, as on the create — a plan with no name is unfindable in the picker
 *                      every run is created from
 * @param suiteIds      the folders in scope. Same validation as the create: an id naming a
 *                      <strong>sibling project's</strong> suite satisfies the foreign key, the
 *                      company column and RLS, and is refused only by {@link PlanEdit}'s live-id set
 * <h2>Two meanings of "scope" arrive on one record, and only one of them is prose</h2>
 *
 * <p>{@code suiteIds}, {@code caseIds}, {@code tags} and {@code sections} are the plan's scope
 * <em>items</em> — which cases it covers. {@code scopeText} and {@code outOfScopeText} are the
 * document sections a test plan calls Scope and Out of scope — what it says it will and will not
 * test, in sentences. They are unrelated, they are adjacent here, and the pair follows the
 * <strong>scalar</strong> half of the convention above while the four above it follow the list half.
 *
 * @param caseIds       likewise for individually named cases
 * @param tags          the unsectioned tag arm (§97 D8) — §96's {@code case_tag}, matched exactly,
 *                      re-resolved on every read, so what it covers can change with nothing written
 *                      to the plan
 * @param sections      the named sub-features. Present replaces the whole list; see
 *                      {@link PlanSectionInput}
 * @param scopeText     prose: what this plan says will be tested. {@code null} clears it
 * @param outOfScopeText prose: what is deliberately not tested here
 * @param entryCriteria what must be true before execution starts
 * @param exitCriteria  what must be true before the release is signed off — the gate §6p exists for
 */
public record PlanUpdateRequest(
        String name,
        String description,
        String milestoneName,
        LocalDate milestoneDate,
        List<UUID> suiteIds,
        List<UUID> caseIds,
        List<String> tags,
        List<PlanSectionInput> sections,
        String scopeText,
        String outOfScopeText,
        List<CriterionInput> entryCriteria,
        List<CriterionInput> exitCriteria) {
}
