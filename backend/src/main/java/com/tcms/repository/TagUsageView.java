package com.tcms.repository;

/**
 * {@code TagUsage} on the wire — one tag in use in a project, and how many live cases carry it.
 *
 * <p>The count is what makes the list usable rather than merely present: a picker offering forty
 * tags in an arbitrary order is a list nobody reads, and "smoke (128)" beside "smoek (1)" is how a
 * typo gets noticed at the moment somebody is about to repeat it. Free text has no other correction
 * mechanism — there is no vocabulary table to retire a value from.
 *
 * @param caseCount live cases only, so this is never zero: a tag whose every case was deleted is
 *                  absent from the list rather than present with a count of nothing
 */
public record TagUsageView(String tag, long caseCount) {
}
