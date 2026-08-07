/**
 * Test repository — the suite tree and, later, the cases inside it (ERD §4).
 *
 * <p>A <em>suite</em> is a folder in one project's tree: "Payments" › "Refunds" ›
 * "Partial refund". It is not the feature dimension, which classifies a case along an
 * orthogonal axis (plan §6d) — the two are deliberately separate, and this module owns
 * only the tree.
 *
 * <p>The name is the plan's (§3), and it means <em>the test repository</em>. It is not a
 * layer name: nothing here is "the persistence layer for the application", and a Spring
 * Data interface in another module does not belong in this package.
 *
 * <h2>Allowed dependencies</h2>
 *
 * <p>{@code tenancy}, {@code project} and {@code identity}. The first is the isolation
 * boundary every company-owned module needs. The second is what makes a suite or a case
 * reachable at all: every read here is gated by
 * {@link com.tcms.project.ProjectService#readVisible}, so a caller passes exactly the same
 * two gates as on {@code GET /projects/{projectId}} — company first, then the explicit
 * project grant.
 *
 * <p><strong>{@code identity} is on that list, and what it is for is narrow.</strong> It
 * was deliberately absent until the case list, whose every row names an owner: a grid
 * showing a person needs that person's display name and email, resolved through the
 * <em>active company's</em> membership (plan §6u). {@code app_user.id} is the permanent key
 * for exactly this (§3 lesson 10), the edge is one the architecture allows, and there is no
 * cycle — {@code identity} depends on {@code tenancy} and {@code project}, never on this
 * module. {@link com.tcms.repository.CaseOwners} is the only class that uses it, and it is
 * the only one that may.
 *
 * <p><strong>What that edge is not, and this is the part worth keeping:</strong> this
 * module still never asks {@code identity} whether a caller <em>may</em> do anything. It
 * hands a user id to {@code project} and is given back either the project or nothing.
 * "Who is this person" and "may they?" are separate questions with separate failure modes,
 * and only the second may have exactly one answer — a second one is the bug class plan §6b
 * names, and the quieter of two answers is the one that decides what a client can read. A
 * permission check appearing anywhere in this package is a defect regardless of what this
 * list permits.
 *
 * <p>Everything else crosses by event. In particular {@code execution} must not be
 * imported when run results start rolling up against suites — {@code suite_run_summary}
 * is a read model in {@code reporting}, fed by events, per §3 lesson 4. The same rule is
 * what keeps {@code TestCaseSummary.lastResult} off the wire today rather than reaching
 * for {@code test_execution} directly.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"tenancy", "project", "identity", "storage"})
package com.tcms.repository;
