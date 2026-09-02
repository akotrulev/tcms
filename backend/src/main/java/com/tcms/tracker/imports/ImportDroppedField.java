package com.tcms.tracker.imports;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * One source field the import did not bring across — a row, not a sentence in a paragraph (D-f).
 *
 * <p>A year later the question is <em>"how many Story Points values did we drop across every
 * import"</em>, and a rendered string cannot answer it. That is 009 {@code t25}'s acceptance
 * criterion and the whole reason this is a table.
 *
 * <p>{@link #reason} is <strong>stored</strong> rather than looked up from {@link DroppedFields}
 * when the report is read. The sentence is part of what somebody was told before they pressed
 * Confirm, so editing the catalogue must not retroactively rewrite a report — the argument
 * {@code issue.issue_key}'s comment makes about composed keys.
 */
@Entity
@Table(name = "import_dropped_field")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class ImportDroppedField {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "import_run_id", nullable = false, updatable = false)
    private UUID importRunId;

    @Column(name = "field_name", nullable = false, updatable = false)
    private String fieldName;

    @Column(name = "field_type", nullable = false, updatable = false)
    private String fieldType;

    @Column(name = "value_count", nullable = false, updatable = false)
    private int valueCount;

    @Column(name = "reason", nullable = false, updatable = false)
    private String reason;

    /**
     * The source's own field order, preserved.
     *
     * <p>Not alphabetical, and the column exists rather than an {@code ORDER BY field_name},
     * because {@code ImportReportMatchesPreviewTest} compares the report's list to the preview's
     * <em>element for element</em> — a list that reordered itself between the two would fail that
     * for a reason with nothing to do with what was dropped.
     */
    @Column(name = "position", nullable = false, updatable = false)
    private int position;

    /** JPA only. */
    protected ImportDroppedField() {
    }

    static ImportDroppedField of(
            UUID companyId, UUID importRunId, DroppedFieldView dropped, int position) {

        ImportDroppedField row = new ImportDroppedField();
        row.id = UUID.randomUUID();
        row.companyId = companyId;
        row.importRunId = importRunId;
        row.fieldName = dropped.name();
        row.fieldType = dropped.fieldType();
        row.valueCount = dropped.valueCount();
        row.reason = dropped.reason();
        row.position = position;
        return row;
    }

    /** The wire shape this row was written from, rebuilt — one shape, two responses (D-f). */
    DroppedFieldView toView() {
        return new DroppedFieldView(fieldName, fieldType, valueCount, reason);
    }

    public UUID getId() {
        return id;
    }

    public UUID getImportRunId() {
        return importRunId;
    }

    public String getFieldName() {
        return fieldName;
    }

    public String getFieldType() {
        return fieldType;
    }

    public int getValueCount() {
        return valueCount;
    }

    public String getReason() {
        return reason;
    }

    public int getPosition() {
        return position;
    }
}
