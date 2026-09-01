package com.tcms.tracker.board;

import com.tcms.api.UserRef;
import com.tcms.tracker.IssueLevel;
import com.tcms.tracker.IssueStatusView;
import com.tcms.tracker.release.ReleaseRefView;
import java.util.List;

/**
 * The contract's {@code BacklogRow}.
 *
 * @param position the 1-based number the row shows, derived from rank order and never stored — a
 *                 stored position is what makes a drag a thousand-row UPDATE
 */
public record BacklogRowView(
        String key,
        String title,
        String typeName,
        IssueLevel typeLevel,
        String iconCode,
        int position,
        String rank,
        IssueStatusView status,
        UserRef assignee,
        Double estimateHours,
        String epicKey,
        ReleaseRefView release,
        List<LabelView> labels) {
}
