package com.tcms.work;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * One person's own note, personal due date and flag on one card — {@code derived_item_annotation}.
 *
 * <p><strong>Keyed on the VIEWER and on nobody else.</strong> Story B5's second criterion: <em>"when
 * a teammate looks at the same issue, they do not see my note"</em> — two people reading one card
 * get different values here, and that is the feature rather than a caching bug.
 *
 * <p><strong>D11 keeps this table and says it matters more, not less.</strong> {@code V1} built it
 * for the derived half of the board, where a card mirrors somebody else's row and cannot be edited;
 * a board that mixes your own issues with reviews other people asked of you is exactly the case a
 * private note on a card you do not own is for. {@code V3} relabels its {@code 'TASK'} rows to
 * {@code 'ISSUE'} rather than dropping them, because the migration carried {@code work_item.id}
 * forward — so a note written on a task before the upgrade is still on the issue it became.
 *
 * <p>{@link #sourceType} is free text rather than an enum. It has to name whatever kind of card
 * exists, including three whose generators are not built, and a closed set here would make adding a
 * generator a migration.
 */
@Entity
@Table(name = "derived_item_annotation")
@IdClass(CardAnnotationId.class)
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class CardAnnotation {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Id
    @Column(name = "source_type", nullable = false, updatable = false)
    private String sourceType;

    @Id
    @Column(name = "source_id", nullable = false, updatable = false)
    private UUID sourceId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "note")
    private String note;

    @Column(name = "personal_due_date")
    private LocalDate personalDueDate;

    @Column(name = "flagged", nullable = false)
    private boolean flagged;

    /** JPA only. */
    protected CardAnnotation() {
    }

    static CardAnnotation forCard(UUID companyId, UUID userId, String sourceType, UUID sourceId) {
        CardAnnotation annotation = new CardAnnotation();
        annotation.companyId = companyId;
        annotation.userId = userId;
        annotation.sourceType = sourceType;
        annotation.sourceId = sourceId;
        return annotation;
    }

    /**
     * Applies a partial edit. An absent field is "leave it", which is why each argument arrives
     * already merged against what is stored — the same rule {@code IssueService} keeps with
     * {@code PatchBody}, and the defect it prevents is a delta edit emptying the note.
     */
    void apply(String note, LocalDate personalDueDate, boolean flagged) {
        this.note = note;
        this.personalDueDate = personalDueDate;
        this.flagged = flagged;
    }

    UUID getSourceId() {
        return sourceId;
    }

    String getSourceType() {
        return sourceType;
    }

    String getNote() {
        return note;
    }

    LocalDate getPersonalDueDate() {
        return personalDueDate;
    }

    boolean isFlagged() {
        return flagged;
    }
}
