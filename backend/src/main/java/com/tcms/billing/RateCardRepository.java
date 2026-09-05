package com.tcms.billing;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The company's rate cards, one per client.
 *
 * <p><strong>{@code @Transactional(readOnly = true)} on the INTERFACE is load-bearing.</strong>
 * Spring Data gives a transaction only to the inherited CRUD methods, so a derived or {@code @Query}
 * method without it runs with no company set on the connection and every RLS-protected read returns
 * <strong>zero rows</strong> — fail-closed, silent, and here indistinguishable from the state this
 * module must render precisely: a client that has no card yet, which is the state every new client
 * is in.
 */
@Transactional(readOnly = true)
interface RateCardRepository extends JpaRepository<RateCard, UUID> {

    /**
     * The one card a client may have — {@code uq_rate_card_client} is what makes this an
     * {@link Optional} rather than a list, and a second {@code POST} for the same client is the
     * {@code 409} this answers.
     */
    Optional<RateCard> findByClientId(UUID clientId);

    /** Every card among a set of clients, for the slot table and for pricing a whole company. */
    List<RateCard> findByClientIdIn(Collection<UUID> clientIds);
}
