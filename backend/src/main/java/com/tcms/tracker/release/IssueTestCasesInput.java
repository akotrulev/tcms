package com.tcms.tracker.release;

import java.util.List;
import java.util.UUID;

/**
 * {@code PUT /issues/{key}/test-cases} — the whole set, written over whatever was there.
 *
 * <p>A replacement rather than add and remove pairs, for {@code IssueLabelsInput}'s reason: the
 * control is a multi-select whose state <em>is</em> the set, and two verbs would let a stale client
 * unlink a case somebody else had just linked.
 */
public record IssueTestCasesInput(List<UUID> caseIds) {
}
