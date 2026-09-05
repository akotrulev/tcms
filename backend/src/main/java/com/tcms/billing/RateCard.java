package com.tcms.billing;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * One client's rate card — and there is exactly one per client ({@code uq_rate_card_client}).
 *
 * <p><strong>The currency lives here and not on the invoice</strong> (019 D-d). That is what makes
 * an invoice's currency a property of its client rather than a field on a form, and a cross-client
 * invoice structurally impossible rather than merely refused. {@code billing.html} carries a EUR
 * client and a CHF client two rows apart, which is the same fact seen from the screen.
 *
 * <p><strong>No effective date on the card.</strong> It is on {@link RateCardEntry}, because that
 * mockup's Acme rows read <em>from 1 Jan 2026</em> for two roles and <em>from 1 Jul 2026</em> for
 * one person. A card is a container; what changes over time is a rate.
 *
 * <p>{@link #clientId} is a raw id and not an association: {@code client} is {@code crm}'s table,
 * this module may not import that package, and the name comes from
 * {@code project.ClientDirectory}. An association would be the import the interface exists to avoid,
 * and {@code ddl-auto: validate} would then check two mappings over one table —
 * {@code execution.CaseFacts}' recorded reason for refusing that shape.
 */
@Entity
@Table(name = "rate_card")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class RateCard {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "client_id", nullable = false, updatable = false)
    private UUID clientId;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    /** JPA only. */
    protected RateCard() {
    }

    static RateCard create(UUID id, UUID companyId, UUID clientId, String currency, Instant now) {
        RateCard created = new RateCard();
        created.id = id;
        created.companyId = companyId;
        created.clientId = clientId;
        created.currency = currency;
        created.createdAt = now;
        created.updatedAt = now;
        return created;
    }

    /**
     * The card's own fields, on a {@code PUT}. The entries are replaced by
     * {@link RateCardService}, not here — a card does not own its entry rows as a JPA collection.
     *
     * <p><strong>The client is not among them.</strong> {@code RateCardInput.clientId} is ignored on
     * the {@code PUT} and the column is {@code updatable = false}: moving a card between clients
     * would silently reprice every draft on both sides, and there is no screen that asks for it.
     */
    void apply(String newCurrency, Instant now) {
        this.currency = newCurrency;
        this.updatedAt = now;
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

    String getCurrency() {
        return currency;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    long getLockVersion() {
        return lockVersion;
    }
}
