package com.tcms.work;

import com.tcms.api.UserRef;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * {@code BoardCard} in {@code docs/specs/openapi.yaml} — one card, row-backed or derived.
 *
 * <p>~~{@code WorkItem} in {@code docs/specs/openapi.yaml} — one board card, stored or derived.~~
 * <strong>→ renamed by 009 module M1</strong>, with the path and the schema: {@code /work-items}
 * named a table that no longer exists (D-a), and the row behind the stored half is an
 * {@code issue} in {@code com.tcms.tracker} (D11).
 *
 * <h2>One shape for both kinds, which is the point rather than a compromise</h2>
 *
 * <p>§6l models this as "an abstract view over Task | DerivedItem", and the board renders one grid.
 * Two wire shapes would push the union onto the client, where every consumer would re-derive which
 * fields it may read — and the first one to get it wrong renders a derived card with a stored card's
 * affordances, which is exactly the divergence the whole design prevents.
 *
 * <p>{@link #derived} is what tells them apart, and {@link #allowedTransitions} is what the SPA
 * actually acts on: a card is draggable to the statuses in that list and nowhere else. A derived card
 * whose source offers no board action carries an empty list, and so does an Epic (D31), and the board
 * disables every move button with a reason rather than hiding them (§31's disable-don't-hide).
 *
 * @param id       for an {@code ISSUE} card, its <strong>key</strong> ({@code ACME-124}) — the thing
 *                 a person types into a commit message and the thing {@code /issues/{key}} takes.
 *                 For a derived card, a <strong>synthetic composite</strong> —
 *                 {@code execution:{uuid}}, {@code case:{uuid}} — because there is no row to have an
 *                 id. Typed as {@code string} in the contract for that reason, not as {@code uuid}:
 *                 declaring a format two thirds of the board cannot satisfy would make every
 *                 generated client's validation reject it.
 * @param sourceType what the card OPENS and what its annotation is keyed on — {@code ISSUE},
 *                 {@code EXECUTION} or {@code CASE}. It is populated for an issue card too, unlike
 *                 the task card it replaced, because {@code derived_item_annotation} is keyed on
 *                 {@code (source_type, source_id)} and the SPA needs both to write one.
 * @param priority {@code tracker}'s four values as a String, for {@link CardStatus}' reason: this
 *                 module may not import that one, and it no longer owns a {@code Priority} of its
 *                 own — {@code work.Priority} retired with {@code work_item}.
 * @param linkedKey the display key of the thing a DERIVED card is about ({@code TC-42}). Null on an
 *                 issue card, whose own {@link #id} is already a key.
 * @param overdue  computed from {@link #dueDate} at read time, never stored — §6l is explicit that
 *                 overdue and due-soon are derived. A stored flag needs a job to flip it and is
 *                 wrong for a day at a time.
 * @param annotation the VIEWER's own note, and never another viewer's (D11, story B5).
 */
public record BoardCardView(
        String id,
        boolean derived,
        String sourceType,
        UUID sourceId,
        String kind,
        String title,
        String description,
        CardStatus status,
        String priority,
        LocalDate dueDate,
        boolean overdue,
        UserRef assignee,
        UUID projectId,
        String projectKey,
        String linkedKey,
        CardAnnotationView annotation,
        List<BoardStatusRef> allowedTransitions) {
}
