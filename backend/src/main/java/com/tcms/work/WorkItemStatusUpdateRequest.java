package com.tcms.work;

/**
 * {@code WorkItemStatusUpdate} on the wire — the body of a move.
 *
 * <p>One field, and the whole of what {@code PATCH /work-items/{id}} accepts. Title, description,
 * due date and assignee are <strong>not</strong> editable through this endpoint, because the board
 * is the only screen that writes here and it only ever moves cards. A field accepted with no
 * affordance behind it is a capability nobody designed, tested, or gated.
 *
 * <h2>A {@code String}, not the enum, and the first draft had it the other way</h2>
 *
 * <p>Binding this directly to {@link WorkItemStatus} looks stricter and is worse. Jackson refuses an
 * unknown value before any code in this module runs, so the answer becomes a generic
 * {@code HttpMessageNotReadableException} 400 — and {@code WorkItemController.unknownStatusProblem},
 * which names the four valid columns, becomes <strong>unreachable code that reads as if it runs</strong>.
 * Two files each looked right on their own while contradicting each other.
 *
 * <p>Jackson's enum binding is also case-sensitive, so {@code "done"} would be rejected on a
 * technicality by a system that knows exactly what was meant. The controller parses and normalises
 * instead, which keeps both the forgiveness and the useful error.
 */
public record WorkItemStatusUpdateRequest(String status) {
}
