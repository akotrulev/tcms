package com.tcms.execution;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * <em>"This test name means this case"</em>, confirmed by a person — {@code V1}'s
 * {@code automation_key_mapping}, <strong>first writer</strong>, and the third step of §6c's
 * matching ladder.
 *
 * <p>{@code V1}'s own comment states the point: <em>"once a human confirms 'this test name means
 * this case', the mapping survives renames of everything except the test itself."</em> That is what
 * separates it from the two steps above it in the ladder — {@code automation_key} is a fact somebody
 * wrote into the case, an embedded display key is a fact somebody wrote into the test, and both stop
 * working the moment either side is edited. This row is a fact about the <em>pair</em>, and it
 * survives both.
 *
 * <h2>The key is company-scoped, and the row is moved rather than duplicated</h2>
 *
 * <p>{@code uq_automation_mapping (company_id, normalized_test_name)} matches {@code automation_key}'s
 * own company scope (§6c) rather than the project's, so one name resolves to one case across the
 * client. Re-confirming a name against a different case therefore <strong>moves</strong> it —
 * {@link #confirm} rewrites {@code case_id} and re-stamps the confirmer — because a 409 would make
 * correcting a mis-binding a delete-then-create with a window in the middle where the CI job
 * silently stops matching.
 *
 * <p>Consequence worth stating: {@link #projectId} is on the row and is <strong>not</strong> part of
 * the key. Two projects in one company cannot claim the same test name, and the second confirmation
 * wins. That is the right answer if the name is genuinely one test, and the wrong one if two
 * projects happen to have identically-named tests — which is exactly the ambiguity §6c's
 * company-scoped {@code automationKey} already decides, in the same direction, for the same reason.
 * Deciding it differently here would put two answers in the product for one question.
 *
 * <h2>{@code normalized_test_name} is normalized by {@link IngestedResult#normalize}, in one place</h2>
 *
 * <p>The parser normalizes what it read and the writer normalizes what a human typed, and if the
 * two rules ever differ every mapping stops matching with nothing to see: the row is there, the test
 * ran, and nothing binds them. One static method, called from both.
 */
@Entity
@Table(name = "automation_key_mapping")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class AutomationKeyMapping {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "normalized_test_name", nullable = false, updatable = false)
    private String normalizedTestName;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    /**
     * Who confirmed it. Nullable in {@code V1} and this writer always fills it — the whole value of
     * the row is that a person vouched for it, and a mapping with no confirmer would be
     * indistinguishable from one the system guessed.
     */
    @Column(name = "confirmed_by")
    private UUID confirmedBy;

    @Column(name = "confirmed_at", nullable = false)
    private Instant confirmedAt;

    /** JPA only. */
    protected AutomationKeyMapping() {
    }

    static AutomationKeyMapping confirm(
            UUID companyId,
            UUID projectId,
            String normalizedTestName,
            UUID caseId,
            UUID userId,
            Instant now) {

        AutomationKeyMapping mapping = new AutomationKeyMapping();
        mapping.id = UUID.randomUUID();
        mapping.companyId = companyId;
        mapping.projectId = projectId;
        mapping.normalizedTestName = normalizedTestName;
        mapping.caseId = caseId;
        mapping.confirmedBy = userId;
        mapping.confirmedAt = now;
        return mapping;
    }

    /** Re-points an existing mapping — see the class header for why this moves rather than refuses. */
    void reconfirm(UUID projectId, UUID caseId, UUID userId, Instant now) {
        this.projectId = projectId;
        this.caseId = caseId;
        this.confirmedBy = userId;
        this.confirmedAt = now;
    }

    UUID getId() {
        return id;
    }

    UUID getProjectId() {
        return projectId;
    }

    String getNormalizedTestName() {
        return normalizedTestName;
    }

    UUID getCaseId() {
        return caseId;
    }

    UUID getConfirmedBy() {
        return confirmedBy;
    }

    Instant getConfirmedAt() {
        return confirmedAt;
    }
}
