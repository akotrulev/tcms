package com.tcms.tracker;

import java.util.UUID;

/**
 * The contract's {@code IssueStatusRef}.
 *
 * <p>{@code kind} travels beside the name on every card, which is what lets a screen colour a
 * column without knowing what this project called it (D2).
 *
 * @param blocking 021 D-b — the one status per project that remembers where a card came from.
 *                 A <strong>flag</strong> and not a fourth {@link IssueStatusKind}: blocked work is
 *                 in flight and not done, so it is {@code IN_PROGRESS} by every percentage,
 *                 burn-up band and flow band that reads a kind, and a fourth member would touch all
 *                 of them to express a distinction none of them cares about
 */
public record IssueStatusView(
        UUID id, String name, IssueStatusKind kind, int position, boolean blocking) {

    public static IssueStatusView of(IssueStatus status) {
        return new IssueStatusView(status.getId(), status.getName(), status.getKind(),
                status.getPosition(), status.isBlocking());
    }
}
