package com.tcms.tracker;

import java.util.UUID;

/**
 * The contract's {@code IssueStatusRef}.
 *
 * <p>{@code kind} travels beside the name on every card, which is what lets a screen colour a
 * column without knowing what this project called it (D2).
 */
public record IssueStatusView(UUID id, String name, IssueStatusKind kind, int position) {

    static IssueStatusView of(IssueStatus status) {
        return new IssueStatusView(
                status.getId(), status.getName(), status.getKind(), status.getPosition());
    }
}
