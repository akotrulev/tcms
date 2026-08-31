package com.tcms.tracker.board;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * One column of one board.
 *
 * <p>{@link #defaultStatusId} is what a drop into this column <em>means</em> (D2). It is always one
 * of the statuses the column claims — enforced by {@link BoardConfigService}, because a default
 * outside the claim set would move a card to a status the column does not show it in, so the card
 * would vanish from the board on being dropped onto it.
 *
 * <p>A null {@link #wipLimit} is "no limit" and is <strong>not</strong> zero: a zero limit is a
 * column permanently in breach, which the header would then shout about forever.
 */
@Entity
@Table(name = "board_column")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class BoardColumn {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "board_id", nullable = false, updatable = false)
    private UUID boardId;

    @Column(name = "name", nullable = false)
    private String name;

    /** Quoted in the DDL because {@code position} is a reserved word; the column name is plain. */
    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "wip_limit")
    private Integer wipLimit;

    @Column(name = "default_status_id", nullable = false)
    private UUID defaultStatusId;

    /** JPA only. */
    protected BoardColumn() {
    }

    static BoardColumn create(
            UUID id,
            UUID companyId,
            UUID boardId,
            String name,
            int position,
            Integer wipLimit,
            UUID defaultStatusId) {

        BoardColumn created = new BoardColumn();
        created.id = id;
        created.companyId = companyId;
        created.boardId = boardId;
        created.name = name;
        created.position = position;
        created.wipLimit = wipLimit;
        created.defaultStatusId = defaultStatusId;
        return created;
    }

    void redefine(String name, int position, Integer wipLimit, UUID defaultStatusId) {
        this.name = name;
        this.position = position;
        this.wipLimit = wipLimit;
        this.defaultStatusId = defaultStatusId;
    }

    void park(int position) {
        this.position = position;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getPosition() {
        return position;
    }

    public Integer getWipLimit() {
        return wipLimit;
    }

    public UUID getDefaultStatusId() {
        return defaultStatusId;
    }
}
