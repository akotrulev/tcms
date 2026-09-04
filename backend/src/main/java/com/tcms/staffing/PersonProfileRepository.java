package com.tcms.staffing;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The profiles, looked up by person.
 *
 * <p><strong>{@code @Transactional(readOnly = true)} on the interface</strong>, and it is the rule
 * every repository in this codebase follows for a reason that is silent when broken: Spring Data
 * gives a transaction only to the inherited CRUD methods, so a derived or {@code @Query} method
 * would otherwise run with no company set, and every RLS-protected read would return zero rows —
 * fail-closed, no error, no empty-state, just nothing. {@code RepositoryTransactionTest} both
 * reproduces that and fails the build on a repository missing this line.
 *
 * <p>Both lookups seek {@code uq_person_profile}, the {@code UNIQUE (company_id, user_id)}
 * constraint. There is deliberately <strong>no {@code ix_person_profile_user}</strong>: a unique
 * constraint <em>is</em> a btree in exactly that column order, and a second copy of it would double
 * the write cost of every profile edit and be chosen by the planner never (V10 §1).
 */
@Transactional(readOnly = true)
interface PersonProfileRepository extends JpaRepository<PersonProfile, UUID> {

    Optional<PersonProfile> findByCompanyIdAndUserId(UUID companyId, UUID userId);

    /** Every profile behind a page of people, in one query rather than one per row. */
    List<PersonProfile> findByCompanyIdAndUserIdIn(UUID companyId, Collection<UUID> userIds);
}
