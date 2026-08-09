/**
 * Identity — who a person is, which companies they belong to, and what they may do.
 *
 * <p>The module is split across the RLS boundary, and which side a table sits on is the
 * single most important thing to know before adding to it (ERD §0.1):
 *
 * <table border="1">
 *   <caption>Identity tables by isolation class</caption>
 *   <tr><th>Instance-level — no {@code company_id}, no RLS</th>
 *       <th>Company-owned — {@code company_id NOT NULL}, FORCE RLS</th></tr>
 *   <tr><td>{@code app_user}, {@code role}, {@code permission},
 *           {@code role_permission}, {@code instance_role_assignment}</td>
 *       <td>{@code company_membership}, {@code team}, {@code team_membership},
 *           {@code role_assignment}</td></tr>
 * </table>
 *
 * <p>{@code app_user} is instance-level because one account may belong to several
 * companies with independent permissions in each (plan §6s), which is also why email is
 * unique instance-wide and why no admin below Instance Admin may enumerate a user's
 * other memberships. {@code instance_role_assignment} is instance-level because a
 * SYSTEM grant modelled as a NULL {@code company_id} would be invisible under RLS in
 * every company context, permanently — measured, not reasoned; see
 * {@code docs/audits/2026-07-30-v1-schema-first-execution.md}.
 *
 * <h2>The one entry point for "may they?"</h2>
 *
 * <p>{@link com.tcms.identity.PermissionEvaluator} is the only place effective
 * permissions are computed. Nothing checks a role <em>name</em> — code checks
 * permissions, and there is no wildcard permission (plan §6b). Both rules exist so
 * adding a custom-role designer later stays additive.
 *
 * <h2>Entities carry plain UUID foreign keys, not associations</h2>
 *
 * <p>Matching {@code com.tcms.project.Project}: the graph is never navigated from an
 * entity here, and the evaluator reads its union through native projections rather than
 * loading objects. Associations would buy lazy-loading traps and nothing else.
 *
 * <h2>Allowed dependencies</h2>
 *
 * <p>{@code tenancy} and {@code project}. The {@code project} edge is real and one-way:
 * {@link com.tcms.identity.PermissionEvaluator} resolves the project through the
 * company-filtered lookup before unioning any grant, which is the fix for the
 * COMPANY-scope leak recorded in {@code docs/HANDOFF.md} §13 — asking about a foreign
 * project must be answerable, and the only module that can answer it is
 * {@code project}. {@code project} must never depend back (see its package comment).
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"api", "tenancy", "project"})
package com.tcms.identity;
