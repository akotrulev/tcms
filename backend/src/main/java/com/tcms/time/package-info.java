/**
 * Time — hours logged against an issue, a meeting or leave, and the periods they are approved in
 * (009 module M6, plan 016).
 *
 * <h2>What this module may not do, stated first because it is the failure the scope names</h2>
 *
 * <p>It <strong>reads the tracker's issue projection and never writes it</strong> (009 review R10,
 * scope §3). That table has exactly one writer, {@code tracker.IssueFactProjection}, fed by the
 * {@code tracker.issues_changed} outbox event. The moment a downstream module writes it,
 * {@code tracker} is no longer the authority on issue state and the two disagree <em>silently</em>
 * — no error, no empty screen, just two numbers that stop matching.
 *
 * <p>The whole crossing lives in {@link com.tcms.time.IssueFacts}, which issues read-only SQL and
 * returns records declared here. <strong>Nothing else in this package may name a foreign
 * table.</strong> Third instance of a pattern with a name in this codebase: {@code work.BoardFacts}
 * reads {@code tracker}, {@code execution.CaseFacts} reads {@code repository}.
 * {@code TimeWritesNoIssueFactTest} asserts both halves — that nothing here writes the projection,
 * and that exactly one file names it.
 *
 * <h2>The other thing it may not do</h2>
 *
 * <p><strong>No rate, no cost, no invoice value — not in a table, a record, a response or a log
 * line.</strong> Scope §7 calls money the sharpest boundary in the expansion: {@code billing:*} and
 * {@code ratecard:read} are Finance and Company Admin only, and a Test Engineer with
 * {@code time:log} logs hours and cannot see what they cost. {@code TimeMoneyBoundaryTest} asserts
 * the negative rather than reading the matrix and concluding it holds, and it is written here,
 * before M8 exists, so M8 inherits it.
 *
 * <h2>The rollup is maintained synchronously, and that is a decision (016 D-h)</h2>
 *
 * <p>{@code issue_time_rollup} is this module's own projection and this module owns both sides of
 * it, so there is no boundary to cross and therefore no {@code CompanyScopedEvent}: the outbox
 * exists to cross boundaries and has a delivery floor around a minute, while the story panel must
 * be right the moment Approve is clicked. {@link com.tcms.time.RollupService} rewrites the affected
 * issues inside the same transaction as the write that moved them.
 *
 * <h2>Allowed dependencies</h2>
 *
 * <p>The five foundation modules. {@code project} carries the isolation boundary and the visibility
 * gate ({@link com.tcms.project.ProjectService#readVisible}); {@code identity} is asked <em>what is
 * this person called</em> and never <em>may they</em>, as in {@code repository}, {@code execution}
 * and {@code work} — the permission question goes through {@code project.ProjectService}, which is
 * the only place in this module that decides anything about access.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"api", "tenancy", "identity", "project", "storage"})
package com.tcms.time;
