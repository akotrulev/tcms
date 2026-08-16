package com.tcms.repository;

import java.util.UUID;

/**
 * How many things attach one library condition — the projection
 * {@link ConditionRepository#countCaseUsage} and {@link ConditionRepository#countSuiteUsage} both
 * return.
 *
 * <p>Shared by two queries here rather than split into two records,
 * because the pair is the same question asked of the two referencing tables and giving each its own
 * type would suggest the numbers are combinable. They are not — see {@link ConditionView}.
 *
 * @param conditionId the library entry
 * @param count       distinct attachers. A {@code long} because {@code COUNT} is one in HQL; the
 *                    view narrows it, since a library entry attached more than two billion times is
 *                    not a case this product needs to represent
 */
public record ConditionUsage(UUID conditionId, long count) {
}
