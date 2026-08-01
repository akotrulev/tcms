package com.tcms.work;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * {@code WorkItem} in {@code docs/specs/openapi.yaml} — one board card, stored or derived.
 *
 * <h2>One shape for both kinds, which is the point rather than a compromise</h2>
 *
 * <p>§6l models {@code WorkItem} as "an abstract view over Task | DerivedItem", and the board
 * renders one grid. Two wire shapes would push the union onto the client, where every consumer would
 * re-derive which fields it may read — and the first one to get it wrong renders a derived card with
 * a stored card's affordances, which is exactly the divergence the whole design prevents.
 *
 * <p>{@link #derived} is what tells them apart, and {@link #allowedTransitions} is what the SPA
 * actually acts on: a card is draggable to the columns in that list and nowhere else. A derived card
 * whose source offers no board action carries an empty list, and the board disables every move
 * button with a reason rather than hiding them (§31's disable-don't-hide).
 *
 * @param id       for a stored task, its UUID as text. For a derived card, a <strong>synthetic
 *                 composite</strong> — {@code execution:{uuid}}, {@code case:{uuid}} — because there
 *                 is no row to have an id. Typed as {@code string} in the contract for that reason,
 *                 not as {@code uuid}: declaring a format the derived half cannot satisfy would make
 *                 every generated client's validation reject two thirds of the board.
 * @param sourceType null for a task. {@code EXECUTION} or {@code CASE} for a derived card, naming
 *                 what it mirrors.
 * @param linkedKey the display key of the thing this card is about ({@code TC-42}), so a card can
 *                 link somewhere. Null for a task, which is about nothing but itself.
 * @param overdue  computed from {@link #dueDate} at read time, never stored — §6l is explicit that
 *                 overdue and due-soon are derived. A stored flag needs a job to flip it and is
 *                 wrong for a day at a time.
 * @param allowedTransitions the columns this card may legally move to. See
 *                 {@code BoardService#transitionsFor} for how each kind's list is decided.
 */
public record WorkItemView(
        String id,
        boolean derived,
        String sourceType,
        UUID sourceId,
        String kind,
        String title,
        String description,
        WorkItemStatus status,
        Priority priority,
        LocalDate dueDate,
        boolean overdue,
        UserRefView assignee,
        UUID projectId,
        String projectKey,
        String linkedKey,
        List<WorkItemStatus> allowedTransitions) {
}
