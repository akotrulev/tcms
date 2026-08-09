/**
 * API — the wire shapes {@code docs/specs/openapi.yaml} owns, and the error rendering that has no
 * controller to live in.
 *
 * <p>Everything else in this application is a module because it owns a <em>question</em>: what a
 * case is, who may see it, where the bytes are. This one owns none. It holds the handful of types
 * that belong to the <strong>contract</strong> rather than to any module, and it exists because the
 * alternative was observed for eleven slices: the same record, written again, in every module that
 * could not import the last copy.
 *
 * <h2>Why now, and not any of the times it was proposed</h2>
 *
 * <p>{@code repository.ApiProblem}'s header called its duplicate <em>"deliberate, visible, and the
 * extraction is the follow-up"</em>. {@code work.UserRefView} went further and kept a
 * <strong>running tally</strong> — {@code ApiProblem} ×2, {@code UserRefView} ×3 — with the rule
 * attached: <em>"the next one should probably come with the extraction rather than with another
 * comment like this."</em> §146's global error handler is that next one. It is a third caller for
 * the error envelope, in no module at all, so there was no package left to copy it into.
 *
 * <p>Every one of those comments was correct, including about the cost. What none of them could do
 * was decide, because the cost is a <em>foundation</em> entry and §76's rule stands: <strong>a new
 * foundation module is a decision, not an import.</strong> This is the decision.
 *
 * <h2>THIS IS A FIFTH FOUNDATION MODULE</h2>
 *
 * <p>{@code ArchitectureRules.FOUNDATION_MODULES} states the three properties that made
 * {@code storage} a defensible fourth, and says a proposed fifth should be held to them. This one
 * clears each in the strongest available form:
 *
 * <ul>
 *   <li><strong>It declares nothing at all</strong> — {@code allowedDependencies = {}}, the same
 *       spelling {@code tenancy} uses. {@code storage} had to name {@code tenancy} because its table
 *       is company-owned; there is no table here, so this module cannot become a second place
 *       anything is decided. It cannot even reach the isolation boundary.</li>
 *   <li><strong>It holds no domain concept</strong>, and holds no <em>behaviour</em> either beyond
 *       rendering: a {@link com.tcms.api.Problem} is four fields and a map, and the error handler
 *       beside it turns a status into one. Nothing here knows what a run, a case or a person is.</li>
 *   <li><strong>It answers a question no module can</strong> — not "a question the database cannot",
 *       which is {@code storage}'s test, but the one behind it: <em>whose is a shape that belongs to
 *       the contract?</em> The answer for eleven slices was "whichever module noticed first, plus a
 *       comment", and that produced five copies of two records across five modules, each free to
 *       drift from the contract independently.</li>
 * </ul>
 *
 * <h2>Why it is not called {@code shared}</h2>
 *
 * <p>Plan §3 lists a {@code shared} module — <em>"storage, audit, attachments, search"</em> — and
 * §125 <strong>refused</strong> it as the home for the blob ports on the grounds that a module every
 * other module may depend on must not be a grab bag. That argument did not expire; it applies here
 * with the same force, and a package named for its <em>accessibility</em> rather than its contents
 * is an invitation to put the next homeless class in it. The name states the charter instead, and
 * the charter is narrow enough to refuse things:
 *
 * <p><strong>A type belongs here only if the contract defines it and more than one module serves
 * it.</strong> A helper two modules happen to share does not qualify — that is a copy, or an event.
 * A service does not qualify, because a service has a question and this module has none. If
 * something arrives here that fails that sentence, the sentence was right and the arrival is the
 * bug.
 *
 * <h2>What it holds</h2>
 *
 * <ul>
 *   <li>{@link com.tcms.api.Problem} — the RFC 7807 envelope every 4xx and 5xx in this API answers
 *       with, {@code Problem} in the contract. Previously {@code repository.ApiProblem}
 *       (package-private, so six modules referred to it in <em>prose</em> and none could call it),
 *       {@code auth.InvitationController.ProblemBody} (the same record without {@code errors}) and
 *       a hand-written JSON string literal in {@code auth.RateLimitFilter}.</li>
 *   <li>The container-error rendering (§146) — the one place a failure Spring resolves <em>before or
 *       instead of</em> a controller becomes the same envelope a controller would have produced.</li>
 * </ul>
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {})
package com.tcms.api;
