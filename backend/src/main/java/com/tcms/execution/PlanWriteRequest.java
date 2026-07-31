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
 * <p><strong>No {@code status}, no criteria and no {@code key}.</strong> Status is the review
 * workflow's ({@link TestPlan#create} says why a create is DRAFT); criteria belong to
 * {@code plan:edit}, which is a different permission and a different slice; and the key is
 * allocated, never supplied — {@code TP-n} from the company's counter (§6t).
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
        List<UUID> caseIds) {
}
