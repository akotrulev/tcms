package com.tcms.tracker;

/**
 * Where a type sits in the hierarchy — {@code issue_type.level}.
 *
 * <p><strong>The depth rule is about this and never about a type's name</strong> (scope §4). Story,
 * Bug, Task and Spike all sit at {@link #STORY}, so all four hold sub-tasks and all four may sit
 * under an Epic — and a fifth story-level type is a <em>row</em> rather than a migration and a
 * change here. {@link IssueEdit} reads {@link #depth()} and compares numbers; nothing in this module
 * compares a type name.
 */
public enum IssueLevel {

    EPIC,
    STORY,
    TASK;

    /** {@code EPIC} → 0, {@code STORY} → 1, {@code TASK} → 2, so "one level above" is arithmetic. */
    int depth() {
        return ordinal();
    }

    /**
     * The stored spelling, as {@code issue_type.level}'s {@code CHECK} constraint permits it.
     *
     * @throws IllegalStateException naming the row, because a value outside the constraint means the
     *     row predates it or the constraint was widened without this enum — and a silent default
     *     would put the widened type at whatever depth happened to be first
     */
    static IssueLevel of(String stored) {
        for (IssueLevel level : values()) {
            if (level.name().equals(stored)) {
                return level;
            }
        }
        throw new IllegalStateException("issue_type.level holds " + stored
                + ", which the CHECK constraint does not permit — the row predates the constraint "
                + "or the constraint was widened without this enum");
    }
}
