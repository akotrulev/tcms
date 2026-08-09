/**
 * Auth — proving who the caller is, and turning that into a request the rest of the
 * application can serve.
 *
 * <p>The first web surface in this codebase. Everything before it was reachable only from
 * a test or from the provisioning CLI, which is why this module is also where the tenancy
 * layer finally gets its production entry point:
 * {@link com.tcms.auth.CompanyContextFilter} is what puts a company into
 * {@link com.tcms.tenancy.CompanyContext} on a web request, so that
 * {@code CompanyAwareTransactionManager} has something to set the {@code app.current_company}
 * GUC from.
 *
 * <h2>The boundary this module holds</h2>
 *
 * <ol>
 *   <li><strong>The company is chosen server-side, once, at login</strong>, from the
 *       account's own ACTIVE memberships, and travels as a signed claim. No request
 *       parameter, header or path segment selects a company — ever. That rule is what makes
 *       56 RLS policies an invariant rather than a convention.</li>
 *   <li><strong>The token is a cache with an integrity check, not an authority.</strong> It
 *       carries no grants; every authorization question still goes to
 *       {@link com.tcms.identity.PermissionEvaluator}. The {@code pv} epoch is what keeps
 *       the session honest between issue and expiry (plan §6b).</li>
 *   <li><strong>No failure on a pre-authentication surface says why.</strong> Sign-in and
 *       invitation redemption each have exactly one refusal, and it costs the same time
 *       whichever cause produced it.</li>
 * </ol>
 *
 * <h2>Allowed dependencies</h2>
 *
 * <p>{@code tenancy} and {@code identity}. It composes them and owns no domain of its own —
 * no entity, no table. {@code project} is deliberately absent: the only project-shaped
 * question here is "what may this caller do on project X", which is asked <em>of</em> the
 * evaluator and answered by {@code identity}.
 *
 * <p>Like {@code provisioning}, this is a leaf — nothing may depend on it. A module reaching
 * in would be reaching for the session, and the session is not an application-wide ambient:
 * {@code CompanyContext} is.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"api", "tenancy", "identity"})
package com.tcms.auth;
