package com.tcms.tracker;

import com.tcms.api.UserRef;
import com.tcms.project.GateResult;
import com.tcms.tenancy.CompanyContext;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Following an issue — {@code PUT /issues/{key}/watchers}.
 *
 * <h2>A PUT with a boolean, not a POST and a DELETE</h2>
 *
 * <p>The star on the screen has two states, and the request says which one the person wants rather
 * than which one they think it is in. That makes it idempotent in both directions: two clicks on a
 * slow connection cannot toggle it back, and a client that lost the response can simply send the
 * same thing again.
 *
 * <h2>Watching is not editing, and the gate says so</h2>
 *
 * <p>{@code issue:read} — every role holds it (§A3), so the {@code Denied} arm is unreachable and
 * the contract declares no 403. It goes through {@link com.tcms.project.ProjectService#writeGranted}
 * rather than {@code readVisible} for a mechanical reason worth stating: {@code readVisible} opens a
 * <em>read-only</em> transaction, and a row written inside one is refused by the connection rather
 * than by any rule in this codebase.
 */
@Service
public class WatcherService {

    /** Held by all six roles (§A3). The gate is here to bound visibility, not to refuse anybody. */
    private static final String READ_ISSUE = "issue:read";

    private final com.tcms.project.ProjectService projects;
    private final IssueRepository issues;
    private final IssueWatcherRepository watchers;
    private final TrackerPeople people;
    private final Clock clock;

    public WatcherService(
            com.tcms.project.ProjectService projects,
            IssueRepository issues,
            IssueWatcherRepository watchers,
            TrackerPeople people,
            Clock clock) {

        this.projects = projects;
        this.issues = issues;
        this.watchers = watchers;
        this.people = people;
        this.clock = clock;
    }

    /** Sets whether the caller follows this issue, and answers with the watchers afterwards. */
    public GateResult<List<UserRef>> setWatching(UUID userId, String key, boolean watching) {
        Optional<Issue> issue = issues.findByIssueKeyAndDeletedAtIsNull(key);
        if (issue.isEmpty()) {
            return new GateResult.Unreachable<>();
        }
        return projects.writeGranted(userId, issue.get().getProjectId(), READ_ISSUE, project -> {
            IssueWatcherId id = new IssueWatcherId(issue.get().getId(), userId);
            if (watching) {
                if (watchers.findById(id).isEmpty()) {
                    watchers.save(IssueWatcher.follow(
                            CompanyContext.require(), issue.get().getId(), userId, clock.instant()));
                }
            } else {
                watchers.deleteById(id);
            }
            return watchersOf(issue.get().getId());
        });
    }

    /**
     * The people following one issue, in the order they started.
     *
     * <p>Package-private because {@link IssueService} serves the same list on the issue itself, and
     * a second assembly of it would drift from this one the first time either changed.
     */
    List<UserRef> watchersOf(UUID issueId) {
        List<IssueWatcher> following = watchers.findByIssueIdOrderByCreatedAtAsc(issueId);
        Set<UUID> userIds = new HashSet<>();
        following.forEach(watcher -> userIds.add(watcher.getUserId()));

        var refs = people.resolve(CompanyContext.require(), userIds);
        return following.stream()
                .map(watcher -> refs.get(watcher.getUserId()))
                .filter(java.util.Objects::nonNull)
                .toList();
    }
}
