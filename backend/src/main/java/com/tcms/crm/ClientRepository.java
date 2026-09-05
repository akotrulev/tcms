package com.tcms.crm;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * The company's clients.
 *
 * <p><strong>{@code @Transactional(readOnly = true)} on the INTERFACE is load-bearing.</strong>
 * Spring Data gives a transaction only to the inherited CRUD methods, so a derived or {@code @Query}
 * method without it runs with no company set on the connection and every RLS-protected read returns
 * <strong>zero rows</strong> — fail-closed, silent, and here indistinguishable from the one state
 * this module must render precisely: a company that has no clients.
 */
@Transactional(readOnly = true)
interface ClientRepository extends JpaRepository<Client, UUID> {

    List<Client> findByStatusOrderByNameAsc(ClientStatus status);

    List<Client> findByStatusAndAccountManagerIdOrderByNameAsc(ClientStatus status, UUID managerId);

    List<Client> findAllByOrderByNameAsc();

    List<Client> findByAccountManagerIdOrderByNameAsc(UUID managerId);

    List<Client> findByIdIn(Collection<UUID> ids);

    /**
     * How many projects name this client — what the {@code 409} on delete is computed from.
     *
     * <p><strong>Archived projects are counted.</strong> An archived project still names its client
     * and is still fully writable — {@code Project.archived}'s own contract note says the flag is a
     * label rather than a lock — so excluding them would let a delete through that leaves a live
     * project pointing at a row the foreign key then refuses to remove. The visible failure would be
     * a {@code 500} where the service had already promised a {@code 204}.
     *
     * <p>A JPQL count over {@code project} and not a native query: {@code Project} is a mapped entity
     * in a foundation module this one may import, so there is nothing here that needs to reach around
     * the mapping. The Hibernate company filter applies to it, and RLS applies underneath.
     */
    @Query("SELECT COUNT(p) FROM Project p WHERE p.clientId = :clientId")
    long countProjectsNaming(@Param("clientId") UUID clientId);
}
