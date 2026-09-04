package com.tcms.staffing;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who holds which skill.
 *
 * <p>{@code @Transactional(readOnly = true)} on the interface — see
 * {@link PersonProfileRepository} for the failure that prevents.
 */
@Transactional(readOnly = true)
interface PersonSkillRepository extends JpaRepository<PersonSkill, PersonSkillId> {

    /**
     * Every chip for a page of people, in one query. Reads the {@code (company, user, skill)} PK.
     *
     * <p>There is deliberately <strong>no single-person variant</strong>. The one surface that shows
     * one person's chips is {@code GET /staffing/people/{userId}}, which goes through the same
     * assembly as the directory with a one-element list — so a {@code findByCompanyIdAndUserId}
     * beside this would be a second read path nothing calls, which is the shape
     * {@code ArchitectureRulesTest.everyDeclaredRepositoryMethodHasACaller} exists to report and did
     * report on the first run of this task.
     */
    List<PersonSkill> findByCompanyIdAndUserIdIn(UUID companyId, Collection<UUID> userIds);

    /**
     * <strong>The directory's "who knows Playwright and Java" filter, answered in one query.</strong>
     *
     * <p>The skills are <em>ANDed</em>, which is what the plural means: a person must hold every one
     * of them, so the {@code HAVING} counts distinct matches and compares against how many were
     * asked for. Doing it as an intersection in Java would be one query per skill and then a set
     * operation over whole-company lists; this reads {@code ix_person_skill_skill}, which V10 built
     * with {@code skill_id} leading for exactly this question.
     *
     * <p>The count is passed rather than derived from {@code :skillIds} because JPQL has no
     * {@code size(:collection)} — and passing it is also what makes a duplicated id in the query
     * string harmless, since the caller de-duplicates before counting.
     */
    @Query("""
            select ps.userId from PersonSkill ps
             where ps.companyId = :companyId
               and ps.skillId in :skillIds
             group by ps.userId
            having count(distinct ps.skillId) = :required
            """)
    List<UUID> holdingEveryOne(
            @Param("companyId") UUID companyId,
            @Param("skillIds") Collection<UUID> skillIds,
            @Param("required") long required);

    /**
     * Clears one person's chips, so {@code PUT …/skills} can write the set whole.
     *
     * <p>Delete-and-insert rather than a reconcile, for {@code PlanScopeItemRepository}'s reason: a
     * {@code person_skill} row carries no identity of its own, nothing references one, and the only
     * thing it holds beyond the key is {@code created_at} — so there is nothing a reconcile would
     * have preserved. It runs inside the caller's read-write transaction; the {@code readOnly} on
     * this interface applies to a transaction this repository starts, never to one it joins.
     */
    @Modifying
    @Query("DELETE FROM PersonSkill ps WHERE ps.companyId = :companyId AND ps.userId = :userId")
    void deleteEveryChipOf(@Param("companyId") UUID companyId, @Param("userId") UUID userId);
}
