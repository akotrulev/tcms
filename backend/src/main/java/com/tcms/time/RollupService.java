package com.tcms.time;

import com.tcms.project.GateResult;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.CompanyContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintains {@code issue_time_rollup}, and serves the two grains a screen asks for.
 *
 * <h2>Synchronous, inside the transaction that moved the hours (016 D-h)</h2>
 *
 * <p>{@link #restate} is called from {@code TimeEntryService} and {@code TimesheetService} while
 * their write is still open. Not an outbox event: this module owns both the entries and the rollup,
 * so there is no boundary to cross — the outbox exists to cross boundaries — and it has a delivery
 * floor around a minute, while the story panel has to be right the moment Approve is clicked.
 *
 * <h2>Recomputed, never adjusted</h2>
 *
 * <p>Every call rewrites the affected issues from the entries that exist now, rather than adding and
 * subtracting deltas. A delta is correct only if every path that could change an hour remembers to
 * apply one, and the failure mode of forgetting is a number that is quietly wrong forever. Reading
 * the two sums back costs one query each and cannot drift.
 */
@Service
public class RollupService {

    private static final String READ_ISSUE = "issue:read";

    private final ProjectService projects;
    private final IssueFacts issues;
    private final TimeEntryRepository entries;
    private final IssueTimeRollupRepository rollups;
    private final Clock clock;

    public RollupService(
            ProjectService projects,
            IssueFacts issues,
            TimeEntryRepository entries,
            IssueTimeRollupRepository rollups,
            Clock clock) {

        this.projects = projects;
        this.issues = issues;
        this.entries = entries;
        this.rollups = rollups;
        this.clock = clock;
    }

    /** {@code GET /time/rollup/issues/{key}} — the "14h (approved)" figure on a story. */
    @Transactional(readOnly = true)
    public GateResult<TimeRollupView> forIssue(UUID userId, String issueKey) {
        Optional<IssueFacts.IssueSubject> subject = issues.byKey(issueKey);
        if (subject.isEmpty()) {
            return new GateResult.Unreachable<>();
        }
        IssueFacts.IssueSubject issue = subject.get();
        return projects.readGranted(userId, issue.projectId(), READ_ISSUE, project -> {
            IssueTimeRollup stored = rollups.findScopedByIssueId(issue.issueId()).orElse(null);
            return new TimeRollupView(
                    issue.issueKey(),
                    hours(issue.estimateHours()),
                    stored == null ? 0d : stored.getApprovedHours().doubleValue(),
                    stored == null ? 0d : stored.getPendingHours().doubleValue());
        });
    }

    /**
     * {@code GET /time/rollup/epics/{key}} — the epic panel, totals and a row per counted child.
     *
     * <p><strong>The totals cover the epic and its counted children together</strong>, and the table
     * lists the children. An epic normally carries no estimate and no hours of its own — D8 puts the
     * estimate on the story — but somebody who logs an afternoon against the epic itself has worked
     * those hours, and a panel that silently dropped them would be a total that does not add up to
     * the timesheets it came from.
     */
    @Transactional(readOnly = true)
    public GateResult<EpicTimeRollupView> forEpic(UUID userId, String epicKey) {
        Optional<IssueFacts.IssueSubject> subject = issues.byKey(epicKey);
        if (subject.isEmpty() || !"EPIC".equals(subject.get().typeLevel())) {
            // A story's key on the epic path is a 404 rather than an empty panel: the client asked
            // about something this resource does not describe, and an epic with no children and a
            // story with no children would otherwise render identically.
            return new GateResult.Unreachable<>();
        }
        IssueFacts.IssueSubject epic = subject.get();
        return projects.readGranted(userId, epic.projectId(), READ_ISSUE, project -> {
            List<IssueFacts.EpicChildFact> children = issues.epicChildren(epic.issueId());

            Set<UUID> everyIssue = new LinkedHashSet<>();
            everyIssue.add(epic.issueId());
            children.forEach(child -> everyIssue.add(child.issueId()));
            Map<UUID, IssueTimeRollup> stored = storedFor(everyIssue);

            double estimate = value(epic.estimateHours());
            double approved = approvedOf(stored.get(epic.issueId()));
            double pending = pendingOf(stored.get(epic.issueId()));

            List<EpicTimeRollupView.ChildView> rows = new ArrayList<>();
            for (IssueFacts.EpicChildFact child : children) {
                IssueTimeRollup row = stored.get(child.issueId());
                double childApproved = approvedOf(row);
                estimate += value(child.estimateHours());
                approved += childApproved;
                pending += pendingOf(row);
                rows.add(new EpicTimeRollupView.ChildView(
                        child.issueKey(),
                        child.title(),
                        hours(child.estimateHours()),
                        childApproved,
                        // An unestimated child cannot be over its estimate. A null read as zero here
                        // would highlight every unestimated row the moment anybody logged an hour.
                        child.estimateHours() != null
                                && childApproved > child.estimateHours().doubleValue()));
            }
            return new EpicTimeRollupView(epic.issueKey(), estimate, approved, pending, rows);
        });
    }

    /**
     * Rewrites the rollup for each issue named, from the entries that exist right now.
     *
     * <p>Package-private and un-gated: every caller is a service in this module that has already
     * passed its own gate, and the issues named are the ones its own write touched.
     */
    void restate(Collection<UUID> issueIds) {
        if (issueIds.isEmpty()) {
            return;
        }
        UUID companyId = CompanyContext.require();
        Instant now = clock.instant();
        for (UUID issueId : issueIds) {
            BigDecimal approved = entries.hoursOnIssueIn(issueId, TimesheetState.APPROVED);
            BigDecimal pending = entries.hoursOnIssueOutside(issueId, TimesheetState.APPROVED);
            List<TimeEntry> surviving =
                    entries.findBySubjectTypeAndSubjectId(TimeSubjectType.ISSUE, issueId);
            Optional<IssueTimeRollup> existing = rollups.findScopedByIssueId(issueId);

            if (surviving.isEmpty()) {
                // The last entry against this issue has gone. The row is removed rather than left
                // at zero, because a rollup row is a claim that hours were logged here and a zero
                // is a different claim from silence.
                existing.ifPresent(rollups::delete);
                continue;
            }
            UUID projectId = surviving.getFirst().getProjectId();
            IssueTimeRollup row = existing.orElseGet(
                    () -> IssueTimeRollup.of(issueId, companyId, projectId, now));
            row.restate(projectId, approved, pending, now);
            rollups.save(row);
        }
    }

    private Map<UUID, IssueTimeRollup> storedFor(Collection<UUID> issueIds) {
        // A HashMap rather than the stream collector's, because a caller looks a nullable id up in
        // it and Map.of()'s get(null) throws by contract — §38's 500, one module over.
        Map<UUID, IssueTimeRollup> byIssue = new HashMap<>();
        rollups.findByIssueIdIn(issueIds).forEach(row -> byIssue.put(row.getIssueId(), row));
        return byIssue;
    }

    private static double approvedOf(IssueTimeRollup row) {
        return row == null ? 0d : row.getApprovedHours().doubleValue();
    }

    private static double pendingOf(IssueTimeRollup row) {
        return row == null ? 0d : row.getPendingHours().doubleValue();
    }

    private static double value(BigDecimal estimate) {
        return estimate == null ? 0d : estimate.doubleValue();
    }

    private static Double hours(BigDecimal estimate) {
        return estimate == null ? null : estimate.doubleValue();
    }
}
