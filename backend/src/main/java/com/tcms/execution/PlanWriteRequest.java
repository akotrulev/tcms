package com.tcms.execution;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * {@code TestPlanInput} on the wire.
 *
 * <p>A record with exactly the contract's seven properties and no more. Fields the contract does not
 * declare are absent rather than accepted-and-dropped, which is the refusal {@code repository}'s
 * slice 7 made for {@code TestCaseInput}'s six unsupported inputs and the same one §29 records: a
 * field the server reads and discards is a save the user watched succeed and that stored nothing.
 *
 * <p><strong>No {@code status} and no {@code key}.</strong> Status is the review workflow's
 * ({@link TestPlan#create} says why a create is DRAFT), and the key is allocated, never supplied —
 * {@code TP-n} from the company's counter (§6t).
 *
 * <p>~~No criteria: they belong to {@code plan:edit}, which is a different permission and a
 * different slice.~~ → <strong>superseded by B9.</strong> The permission argument does not hold —
 * {@code plan:create} and {@code plan:edit} are held by exactly the same roles, and a caller who
 * may create a plan may immediately edit it — so what the narrowness bought was a second request
 * rather than a boundary. What it cost is stated in the audit: <em>clone was more expressive than
 * create</em>. {@code PlanCloneService} reaches {@link PlanCreateService#write} with sections, a
 * tag arm, prose and both criteria, so the server has always been able to make a complete plan in
 * one transaction; only this record could not ask for it.
 *
 * <p>The <em>flow</em> the old sentence describes is still the usual one — a plan is named before
 * anybody knows what would make it releasable — and it remains what the SPA does, because a form
 * that asked for exit criteria on the New Plan dialog would be asking the wrong question at the
 * wrong moment. Every one of the six added fields is optional, so that flow is unchanged; what
 * changed is that an importer, a template or an agent can state the whole plan at once.
 *
 * <p>{@code milestoneDate} is a {@link LocalDate} rather than an {@code Instant}, matching the
 * column and the contract's {@code format: date}: a milestone is a day the whole project agrees on,
 * and giving it an instant makes it a different day either side of a time zone.
 */
public record PlanWriteRequest(
        String name,
        String description,
        String milestoneName,
        LocalDate milestoneDate,
        List<UUID> suiteIds,
        List<UUID> caseIds,

        // The six B9 added, each with exactly the meaning it has on PlanUpdateRequest — the same
        // validation runs over both, so a rule that held on the edit holds here without being
        // restated. Absent (null) is "not supplied" on every one of them, which on a create is the
        // same thing as empty: there is nothing stored to leave alone.
        List<String> tags,
        List<PlanSectionInput> sections,
        String scopeText,
        String outOfScopeText,
        List<CriterionInput> entryCriteria,
        List<CriterionInput> exitCriteria) {
}
