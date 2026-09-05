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
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * One rung of one client's rate ladder — {@code billing.html}'s <em>Applies to · Rate · Effective</em>
 * columns.
 *
 * <p><strong>{@link #effectiveFrom} is on the entry and not on the card</strong> (019 D-d). The rate
 * for an hour is the most specific entry effective on or before that hour's {@code spent_on}, so an
 * entry added in July does not reprice June. {@link PricingService} is the only place that walks
 * this, and {@code invoice_line.rate_applied} is where the answer is frozen one step downstream.
 *
 * <p>{@link #personId} and {@link #roleId} are nullable per {@link #scope}, matched by
 * {@code ck_rate_card_entry_shape}: {@code PERSON} needs the first, {@code ROLE} the second,
 * {@code ALL_ROLES} neither. Both are raw ids rather than associations — {@code app_user} and
 * {@code role} are {@code identity}'s, and {@code role} is instance-level reference data on ERD
 * §0.1's exception list, so the isolation lives on this row's {@code company_id} and not on the row
 * it points at.
 *
 * <p><strong>No {@code lock_version} and no {@code updated_at}</strong>, because an entry is never
 * updated: {@code PUT /rate-cards/{id}} is a full replacement, so the whole entry set is deleted and
 * written again. Optimistic locking guards a concurrent {@code UPDATE} and there is none to guard;
 * the card carries the version, which is what a concurrent replacement actually races on.
 */
@Entity
@Table(name = "rate_card_entry")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class RateCardEntry {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "rate_card_id", nullable = false, updatable = false)
    private UUID rateCardId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false)
    private RateCardScope scope;

    @Column(name = "person_id")
    private UUID personId;

    @Column(name = "role_id")
    private UUID roleId;

    @Column(name = "hourly_rate", nullable = false)
    private BigDecimal hourlyRate;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA only. */
    protected RateCardEntry() {
    }

    static RateCardEntry create(
            UUID id,
            UUID companyId,
            UUID rateCardId,
            RateCardScope scope,
            UUID personId,
            UUID roleId,
            BigDecimal hourlyRate,
            LocalDate effectiveFrom,
            Instant now) {

        RateCardEntry created = new RateCardEntry();
        created.id = id;
        created.companyId = companyId;
        created.rateCardId = rateCardId;
        created.scope = scope;
        created.personId = personId;
        created.roleId = roleId;
        created.hourlyRate = hourlyRate;
        created.effectiveFrom = effectiveFrom;
        created.createdAt = now;
        return created;
    }

    UUID getId() {
        return id;
    }

    UUID getCompanyId() {
        return companyId;
    }

    UUID getRateCardId() {
        return rateCardId;
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

    BigDecimal getHourlyRate() {
        return hourlyRate;
    }

    LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
