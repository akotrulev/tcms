package com.tcms.tracker;

import java.util.List;
import java.util.UUID;

/**
 * The contract's {@code ProjectWorkflow} — a project's statuses, its types and the legal moves
 * between the statuses, as one document.
 *
 * <p><strong>It is also the PUT's request body</strong>, and that is the decision rather than a
 * convenience: reordering is a statement about the <em>set</em>, and a sequence of single-status
 * writes has an intermediate state where two statuses claim one position — which is exactly what the
 * settings screen's ↑/↓ buttons generate.
 */
public record WorkflowView(
        UUID projectId,
        List<IssueStatusView> statuses,
        List<IssueTypeView> types,
        List<TransitionView> transitions) {

    /** One legal move, as a pair. A pair not in this list is refused with a 409. */
    public record TransitionView(UUID fromStatusId, UUID toStatusId) {
    }
}
