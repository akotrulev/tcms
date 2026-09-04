/**
 * Staffing — who is on a project and at what percentage, who has which skill, and when anybody is
 * free (009 module M7, plan 017).
 *
 * <h2>What this module may not do, stated first because it is the failure the scope names</h2>
 *
 * <p>It <strong>reads {@code time}'s tables and never writes them</strong> (017 D-j). The whole
 * crossing lives in {@link com.tcms.staffing.TimeFacts}, which issues read-only SQL and returns
 * records declared here; <strong>nothing else in this package may name a foreign table.</strong>
 * Fifth instance of a pattern with a name in this codebase: {@code work.BoardFacts} reads
 * {@code tracker}, {@code execution.CaseFacts} reads {@code repository},
 * {@code tracker.release.ReleaseFacts} reads {@code execution}, {@code time.IssueFacts} reads
 * {@code tracker}. {@code StaffingReadsNoTimeEntryTest} asserts both halves.
 *
 * <p>ArchUnit cannot see this rule: SQL is not a Java dependency, so the guard has to be a test over
 * file text. That is why it exists as a test and not as an allowed-dependency entry.
 *
 * <h2>The other thing it may not do</h2>
 *
 * <p><strong>No rate, no cost, no invoice value.</strong> An allocation is a percentage of
 * somebody's time; what that time is worth is M8's, and scope §7 calls it the sharpest boundary in
 * the expansion. {@code time.TimeMoneyBoundaryTest} was written before M8 existed so M8 would
 * inherit it, and the same argument applies here — an Account Manager sees the client, the hours and
 * the utilisation, and never a rate.
 *
 * <h2>Capacity is derived, and there is exactly one function that derives it</h2>
 *
 * <p>{@link com.tcms.staffing.Capacity} — see its own comment. Four surfaces show capacity and none
 * of them computes it, which is the only way "an absence zeroes capacity everywhere" is a fact
 * rather than a claim checked four times.
 *
 * <h2>Two scopes, and the split is load-bearing</h2>
 *
 * <p>{@code staffing:read} and {@code staffing:allocate} are PROJECT-scope and go through
 * {@link com.tcms.project.ProjectService}'s project gates. {@code staffing:directory},
 * {@code staffing:profile} and {@code staffing:absence} are COMPANY-scope and go through
 * {@link com.tcms.project.ProjectService#readInCompany}. Asking a COMPANY-scope key at project
 * scope is the "held anywhere in this company" shortcut the roles spec records rejecting; asking a
 * PROJECT-scope key company-wide is the unreachable row §43 repaired for {@code company:read}.
 *
 * <p>Two reads carry no staffing key at all and both are about the caller themselves:
 * {@code GET /staffing/capacity/me} (017 D-m) and recording or removing your own absence
 * (017 D-h) — the catalogue text for {@code staffing:absence} says <em>"record absence on somebody
 * else's behalf"</em>, and a product where telling it you are away on Thursday was an
 * administrative act would be one where nobody records absence at all.
 *
 * <h2>Allowed dependencies</h2>
 *
 * <p>The five foundation modules. {@code identity} is asked <em>what is this person called</em> and
 * <em>who is in this company</em>, never <em>may they</em> — the permission question goes through
 * {@code project.ProjectService}, which is the only place in this module that decides anything
 * about access, exactly as in {@code time}.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"api", "tenancy", "identity", "project", "storage"})
package com.tcms.staffing;
