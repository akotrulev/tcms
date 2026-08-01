package com.tcms.work;

import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code WorkItemInput} on the wire.
 *
 * <p>A record with exactly the contract's six properties and no more. A field the server accepts and
 * discards is a save the user watched succeed that stored nothing — the refusal §29 records and
 * {@code PlanWriteRequest} repeats.
 *
 * <p><strong>No {@code kind} and no {@code status}</strong>, and the two omissions are load-bearing
 * rather than tidy:
 *
 * <ul>
 *   <li>{@code kind} would let a client POST {@code EXECUTION} and so <em>materialise a derived
 *       item</em> — the single thing §6l's whole design exists to prevent. A stored card claiming to
 *       be an execution owns a status the execution cannot see, and the two diverge on the first
 *       result anybody records.</li>
 *   <li>{@code status} would allow "create it in Done": a card that reached the last column without
 *       any move being made. {@link WorkItem#create} starts every task in {@code TODO}.</li>
 * </ul>
 *
 * <p>{@code projectId} is in the body rather than the path because the operation is
 * {@code POST /work-items} — the board is an instance-scoped surface serving §6l's eventual
 * cross-project personal view, and the project is a property of the task rather than of the
 * endpoint.
 */
public record WorkItemWriteRequest(
        UUID projectId,
        String title,
        String description,
        Priority priority,
        LocalDate dueDate,
        UUID assigneeId) {
}
