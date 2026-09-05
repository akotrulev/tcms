package com.tcms.billing;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * A draft is a header; an issued invoice is a record (019 D-c).
 *
 * <p>While {@code DRAFT} this row carries the client, the period, the terms and nothing else — no
 * {@code seq}, no {@code number}, no total, and <strong>not one {@code invoice_line}</strong>. Its
 * lines are computed on every read from the approved entries that exist now.
 * {@code ck_invoice_issued} is that decision written as a constraint: the two states have different
 * shapes and the database holds the difference, so a half-issued row cannot exist even if this class
 * were wrong.
 *
 * <p>{@link #issue} is the only method that moves the state, and it sets all four issued columns
 * together for exactly that reason.
 *
 * <p>{@link #clientId} and {@link #billToId} are raw ids and not associations: {@code client} and
 * {@code client_contact} are {@code crm}'s tables, this module may not import that package, and the
 * names arrive through {@link com.tcms.project.ClientDirectory}. {@code RateCard} states the same
 * argument one table over.
 *
 * <p><strong>{@code client_id} has no cascade and no {@code SET NULL}</strong>, unlike
 * {@code rate_card.client_id}: an issued invoice is the record of what was sent, and deleting the
 * client must neither take it nor hollow it out. {@code DELETE /clients/{clientId}} already answers
 * 409 while a project references the client (018 D-k), and this refuses the same way from one table
 * over.
 */
@Entity
@Table(name = "invoice")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class Invoice {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "client_id", nullable = false, updatable = false)
    private UUID clientId;

    /**
     * This module's own counter (019 D-k), {@code NULL} until issue.
     *
     * <p>Its reader is {@link InvoiceNumbers}' {@code MAX(seq)} statement rather than a getter:
     * nothing on the wire carries the sequence, only the rendered {@link #number} it produces.
     */
    @Column(name = "seq")
    private Long seq;

    @Column(name = "number")
    private String number;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private InvoiceStatus status;

    @Column(name = "currency", nullable = false, updatable = false)
    private String currency;

    @Column(name = "terms_days", nullable = false)
    private int termsDays;

    @Column(name = "total_amount")
    private BigDecimal totalAmount;

    @Column(name = "bill_to_id")
    private UUID billToId;

    @Column(name = "issued_at")
    private Instant issuedAt;

    @Column(name = "issued_by")
    private UUID issuedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    /** JPA only. */
    protected Invoice() {
    }

    static Invoice draft(
            UUID id,
            UUID companyId,
            UUID clientId,
            LocalDate periodStart,
            LocalDate periodEnd,
            String currency,
            int termsDays,
            UUID billToId,
            Instant now) {

        Invoice drafted = new Invoice();
        drafted.id = id;
        drafted.companyId = companyId;
        drafted.clientId = clientId;
        drafted.periodStart = periodStart;
        drafted.periodEnd = periodEnd;
        drafted.status = InvoiceStatus.DRAFT;
        drafted.currency = currency;
        drafted.termsDays = termsDays;
        drafted.billToId = billToId;
        drafted.createdAt = now;
        return drafted;
    }

    /**
     * The one state transition on this row, and all four issued columns move together.
     *
     * <p>Split into four setters they would be four ways to leave the row half-issued, which
     * {@code ck_invoice_issued} would then refuse with a constraint violation naming none of them.
     * There is no inverse: nothing on this surface unissues an invoice, because nothing unsends a
     * document.
     */
    void issue(long allocatedSeq, String rendered, BigDecimal total, UUID by, Instant at) {
        this.seq = allocatedSeq;
        this.number = rendered;
        this.totalAmount = total;
        this.issuedBy = by;
        this.issuedAt = at;
        this.status = InvoiceStatus.ISSUED;
    }

    UUID getId() {
        return id;
    }

    UUID getCompanyId() {
        return companyId;
    }

    UUID getClientId() {
        return clientId;
    }

    String getNumber() {
        return number;
    }

    LocalDate getPeriodStart() {
        return periodStart;
    }

    LocalDate getPeriodEnd() {
        return periodEnd;
    }

    InvoiceStatus getStatus() {
        return status;
    }

    String getCurrency() {
        return currency;
    }

    int getTermsDays() {
        return termsDays;
    }

    BigDecimal getTotalAmount() {
        return totalAmount;
    }

    UUID getBillToId() {
        return billToId;
    }

    Instant getIssuedAt() {
        return issuedAt;
    }

    UUID getIssuedBy() {
        return issuedBy;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    long getLockVersion() {
        return lockVersion;
    }
}
