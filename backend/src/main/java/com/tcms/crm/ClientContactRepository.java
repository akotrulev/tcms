package com.tcms.crm;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * A client's contacts.
 *
 * <p>{@code @Transactional(readOnly = true)} on the interface — see {@link ClientRepository} for the
 * silent zero-row failure that prevents.
 *
 * <p>There is no {@code findByEmail} and there will not be one. Looking a person up by email is what
 * an identity table is for, and this one deliberately is not: {@code email} here is neither unique
 * nor case-folded, so such a method would return a list and read like a login lookup that happens to
 * be broken.
 */
@Transactional(readOnly = true)
interface ClientContactRepository extends JpaRepository<ClientContact, UUID> {

    List<ClientContact> findByClientIdOrderByNameAsc(UUID clientId);
}
