package com.tcms.tracker;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * One of a project's issue types — Epic, Story, Task, Bug, Spike, Sub-task, or whatever a client
 * adds next.
 *
 * <p><strong>{@link #level} is the load-bearing column and {@link #name} is not.</strong> The depth
 * rule ({@link IssueEdit}) compares levels, {@link Completion} counts on
 * {@link #countsTowardCompletion}, and the icon is stored rather than derived — so a project that
 * renames "Story" to "Requirement" changes one string and nothing else in the product notices.
 * Adding a fifth story-level type is a row in {@code tcms_seed_project_defaults} or a
 * {@code PUT /projects/{id}/workflow}, never a change here.
 */
@Entity
@Table(name = "issue_type")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class IssueType {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "name", nullable = false)
    private String name;

    /** {@code EPIC}, {@code STORY} or {@code TASK}. Read through {@link #getLevel()}. */
    @Column(name = "level", nullable = false)
    private String level;

    @Column(name = "counts_toward_completion", nullable = false)
    private boolean countsTowardCompletion;

    @Column(name = "icon_code", nullable = false)
    private String iconCode;

    @Column(name = "position", nullable = false)
    private int position;

    /** JPA only. */
    protected IssueType() {
    }

    static IssueType create(
            UUID id,
            UUID companyId,
            UUID projectId,
            String name,
            IssueLevel level,
            boolean countsTowardCompletion,
            String iconCode,
            int position) {

        IssueType created = new IssueType();
        created.id = id;
        created.companyId = companyId;
        created.projectId = projectId;
        created.name = name;
        created.level = level.name();
        created.countsTowardCompletion = countsTowardCompletion;
        created.iconCode = iconCode;
        created.position = position;
        return created;
    }

    void rename(String name, String iconCode, int position) {
        this.name = name;
        this.iconCode = iconCode;
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

    public IssueLevel getLevel() {
        return IssueLevel.of(level);
    }

    public boolean isCountsTowardCompletion() {
        return countsTowardCompletion;
    }

    public String getIconCode() {
        return iconCode;
    }

    public int getPosition() {
        return position;
    }

    /** The pure-validation view of this row — see {@link IssueEdit}. */
    IssueTypeRow row() {
        return new IssueTypeRow(id, name, getLevel(), countsTowardCompletion);
    }
}
