/**
 * Importing a whole project out of Jira or Azure DevOps and into this one (009 module M4, plan 014).
 *
 * <h2>There is deliberately no {@code @ApplicationModule} on this package</h2>
 *
 * <p>A sub-package is not a module. {@code ArchitectureRules.moduleOf} takes the <strong>first</strong>
 * segment after {@code com.tcms}, so every type here belongs to {@code tracker} and inherits
 * {@code tracker}'s {@code allowedDependencies} — which is exactly what this code needs, because it
 * writes {@code issue}, {@code issue_type}, {@code issue_status}, {@code issue_comment} and
 * {@code issue_label_assignment} directly. Declaring a module here would make Spring Modulith treat
 * it as a sixth feature module and {@code ModuleStructureTest} would then demand a declaration for a
 * package that must not have one. {@code tracker/board/} and {@code tracker/release/} are the
 * precedent: neither has a {@code package-info} at all.
 *
 * <h2>{@code com.tcms.integration} is unreachable, and everything in it looks like what this needs</h2>
 *
 * <p>{@code OutboundUrlGuard}, {@code SecretCipher}, {@code TrackerClient}, {@code TrackerProvider}
 * and {@code TrackerUnavailableException} are each almost exactly the thing an importer wants, and
 * every one of them is a forbidden edge. It is not a compile error — it compiles fine and fails in
 * {@code ArchitectureRulesTest} at the end of a long suite. So this package carries its own
 * {@link com.tcms.tracker.imports.ImportUrlGuard} (pinned to {@code integration}'s as text, plan
 * 014 D-c) and its own {@link com.tcms.tracker.imports.ImportHttp}.
 *
 * <h2>Two passes, and neither is the other's shape (D-b)</h2>
 *
 * <ol>
 *   <li><strong>Read</strong> — credential-carrying, transaction-free, minutes long, bounded by
 *       {@code tcms.tracker.imports.max-issues}. Every timeout, 401, 429 and mid-flight
 *       disconnection happens here.</li>
 *   <li><strong>Write</strong> — one transaction, all or nothing. Either the whole project arrived
 *       or none of it did, which is what makes the preview's "no data has been written" promise
 *       worth having on the other side of Confirm too.</li>
 * </ol>
 *
 * <p><strong>No credential is ever persisted</strong> (D-a, closing review R14). {@code import_run}
 * has no secret column and the migration's header says so, because a column absent by decision is
 * indistinguishable from one nobody thought of.
 */
package com.tcms.tracker.imports;
