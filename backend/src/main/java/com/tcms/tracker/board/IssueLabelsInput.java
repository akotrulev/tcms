package com.tcms.tracker.board;

import java.util.List;
import java.util.UUID;

/**
 * {@code PUT /issues/{key}/labels} — <strong>a replacement, not add/remove pairs</strong>.
 *
 * <p>The SPA's control is a multi-select whose state is the whole set, and two endpoints for it
 * would let a stale client remove a label somebody else had just added.
 */
public record IssueLabelsInput(List<UUID> labelIds) {
}
