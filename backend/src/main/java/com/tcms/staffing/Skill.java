package com.tcms.staffing;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * One word in a company's skill vocabulary — "Playwright", "SAP FI", whatever the client's product
 * is built on.
 *
 * <p><strong>{@link #name} is {@code CITEXT} and the {@code columnDefinition} is load-bearing.</strong>
 * {@code citext} is an extension type that reports as {@code Types#OTHER} while a plain
 * {@code String} field maps to {@code varchar}, so {@code ddl-auto: validate} refuses to start with
 * <em>"found [citext (Types#OTHER)], but expecting [varchar(255)]"</em> without it — a failure at
 * boot, which surfaces as every {@code @SpringBootTest} failing at once rather than as a compile
 * error. {@code AppUser.email} and {@code CompanyEmailDomain.domain} both carry it for the same
 * reason, and a query comparing the column to a bound parameter has to cast
 * ({@code SkillRepository.findByName}, as {@code AppUserRepository} does).
 *
 * <p>Case-folded because a directory whose filter misses half the people who have the skill is
 * worse than no filter: "Playwright" and "playwright" must not become two skills.
 *
 * <p>No {@code lock_version} and no {@code updated_at}: the contract has {@code POST} and
 * {@code GET} on this resource and nothing else, so a skill is created and listed but never edited.
 * A rename arrives as a column on the day a screen renames one.
 */
@Entity
@Table(name = "skill")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class Skill {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "name", nullable = false, columnDefinition = "citext")
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA only. */
    protected Skill() {
    }

    static Skill create(UUID id, UUID companyId, String name, Instant now) {
        Skill created = new Skill();
        created.id = id;
        created.companyId = companyId;
        created.name = name;
        created.createdAt = now;
        return created;
    }

    UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }
}
