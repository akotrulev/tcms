/**
 * Project — a full client project, an entire product or engagement, not a feature area
 * (plan §1). "Acme Online Banking" is a project; "Payments" is a feature or a suite
 * folder. A company holds a handful of whole products, not dozens of features.
 *
 * <p>Company membership conveys authentication and <strong>zero</strong> project
 * visibility: a regular user sees only the projects they hold an explicit grant on —
 * not the company's project list, names, or counts (plan §6b). <strong>Both gates are
 * enforced here now</strong> — {@code ProjectService} applies the grant gate to every
 * row the company filter admits, so {@code GET /projects} is the first endpoint where
 * the second gate is observable over HTTP.
 *
 * <h2>Allowed dependencies</h2>
 *
 * <p>{@code tenancy} only. A project is company-owned, so it needs the boundary and
 * nothing else. In particular it must <strong>not</strong> reach into {@code identity}:
 * the second gate (does this user hold a grant on this project?) is answered by
 * {@code identity}'s evaluator asking about a project, not by a project asking about a
 * user — and reversing that would put the two modules in a cycle.
 *
 * <p>That constraint is what {@link com.tcms.project.ProjectAccess} is for. Listing
 * projects genuinely needs an answer only {@code identity} has, so the dependency is
 * <strong>inverted rather than reversed</strong>: this module declares the port, and
 * {@code identity} — which may already see this one — implements it
 * ({@code IdentityProjectAccess}). The arrow still points one way, and nothing was added
 * to the list above to make the endpoint possible. If Modulith ever fails on this edge,
 * the fix is a port, never a wider {@code allowedDependencies}.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"api", "tenancy"})
package com.tcms.project;
