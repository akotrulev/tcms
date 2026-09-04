package com.tcms.staffing;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link PersonSkill}'s composite key — {@code (company_id, user_id, skill_id)}, the join table's
 * own primary key in {@code V10}.
 *
 * <p>A class rather than a record because {@code @IdClass} requires a no-argument constructor and
 * mutable fields Hibernate can populate; the entity's own components carry the {@code @Id}
 * annotations and this only has to name them.
 *
 * <p>{@code company_id} is <em>in</em> the key rather than beside it, which is the join table's
 * whole point: {@code V1} shipped five join tables without the isolation column, and a join table
 * is the easiest place in a schema to forget it because both parents are isolated and the rows look
 * right in every one-company test.
 */
public class PersonSkillId implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID companyId;
    private UUID userId;
    private UUID skillId;

    /** JPA only. */
    public PersonSkillId() {
    }

    PersonSkillId(UUID companyId, UUID userId, UUID skillId) {
        this.companyId = companyId;
        this.userId = userId;
        this.skillId = skillId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PersonSkillId key)) {
            return false;
        }
        return Objects.equals(companyId, key.companyId)
                && Objects.equals(userId, key.userId)
                && Objects.equals(skillId, key.skillId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(companyId, userId, skillId);
    }
}
