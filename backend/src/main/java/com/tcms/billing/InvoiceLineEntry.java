package com.tcms.billing;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * The seventh table, and the anti-double-billing one (019 D-b).
 *
 * <p>Story M2 requires it — <em>"the time entries show which invoice they are on"</em> — and it is
 * also the only place <em>"which approved hours are already on an issued invoice"</em> can live.
 * Rows here exist <strong>only for issued invoices</strong> (a draft stores no lines), so
 * <em>already billed</em> is a fact about issued work and never about somebody else's open draft,
 * and {@code uq_invoice_line_entry_time} is what makes it a fact the database holds rather than a
 * query somebody remembered to write.
 *
 * <p><strong>{@link #timeEntryId} has no foreign key</strong>, and that is the module rule rather
 * than an omission: {@code billing} may not write a column on {@code time_entry} and may not import
 * {@code time}, so the reference is polymorphic exactly as {@code attachment.owner_id},
 * {@code doc_link.source_id}, {@code issue_link.target_id} and {@code time_entry.subject_id} are
 * (scope §3, ERD §0.1). The cost that pattern always pays is that referential integrity is
 * application-side and a deleted entry can orphan a row here.
 *
 * <p><strong>What bounds that cost is that this row is a snapshot.</strong> The hours, the day, the
 * person's name, the subject and the approval are <em>copied</em> rather than joined, so an orphan
 * still renders correctly on an invoice sent last quarter — which is the only thing an issued
 * invoice's supporting detail has to do.
 *
 * <p>{@link #approvedBy} is <strong>who decided that person's period</strong> — not who approved
 * this project's hours, and the difference is 019 D-f. A name rather than a user id, for this
 * class's reason: the invoice must read correctly after the approver leaves.
 */
@Entity
@Table(name = "invoice_line_entry")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class InvoiceLineEntry {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "invoice_line_id", nullable = false, updatable = false)
    private UUID invoiceLineId;

    @Column(name = "time_entry_id", nullable = false, updatable = false)
    private UUID timeEntryId;

    @Column(name = "spent_on", nullable = false, updatable = false)
    private LocalDate spentOn;

    @Column(name = "hours", nullable = false, updatable = false)
    private BigDecimal hours;

    @Column(name = "person_name", nullable = false, updatable = false)
    private String personName;

    @Column(name = "subject_key", updatable = false)
    private String subjectKey;

    @Column(name = "subject_title", updatable = false)
    private String subjectTitle;

    @Column(name = "approved_at", nullable = false, updatable = false)
    private Instant approvedAt;

    @Column(name = "approved_by", nullable = false, updatable = false)
    private String approvedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA only. */
    protected InvoiceLineEntry() {
    }

    static InvoiceLineEntry snapshot(
            UUID id,
            UUID companyId,
            UUID invoiceLineId,
            UUID timeEntryId,
            LocalDate spentOn,
            BigDecimal hours,
            String personName,
            String subjectKey,
            String subjectTitle,
            Instant approvedAt,
            String approvedBy,
            Instant now) {

        InvoiceLineEntry copied = new InvoiceLineEntry();
        copied.id = id;
        copied.companyId = companyId;
        copied.invoiceLineId = invoiceLineId;
        copied.timeEntryId = timeEntryId;
        copied.spentOn = spentOn;
        copied.hours = hours;
        copied.personName = personName;
        copied.subjectKey = subjectKey;
        copied.subjectTitle = subjectTitle;
        copied.approvedAt = approvedAt;
        copied.approvedBy = approvedBy;
        copied.createdAt = now;
        return copied;
    }

    UUID getInvoiceLineId() {
        return invoiceLineId;
    }

    UUID getTimeEntryId() {
        return timeEntryId;
    }

    LocalDate getSpentOn() {
        return spentOn;
    }

    BigDecimal getHours() {
        return hours;
    }

    String getPersonName() {
        return personName;
    }

    String getSubjectKey() {
        return subjectKey;
    }

    String getSubjectTitle() {
        return subjectTitle;
    }

    Instant getApprovedAt() {
        return approvedAt;
    }

    String getApprovedBy() {
        return approvedBy;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
