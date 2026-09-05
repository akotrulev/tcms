package com.tcms.billing;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * <strong>Written once, inside the issue transaction, and never updated.</strong>
 *
 * <p>There is no {@code apply}, no setter and no {@code @Version} on this class, and none of the
 * three is an omission (019 D-c). Optimistic locking guards a concurrent {@code UPDATE} and nothing
 * updates these rows — that is what <em>"the lines lock on issue"</em> means here. The immutability
 * is <strong>structural</strong>: there is no operation that can change a line because none was
 * built, rather than a guard on the sixth handler somebody forgets. The proof is not that a
 * {@code PUT} returns 405; it is that issuing, editing the rate card and re-reading returns a
 * byte-identical line, which is what {@code InvoiceLifecycleTest} asserts.
 *
 * <p>{@link #rateApplied} is <strong>a number, not a {@code rate_card_entry} id</strong>. A card
 * edited next month must not reprice an invoice sent last month, and a reference resolved at read
 * time would do exactly that. {@link #appliesTo} is the resolved label for the same reason — the row
 * must still read correctly after the person leaves and the role is renamed.
 *
 * <p>{@link #personId} and {@link #roleId} are kept beside it <strong>for grouping, not for
 * rendering</strong>: they are what {@link InvoiceService}'s line key is built from on both the
 * computed and the stored path, so one grouping rule serves a draft and an issued invoice. They are
 * the copies that may go stale; {@link #appliesTo} is the copy that may not.
 *
 * <p>{@link #amount} is stored rather than derived as {@code hours × rateApplied}, and the rounding
 * is why: a line total is what was <em>sent</em> to the client, to the cent they may already have
 * paid — not a product two readers can round two ways.
 */
@Entity
@Table(name = "invoice_line")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class InvoiceLine {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private UUID invoiceId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, updatable = false)
    private RateCardScope scope;

    @Column(name = "person_id", updatable = false)
    private UUID personId;

    @Column(name = "role_id", updatable = false)
    private UUID roleId;

    @Column(name = "applies_to", nullable = false, updatable = false)
    private String appliesTo;

    @Column(name = "hours", nullable = false, updatable = false)
    private BigDecimal hours;

    @Column(name = "rate_applied", nullable = false, updatable = false)
    private BigDecimal rateApplied;

    @Column(name = "amount", nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA only. */
    protected InvoiceLine() {
    }

    static InvoiceLine record(
            UUID id,
            UUID companyId,
            UUID invoiceId,
            UUID projectId,
            RateCardScope scope,
            UUID personId,
            UUID roleId,
            String appliesTo,
            BigDecimal hours,
            BigDecimal rateApplied,
            BigDecimal amount,
            Instant now) {

        InvoiceLine written = new InvoiceLine();
        written.id = id;
        written.companyId = companyId;
        written.invoiceId = invoiceId;
        written.projectId = projectId;
        written.scope = scope;
        written.personId = personId;
        written.roleId = roleId;
        written.appliesTo = appliesTo;
        written.hours = hours;
        written.rateApplied = rateApplied;
        written.amount = amount;
        written.createdAt = now;
        return written;
    }

    UUID getId() {
        return id;
    }

    UUID getCompanyId() {
        return companyId;
    }

    UUID getInvoiceId() {
        return invoiceId;
    }

    UUID getProjectId() {
        return projectId;
    }

    RateCardScope getScope() {
        return scope;
    }

    UUID getPersonId() {
        return personId;
    }

    UUID getRoleId() {
        return roleId;
    }

    String getAppliesTo() {
        return appliesTo;
    }

    BigDecimal getHours() {
        return hours;
    }

    BigDecimal getRateApplied() {
        return rateApplied;
    }

    BigDecimal getAmount() {
        return amount;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
