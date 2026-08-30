package com.tcms.tracker;

import java.util.UUID;

/** {@code POST /issues/{key}/transitions} — the target status, and nothing else. */
public record IssueTransitionInput(UUID statusId) {
}
