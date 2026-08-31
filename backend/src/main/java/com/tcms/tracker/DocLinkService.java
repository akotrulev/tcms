package com.tcms.tracker;

import com.tcms.project.ProjectService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads {@code issue_doc_link}, this module's mirror of the links from its issues into
 * documentation.
 *
 * <p>It reads the <em>mirror</em> and never {@code doc_link}: the whole point of
 * {@link IssueDocLinkFacts} is that no request path in this module names a {@code documentation}
 * table, so the freshness of what this serves is the projection's rather than this query's. A link
 * made a moment ago appears here after {@code documentation.links_changed} is delivered, which is
 * the same trade {@code case_doc_link} has made since it was built.
 *
 * <p>Gated on visibility alone, like every other read here: all six roles hold {@code issue:read}
 * (§A3), so a 403 arm would be unreachable and the contract declares none.
 */
@Service
public class DocLinkService {

    private final ProjectService projects;
    private final IssueRepository issues;
    private final IssueDocLinkRepository links;

    public DocLinkService(
            ProjectService projects, IssueRepository issues, IssueDocLinkRepository links) {

        this.projects = projects;
        this.issues = issues;
        this.links = links;
    }

    @Transactional(readOnly = true)
    public Optional<List<IssueDocLinkView>> list(UUID userId, String key) {
        return issues.findByIssueKeyAndDeletedAtIsNull(key)
                .flatMap(issue -> projects.readVisible(userId, issue.getProjectId(), project ->
                        links.findByIssueIdOrderByTargetKindAscPageKeyAscIdAsc(issue.getId())
                                .stream()
                                .map(IssueDocLinkView::of)
                                .toList()));
    }
}
