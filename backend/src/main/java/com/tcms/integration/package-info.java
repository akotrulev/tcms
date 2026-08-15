/**
 * Integration — where this product talks to something outside itself (plan §5).
 *
 * <p>Today that is notifications: a project's channels, what each is subscribed to, the three
 * adapters that deliver, and the log of what was attempted. Plan §5's module list names the rest —
 * Jira, Git traceability, outbound webhooks — and they share this module and nothing else, which
 * is why they are not built here: three unrelated things behind one port would make the port a
 * union rather than an abstraction.
 *
 * <h2>THIS IS NOT A FOUNDATION MODULE AND MUST NOT BECOME ONE</h2>
 *
 * <p>§125 admitted {@code storage} as a fourth on three stated grounds: it declares {@code tenancy}
 * alone, it holds no domain concept, and it answers a question the database cannot. This module
 * fails the second outright. A notification channel is a domain concept with a lifecycle, an owner
 * and a permission ({@code integration:manage}), so it is exactly the shape
 * {@code ArchitectureRules.FOUNDATION_MODULES} says a feature module proposing itself will not
 * clear. <strong>Nothing may depend on this package.</strong>
 *
 * <h2>Allowed dependencies, and the one the plan named that is not here</h2>
 *
 * <p>{@code api}, {@code tenancy} and {@code project}. The plan's §2 also named {@code identity},
 * and it is left off because nothing here reaches it: a person's display name arrives through
 * {@link com.tcms.project.PeopleDirectory}, which is {@code project}'s port precisely so that a
 * module which may not import {@code identity} can still ask <em>who is this</em>. A declaration
 * is a fact about code that exists rather than a forecast of what the module will want — a
 * forecast cannot fail the build when it stops being true — so the fourth entry arrives with its
 * first import.
 *
 * <p><strong>{@code execution} and {@code work} are not on the list and never may be</strong>, and
 * that constraint is the whole design rather than a rule being obeyed. Every fact this module
 * renders — a run finished, a task was assigned to somebody — belongs to a module it cannot see,
 * so the transactional outbox is not one mechanism among several, it is the only one available.
 * {@code ArchitectureRulesTest.crossModuleDependenciesPointOnlyAtTheFoundation} reads every import
 * out of this package, with no exemption for an event type.
 *
 * <h2>The outbox's payload arm has its first product caller here</h2>
 *
 * <p>Both events recorded before this slice are <em>coalesced state</em> events — "this company's
 * results moved" — whose handlers are whole-company recomputes that take no arguments, so no
 * payload has ever crossed a module boundary and been read.
 * {@link com.tcms.tenancy.Outbox#record}'s javadoc stated the debt the first real caller would
 * inherit: a producer-side test and a consumer-side test pinning the payload shape, because the
 * two modules may not share a class and the compiler therefore cannot see across the boundary.
 * {@code RunCompletedPayloadTest} exists on both sides for that reason, and
 * {@code NotificationDeliveryTest} drives producer to handler end to end, which is the half that
 * catches the two declarations drifting apart after both tests were updated.
 *
 * <h2>A credential lives here, which nothing else in this codebase has had to hold</h2>
 *
 * <p>An incoming-webhook URL <em>is</em> the authentication: anyone holding it can post into the
 * room. Unlike an API token a digest is not available, because the value has to be recoverable to
 * be used, so {@link com.tcms.integration.SecretCipher} encrypts it and the key comes from the
 * environment with no default of any kind (plan §6r; §108's lesson about {@code ${VAR:?…}} taken
 * literally). Three rules follow it everywhere and each has a test: the plaintext is never
 * returned to a caller, never put in an outbox payload, and never written into a delivery error.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"api", "tenancy", "project"})
package com.tcms.integration;
