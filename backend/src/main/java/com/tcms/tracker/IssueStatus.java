package com.tcms.tracker;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * One column of one project's workflow.
 *
 * <p>D2 puts the canonical status on the issue and lets a board <em>map</em> statuses onto columns,
 * so two boards over the same work cannot disagree about where a card is. {@link #kind} is what
 * every percentage is computed from, and {@link #position} is what "the first status" means — a new
 * issue enters the project's first status <strong>by position</strong> and never by the word
 * "Backlog", because a project that renamed it still has a first column.
 */
@Entity
@Table(name = "issue_status")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class IssueStatus {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "name", nullable = false)
    private String name;

    /** {@code NOT_STARTED}, {@code IN_PROGRESS} or {@code DONE}. Read through {@link #getKind()}. */
    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "position", nullable = false)
    private int position;

    /**
     * The one status per project whose entry records where the card came from (021 D-b).
     *
     * <p>A flag rather than a fourth {@link IssueStatusKind}, and that is a decision about what
     * {@code kind} is <em>for</em>: every percentage, burn-up band and flow band in the product is
     * computed from it, and blocked work is in flight and not done — which is what
     * {@code IN_PROGRESS} already means to every one of them. A fourth kind would have to be given
     * a meaning by all of them to express a distinction none of them cares about.
     *
     * <p>At most one per project, held by {@code uq_issue_status_one_blocking}. Written by the
     * seed and by V13's back-fill; the workflow editor cannot yet move it, which is 021 T3's.
     */
    @Column(name = "blocking", nullable = false)
    private boolean blocking;

    /** JPA only. */
    protected IssueStatus() {
    }

    static IssueStatus create(
            UUID id, UUID companyId, UUID projectId, String name, IssueStatusKind kind, int position) {

        IssueStatus created = new IssueStatus();
        created.id = id;
        created.companyId = companyId;
        created.projectId = projectId;
        created.name = name;
        created.kind = kind.name();
        created.position = position;
        return created;
    }

    void redefine(String name, IssueStatusKind kind, int position) {
        this.name = name;
        this.kind = kind.name();
        this.position = position;
    }

    public UUID getId() {
        return id;
    }

    UUID getCompanyId() {
        return companyId;
    }

    UUID getProjectId() {
        return projectId;
    }

    public String getName() {
        return name;
    }

    public IssueStatusKind getKind() {
        return IssueStatusKind.of(kind);
    }

    public int getPosition() {
        return position;
    }

    public boolean isBlocking() {
        return blocking;
    }
}
