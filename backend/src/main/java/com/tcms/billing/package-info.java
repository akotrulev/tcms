/**
 * Billing — what an approved hour is worth, what a project may spend, and the invoice that says so
 * (009 module M8, plan 019).
 *
 * <h2>What this module may not do, stated first because both are failures with names</h2>
 *
 * <p>It <strong>reads {@code time}'s entries and the tracker's issue projection, and writes
 * neither.</strong> The whole of the first crossing is {@link com.tcms.billing.ApprovedTimeFacts};
 * nothing else here may name {@code time_entry} or {@code timesheet_period}, and nothing may name
 * {@code issue_fact} until the class that owns that crossing arrives with the invoice surface.
 * {@code BillingReadsAndNeverWritesTest} asserts both halves over source text, because ArchUnit
 * cannot see the rule — SQL is not a Java dependency. Sixth instance of a pattern with a name here:
 * {@code work.BoardFacts}, {@code execution.CaseFacts}, {@code tracker.release.ReleaseFacts},
 * {@code time.IssueFacts}, {@code staffing.TimeFacts}.
 *
 * <p><strong>The class is {@code ApprovedTimeFacts} and not {@code TimeFacts}, and the rename is
 * load-bearing rather than a preference</strong> (019 D-r). {@code staffing.TimeFacts} is a
 * {@code @Component}, Spring's default bean name is the simple class name, and two of them collide
 * into a {@code ConflictingBeanDefinitionException} that stops the context booting —
 * {@code ArchitectureRulesTest.springStereotypesHaveDistinctSimpleNames} exists because §131 shipped
 * exactly that. The {@code *Facts} pattern had never repeated a name before this module; it does
 * now, twice, so both of ours are qualified by what they read.
 *
 * <p>It <strong>may not import {@code crm}.</strong> A client's name arrives through
 * {@link com.tcms.project.ClientDirectory}, declared in a foundation module and implemented by
 * {@code crm}. There is deliberately no {@code billing.ClientFacts}: the {@code *Facts} direction is
 * downstream-to-upstream, and {@code project} → {@code crm} is the reverse, held by an interface
 * precisely so that a rule ArchUnit cannot check is not the only thing keeping it honest. Where that
 * interface lacked what this module needed it grew a method — see
 * {@link com.tcms.project.ClientDirectory#activeClients()} — rather than this module growing a
 * query.
 *
 * <h2>{@code issue_time_rollup} is not read, and that is not an oversight</h2>
 *
 * <p>It carries approved and pending hours <em>per issue</em>. An invoice needs the entries — who,
 * when, how many, in which period — not their sum. 016 D-c made {@code time.RollupService} that
 * projection's only writer and named M8 as its first outside consumer; this module declines the
 * inheritance, because the question it asks is one that table cannot answer.
 *
 * <h2>Only approved hours against an issue can be priced</h2>
 *
 * <p>Two conditions, and neither is a courtesy filter. <strong>Approved</strong>, because 016 D-d
 * keeps approved and pending apart and nothing in this product sums them — pricing pending hours
 * would bill work nobody has signed for. And <strong>{@code subject_type = 'ISSUE'}</strong>,
 * because {@code ck_time_entry_subject_shape} makes {@code project_id} null for the other four
 * members: an hour with no project has no client, no rate card and no budget. A person's fortnight
 * can be entirely meetings and leave, and be a complete and correct period that produces no invoice
 * line at all.
 *
 * <h2>Allowed dependencies</h2>
 *
 * <p>Four of the five foundation modules; {@code storage} is not among them, because nothing here
 * has an attachment — {@code invoice.html}'s <em>Preview PDF</em> is not built and has no endpoint,
 * and {@code client.html}'s Contracts panel belongs to no module at all (018 D-h). The day either
 * arrives, this list grows by one entry and the growth is the record that it did.
 *
 * <p>{@code identity} is asked <em>what is this person called</em>, <em>what is this role called</em>
 * and <em>which roles is this person staffed with on this project</em> — never <em>may they</em>.
 * The third is a pricing question and not an authorization one: the rate ladder's middle rung is
 * "a {@code ROLE} entry naming a role that person actually holds on that project" (019 D-d), which
 * is a fact about how somebody is staffed and is read through
 * {@code identity.RoleAssignmentRepository}'s own projections so that no second query in this
 * codebase computes it. The permission question goes through {@code project.ProjectService},
 * exactly as in {@code time}, {@code staffing} and {@code crm}.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"api", "tenancy", "identity", "project"})
package com.tcms.billing;
