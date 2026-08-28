package com.tcms.integration.tracker;

/**
 * {@code TrackerIssueStatusCategory} in {@code docs/specs/openapi.yaml} — the closed set a client
 * may colour by.
 *
 * <p>Jira's own {@code statusCategory} and ADO's state category map onto these three in the
 * adapter. <strong>The tracker's free-text status <em>name</em> is carried separately and nothing
 * may branch on it</strong>: status names are configured per project, in somebody else's product,
 * and a client switching on "Ready for QA" is a client that renders grey the day a team renames a
 * column.
 */
public enum TrackerIssueStatusCategory {

    /** Not started. Jira {@code new} / {@code undefined}; ADO {@code Proposed}. */
    TODO,

    /** Being worked. Jira {@code indeterminate}; ADO {@code InProgress} / {@code Resolved}. */
    IN_PROGRESS,

    /** Finished, however that tracker spells it. Jira {@code done}; ADO {@code Completed}. */
    DONE
}
