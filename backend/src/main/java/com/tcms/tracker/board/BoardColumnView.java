package com.tcms.tracker.board;

import com.tcms.tracker.IssueStatusView;
import java.util.List;
import java.util.UUID;

/**
 * The contract's {@code BoardColumn}.
 *
 * <p>A separate record from the {@link BoardColumn} entity deliberately: the wire shape and the row
 * are different things, and pairing a schema to an entity is how a column becomes a field nobody
 * meant to publish.
 */
public record BoardColumnView(
        UUID id,
        String name,
        int position,
        Integer wipLimit,
        List<IssueStatusView> statuses,
        UUID defaultStatusId) {
}
