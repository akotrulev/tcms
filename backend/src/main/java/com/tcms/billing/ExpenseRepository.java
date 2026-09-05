package com.tcms.billing;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Costs recorded against a project and charged to nobody (019 D-j).
 *
 * <p>{@code @Transactional(readOnly = true)} on the interface for {@link RateCardRepository}'s
 * reason.
 *
 * <p>One derived method rather than two, and the absent {@code from}/{@code to} are widened to a
 * range no recorded date can fall outside rather than branching: two methods would be two orderings
 * to keep in step, and the filter is a filter in both cases. {@link ExpenseService} is where the
 * widening is written and says which bounds it uses and why they are not {@link LocalDate#MIN}.
 */
@Transactional(readOnly = true)
interface ExpenseRepository extends JpaRepository<Expense, UUID> {

    List<Expense> findByProjectIdAndIncurredOnBetweenOrderByIncurredOnDescCreatedAtDesc(
            UUID projectId, LocalDate from, LocalDate to);
}
