package com.tcms.documentation;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * The container a project's pages live in — one per project, seeded by {@code V18}.
 *
 * <p>It is not on the wire and has no endpoint. {@code doc_page.space_id} is {@code NOT NULL}, so
 * the row has to exist; nothing else about it is a product concept yet. The ERD models spaces as
 * something a project may eventually have several of, which is why this is a table rather than a
 * column on {@code project} — but until the API can name one, "the project's space" is the only
 * question anybody asks and {@link DocSpaceRepository} is the only place that asks it.
 *
 * <p>No mutators, deliberately. Nothing renames or deletes a space, and a setter here would be an
 * affordance for an operation with no endpoint, no permission and no audit action behind it.
 */
@Entity
@Table(name = "doc_space")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class DocSpace {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "name", nullable = false)
    private String name;

    /** JPA only. */
    protected DocSpace() {
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
}
