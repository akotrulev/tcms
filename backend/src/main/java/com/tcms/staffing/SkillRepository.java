package com.tcms.staffing;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * The company's skill vocabulary.
 *
 * <p>{@code @Transactional(readOnly = true)} on the interface — see
 * {@link PersonProfileRepository} for the failure that prevents.
 */
@Transactional(readOnly = true)
interface SkillRepository extends JpaRepository<Skill, UUID> {

    List<Skill> findByCompanyIdOrderByNameAsc(UUID companyId);

    List<Skill> findByCompanyIdAndIdIn(UUID companyId, Collection<UUID> ids);

    /**
     * The skill with this name, case-insensitively.
     *
     * <p><strong>The cast is required and a derived query is wrong here</strong>, for
     * {@code AppUserRepository.findByEmail}'s reason: Hibernate binds the parameter as
     * {@code varchar}, so {@code name = ?} against a {@code citext} column resolves through
     * {@code text} and compares <em>case-sensitively</em>. The unique index stays case-insensitive
     * either way, so the two would disagree — {@code POST /staffing/skills} would fail to find
     * "playwright", try to insert it, and be refused by {@code uq_skill_name} as a duplicate of
     * "Playwright". The visible symptom is a 409 for a skill the directory cannot show.
     *
     * <p>Native SQL is outside the Hibernate company filter, so the isolation here is RLS's: the
     * statement runs inside the caller's transaction, where {@code CompanyAwareTransactionManager}
     * has already set {@code app.current_company}. That is the backstop layer doing the job the
     * filter does for the derived methods above, and it is why {@code skill} carries a forced
     * policy rather than only an application predicate.
     */
    @Query(nativeQuery = true,
            value = "SELECT * FROM skill WHERE name = CAST(:name AS citext)")
    Optional<Skill> findByName(@Param("name") String name);
}
