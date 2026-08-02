package com.tcms.repository;

import java.util.UUID;

/**
 * How many cases reference one library entry — the row shape of
 * {@link SharedStepRepository#countUsage}.
 *
 * <p>A record rather than {@code Object[]}: the query returns two columns of different types and
 * the array form puts the burden of remembering their order on every caller, which is the kind of
 * thing that stays correct until somebody adds a third column.
 *
 * @param sharedStepId the library entry
 * @param cases        distinct <em>live</em> cases referencing it. Distinct rather than a row count
 *                     because a case may reference the same entry twice, and counting rows would
 *                     report a library entry as more widely shared than it is — §6e's rule about
 *                     conflating an execution count with a distinct-case count, one layer down.
 */
public record SharedStepUsage(UUID sharedStepId, long cases) {
}
