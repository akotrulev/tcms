/**
 * Project — a full client project, an entire product or engagement, not a feature area
 * (plan §1). "Acme Online Banking" is a project; "Payments" is a feature or a suite
 * folder. A company holds a handful of whole products, not dozens of features.
 *
 * <p>Company membership conveys authentication and <strong>zero</strong> project
 * visibility: a regular user sees only the projects they hold an explicit grant on —
 * not the company's project list, names, or counts (plan §6b). That second gate lands
 * with the permission evaluator in M0.5; this module currently enforces only the
 * company gate.
 *
 * <h2>Allowed dependencies</h2>
 *
 * <p>{@code tenancy} only. A project is company-owned, so it needs the boundary and
 * nothing else. In particular it must <strong>not</strong> reach into {@code identity}:
 * the second gate (does this user hold a grant on this project?) is answered by
 * {@code identity}'s evaluator asking about a project, not by a project asking about a
 * user — and reversing that would put the two modules in a cycle.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = "tenancy")
package com.tcms.project;
