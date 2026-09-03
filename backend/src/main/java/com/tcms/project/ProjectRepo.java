package com.tcms.project;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * One repository a project spans, and how it is built — {@code project_repository}, reference data
 * (D16).
 *
 * <p><strong>Named {@code ProjectRepo} and not {@code ProjectRepository}</strong>, which is the name
 * of this package's Spring Data interface for {@link Project}. Two types one import apart, one an
 * entity and one a repository, is how a wrong import survives review — the same argument
 * {@code tracker.release.VerificationLink} records for not being called {@code IssueLink}.
 *
 * <p>This product never holds, clones or builds a repository. A row here is what a new joiner or
 * somebody's assistant is <em>told</em> to check out, which is why there is no credential column and
 * no local path: the checkout path is machine-specific and lives in the developer's own MCP
 * configuration (D25).
 *
 * <p>{@code build_command} is per repository rather than per project, and V8's own comment carries
 * the reason: this product is itself three stacks with three gates and no root build, so one
 * wrapper command would say "the build broke" without saying which stack.
 *
 * <h2>"Primary" is not stored</h2>
 *
 * <p>The lowest {@link #position} renders as primary ({@code settings-repositories.html}). A second
 * boolean column would be a second source of truth for the same fact, and the two would disagree the
 * first time a row was deleted.
 */
@Entity
@Table(name = "project_repository")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class ProjectRepo {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "url", nullable = false)
    private String url;

    @Column(name = "default_branch", nullable = false)
    private String defaultBranch;

    /** The working subdirectory within the repository. {@code /} for the root. */
    @Column(name = "subdirectory", nullable = false)
    private String subdirectory;

    @Column(name = "build_command", nullable = false)
    private String buildCommand;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    /** JPA only. */
    protected ProjectRepo() {
    }

    static ProjectRepo add(
            UUID companyId,
            UUID projectId,
            int position,
            ProjectRepoInput input,
            UUID actorId,
            Instant now) {

        ProjectRepo created = new ProjectRepo();
        created.id = UUID.randomUUID();
        created.companyId = companyId;
        created.projectId = projectId;
        created.position = position;
        created.createdAt = now;
        created.createdBy = actorId;
        created.apply(input, actorId, now);
        return created;
    }

    /** A full replacement of the five values — the {@code PATCH} is not a delta (the contract says so). */
    void apply(ProjectRepoInput input, UUID actorId, Instant now) {
        this.name = input.name().strip();
        this.url = input.url().strip();
        this.defaultBranch = input.defaultBranch().strip();
        this.subdirectory = input.subdirectory().strip();
        this.buildCommand = input.buildCommand().strip();
        this.updatedBy = actorId;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }

    public String getSubdirectory() {
        return subdirectory;
    }

    public String getBuildCommand() {
        return buildCommand;
    }

    public int getPosition() {
        return position;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }
}
