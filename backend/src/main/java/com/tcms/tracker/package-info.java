/**
 * Tracker — epics, stories, tasks, and the workflow they move through (009 module M1).
 *
 * <h2>This is not, and will never be, a foundation module</h2>
 *
 * <p>{@code ArchitectureRules.FOUNDATION_MODULES} is {@code {api, tenancy, identity, project,
 * storage}} and its javadoc states the bar a sixth must clear. Two clauses are decisive: a
 * foundation module <em>holds no domain concept</em>, and it <em>answers a question the database
 * cannot</em>. This module holds the largest domain concept in the product. It fails both outright,
 * and §76 already refused a {@code review} module on the same bar — <em>a foundation module is a
 * decision, not an import</em>.
 *
 * <p>That is a real constraint, because in a project-management system nearly everything points at
 * an issue: a time entry, an invoice line, a board card, a release membership, a document link. So
 * <strong>downstream modules reference an issue polymorphically and keep their own
 * projection</strong> (scope §3). {@code time_entry} will carry {@code (subject_type, subject_id)};
 * {@code reporting} and {@code billing} join the {@code issue_fact} table. None of that machinery
 * is new — {@code work_item_link}, {@code doc_link}, {@code case_doc_link} and
 * {@code execution_fact} already pay exactly this price.
 *
 * <p><strong>What it costs, stated plainly:</strong> referential integrity between a time entry and
 * its issue is enforced in application code, not by the database. A deleted issue can orphan one.
 * The mitigation is soft delete ({@code issue.deleted_at}) plus a consistency check in the nightly
 * suite.
 *
 * <h2>The trap at the centre of the projection</h2>
 *
 * <p>{@code issue_fact} <strong>must be fed by an event and never written by {@code time} or
 * {@code billing}.</strong> The moment a downstream module writes it, this module is no longer the
 * authority on issue state and the two disagree <em>silently</em>. One writer, and it is here.
 *
 * <h2>Allowed dependencies</h2>
 *
 * <p>The five foundation modules. {@code project} carries three things this module cannot work
 * without: the isolation boundary, the gate ({@link com.tcms.project.ProjectService#readVisible} /
 * {@code #writeGranted}), and {@link com.tcms.project.ProjectKeys} — the per-project issue-key
 * allocator M0 landed one module ahead of its first caller. {@code identity} is asked <em>what is
 * this person called</em> and never <em>may they</em>, as in {@code repository},
 * {@code execution} and {@code work}.
 *
 * <p><strong>{@code documentation} is not on the list and must never be</strong>, and yet
 * {@code issue_doc_link} mirrors that module's table field for field. Same tension
 * {@code work.BoardFacts} resolves the same way: the whole edge lives in exactly one class, which
 * issues SQL naming {@code doc_link} and {@code doc_page} and returns records declared here.
 * Nothing else in this package may name a foreign table. The <em>write</em> is not here at all —
 * creating the link is {@code documentation}'s own endpoint, because that module owns
 * {@code doc_link} and a second writer is how two tables start disagreeing.
 *
 * <h2>What is deliberately not built here</h2>
 *
 * <p>Boards, rank, labels, releases, the timeline and the calendar — M2 and M3. A board is
 * <em>configuration over</em> {@link com.tcms.tracker.IssueStatus}, which is why D2 puts the
 * canonical status on the issue and lets a board map statuses onto columns: two boards over the
 * same work cannot then disagree about where a card is.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"api", "tenancy", "identity", "project", "storage"})
package com.tcms.tracker;
