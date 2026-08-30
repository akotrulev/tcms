package com.tcms.tracker;

import java.util.UUID;

/**
 * One acceptance criterion — story H1/H4's checklist, and the "3 of 4" the story page renders from
 * this and nothing else.
 *
 * <p>Each carries its own id so a client can tick one without sending the list back in order, and
 * its own {@code position} so the order is the author's rather than the array's incidental one.
 */
public record AcceptanceCriterion(UUID id, String text, boolean done, int position) {
}
