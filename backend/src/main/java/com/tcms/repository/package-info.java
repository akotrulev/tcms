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
 * <p>{@code tenancy} and {@code project}. The first is the isolation boundary every
 * company-owned module needs. The second is what makes a suite reachable at all: every
 * read here is gated by {@link com.tcms.project.ProjectService#readVisible}, so a caller
 * passes exactly the same two gates as on {@code GET /projects/{projectId}} — company
 * first, then the explicit project grant.
 *
 * <p><strong>Not {@code identity}.</strong> This module never asks who the caller is or
 * what they hold; it hands a user id to {@code project} and is given back either the
 * project or nothing. A second answer to "may they?" is the bug class plan §6b names, and
 * the quieter of two answers is the one that decides what a client can read.
 *
 * <p>Everything else crosses by event. In particular {@code execution} must not be
 * imported when run results start rolling up against suites — {@code suite_run_summary}
 * is a read model in {@code reporting}, fed by events, per §3 lesson 4.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"tenancy", "project"})
package com.tcms.repository;
