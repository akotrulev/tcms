package com.tcms.tracker.board;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * A board — <strong>a lens, not a container</strong> (scope §12.1).
 *
 * <p>Nothing points at a board from {@code issue}, which is the whole design: an issue appears on
 * every board of its project, in the column claiming its status. Deleting a board therefore loses
 * no work, and two boards over one project cannot disagree about where a card is because neither
 * owns the answer — {@link com.tcms.tracker.IssueStatus} does (D2).
 */
@Entity
@Table(name = "board")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class Board {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "swimlane_by", nullable = false)
    private SwimlaneBy swimlaneBy;

    /**
     * Where this board sits in the Work strip, left to right (021 D-f).
     *
     * <p>V13's column, and it exists because {@code board} had no order of its own: the repository
     * sorted by name, which lists Dev before PM and QA last, so the three seeded boards would have
     * read their hand-offs right to left. Deliberately not unique — a created board takes
     * {@code max + 1}, two created in one instant may tie, and the name breaks it.
     */
    @Column(name = "position", nullable = false)
    private int position;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    /** JPA only. */
    protected Board() {
    }

    static Board create(
            UUID id, UUID companyId, UUID projectId, String name, SwimlaneBy swimlaneBy,
            int position) {

        Board created = new Board();
        created.id = id;
        created.companyId = companyId;
        created.projectId = projectId;
        created.name = name;
        created.swimlaneBy = swimlaneBy;
        created.position = position;
        return created;
    }

    void redefine(String name, SwimlaneBy swimlaneBy) {
        this.name = name;
        this.swimlaneBy = swimlaneBy;
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

    public SwimlaneBy getSwimlaneBy() {
        return swimlaneBy;
    }

    public int getPosition() {
        return position;
    }
}
