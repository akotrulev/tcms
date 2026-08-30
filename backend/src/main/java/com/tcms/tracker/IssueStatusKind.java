package com.tcms.tracker;

/**
 * What a status <em>means</em> — {@code issue_status.kind}.
 *
 * <p>D2: every percentage in the product is computed from this rather than from what a project
 * called its columns, so a client that renamed "Backlog" to "Icebox" still gets a completion figure.
 * {@link #IN_PROGRESS} is <strong>not partial credit</strong> (D8) — a number that moves before
 * anything ships is the number people learn to distrust, and once distrusted it is decoration.
 */
public enum IssueStatusKind {

    NOT_STARTED,
    IN_PROGRESS,
    DONE;

    /** @throws IllegalStateException for the reason {@link IssueLevel#of} gives. */
    static IssueStatusKind of(String stored) {
        for (IssueStatusKind kind : values()) {
            if (kind.name().equals(stored)) {
                return kind;
            }
        }
        throw new IllegalStateException("issue_status.kind holds " + stored
                + ", which the CHECK constraint does not permit");
    }
}
