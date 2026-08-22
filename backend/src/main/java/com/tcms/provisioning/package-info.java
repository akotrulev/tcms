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
 *
 * <p>The leaf property is asserted by {@code ArchitectureRulesTest}, which Spring
 * Modulith cannot express: {@code allowedDependencies} constrains what a module reaches
 * <em>out</em> to, never who reaches <em>in</em>. The one permitted inbound edge is from
 * the composition root {@code com.tcms} — {@code TcmsApplication} decides from the raw
 * arguments whether the process is the server or the CLI, and that decision has to be
 * made before the context exists.
 */
@org.springframework.modulith.ApplicationModule(
        // `storage` is the fourth entry, added for `export-company` (§NN). It is one of
        // ArchitectureRules.FOUNDATION_MODULES — the five every module may depend on — and
        // `documentation`, `execution`, `reporting` and `repository` already declare it. The
        // export has to put the BYTES its rows name into the archive, and the three blob ports
        // are the only way to reach them; the alternative is this module learning the filesystem
        // layout, which is exactly what the ports exist to stop. Note what this is NOT: a widened
        // list standing in for a port. `project`'s header warns against that, and the warning is
        // about reaching sideways into a peer — `storage` is beneath everything by construction.
        allowedDependencies = {"tenancy", "identity", "project", "storage"})
package com.tcms.provisioning;
