package com.tcms.tracker.board;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * A project's labels — Trello's primary scanning device (§12.2), and <strong>deliberately not a
 * taxonomy</strong>.
 *
 * <p>This project already has {@code classification_dimension} and {@code case_tag} for controlled
 * vocabulary. A label is the informal, fast-moving axis, which is why it is project-scoped, needs no
 * dimension, and is deleted without a migration plan.
 *
 * <p>{@link #color} holds a design-token name and never a hex value, and the column is
 * {@code color} rather than {@code color_token} (D-i) — so this entity needs no {@code @Column}
 * override and no layer spells it differently from its neighbour.
 */
@Entity
@Table(name = "issue_label")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class IssueLabel {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "color", nullable = false)
    private String color;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    /** JPA only. */
    protected IssueLabel() {
    }

    static IssueLabel create(
            UUID id, UUID companyId, UUID projectId, String name, String color,
            UUID createdBy, Instant now) {

        IssueLabel created = new IssueLabel();
        created.id = id;
        created.companyId = companyId;
        created.projectId = projectId;
        created.name = name;
        created.color = color;
        created.createdBy = createdBy;
        created.createdAt = now;
        return created;
    }

    void redefine(String name, String color) {
        this.name = name;
        this.color = color;
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

    public String getColor() {
        return color;
    }
}
