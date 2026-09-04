package com.tcms.staffing;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * Which people know which skills — the join, and it carries {@code company_id} like every other
 * join table in this schema.
 *
 * <p>No proficiency level. {@code directory.html} draws chips with no level on them, and a level
 * nothing renders is a column three people would fill in inconsistently and a fourth would then
 * filter on.
 *
 * <p>The set is written whole rather than as a delta ({@code PUT …/skills}): a chip list is a set,
 * and a body naming one skill takes the others off.
 */
@Entity
@Table(name = "person_skill")
@IdClass(PersonSkillId.class)
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class PersonSkill {

    @Id
    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Id
    @Column(name = "skill_id", nullable = false, updatable = false)
    private UUID skillId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA only. */
    protected PersonSkill() {
    }

    static PersonSkill held(UUID companyId, UUID userId, UUID skillId, Instant now) {
        PersonSkill created = new PersonSkill();
        created.companyId = companyId;
        created.userId = userId;
        created.skillId = skillId;
        created.createdAt = now;
        return created;
    }

    UUID getUserId() {
        return userId;
    }

    UUID getSkillId() {
        return skillId;
    }
}
