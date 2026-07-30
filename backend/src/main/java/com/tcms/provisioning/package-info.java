/**
 * Provisioning — the operator lane that brings a client company into existence.
 *
 * <p>Companies are provisioned by a tested, idempotent CLI command, <strong>not by
 * self-signup and not through an admin UI</strong> (plan §6s). That single decision is
 * why the multi-company <em>feature</em> (M6) is small: no company CRUD screens, no ops
 * console, no quota UI, no archive state. It is also why this module exists at M0 while
 * the surface it enables ships last — a discriminator that has only ever held one value
 * is an untested claim.
 *
 * <h2>Why a module of its own</h2>
 *
 * <p>Provisioning composes {@code tenancy} (the company), {@code identity} (the first
 * Company Admin, their membership and their grant) and {@code project} (the optional
 * first project). Putting it inside any one of those three would create a sideways
 * dependency between the other two — {@code identity} reaching into {@code project}, or
 * {@code tenancy} losing its "depends on nothing" property, which the whole isolation
 * design rests on. Depending <em>toward</em> {@code tenancy}, {@code identity} and
 * {@code project} is exactly what the module rules permit from anywhere, so this is the
 * cheap direction.
 *
 * <p>It is a leaf: nothing depends on {@code provisioning}, and nothing should. If a
 * future admin API needs to create a company, it calls
 * {@link com.tcms.provisioning.CompanyProvisioningService} — it does not grow a second
 * provisioning path, because a company that exists without its vocabularies, its
 * display-key counters or an administrator is worse than no company at all.
 */
package com.tcms.provisioning;
