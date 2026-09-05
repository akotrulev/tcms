package com.tcms.billing;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * One ceiling per project, or none.
 *
 * <p>{@code @Transactional(readOnly = true)} on the interface for {@link RateCardRepository}'s
 * reason. Here the silent-zero-rows failure would be its own defect rather than a missing row: a
 * project with a budget would read as <em>no budget set</em>, which is a legitimate state, so the
 * screen would render correctly and be wrong.
 */
@Transactional(readOnly = true)
interface BudgetRepository extends JpaRepository<Budget, UUID> {

    /** {@code uq_budget_project} is what makes this an {@link Optional}. */
    Optional<Budget> findByProjectId(UUID projectId);

    /**
     * The budgets among a set of projects — the company table, and the client filter, which are the
     * same question asked of a narrower project set.
     */
    List<Budget> findByProjectIdIn(Collection<UUID> projectIds);
}
