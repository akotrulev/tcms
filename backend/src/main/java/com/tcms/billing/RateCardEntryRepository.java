package com.tcms.billing;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * A card's rungs.
 *
 * <p>{@code @Transactional(readOnly = true)} on the interface for {@link RateCardRepository}'s
 * reason, which is the same everywhere in this codebase and has bitten every module in it.
 *
 * <p>Ordered by {@code effectiveFrom} descending so the first matching row for a scope is the one
 * effective on the day worked — the ladder {@link PricingService} walks reads the list in this order
 * and stops. There is no {@code deleteByRateCardId}: a {@code PUT} is a full replacement, so the
 * service reads the entries it is about to replace and hands them to the inherited
 * {@code deleteAll}, which keeps the replaced set and the deleted set the same list rather than two
 * queries that can disagree.
 */
@Transactional(readOnly = true)
interface RateCardEntryRepository extends JpaRepository<RateCardEntry, UUID> {

    List<RateCardEntry> findByRateCardIdOrderByEffectiveFromDesc(UUID rateCardId);

    List<RateCardEntry> findByRateCardIdInOrderByEffectiveFromDesc(Collection<UUID> rateCardIds);
}
