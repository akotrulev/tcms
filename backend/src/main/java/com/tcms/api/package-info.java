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
 * extraction is the follow-up"</em>. {@code work.UserRef} went further and kept a
 * <strong>running tally</strong> — {@code ApiProblem} ×2, {@code UserRef} ×3 — with the rule
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
 * <p>It has already refused things, which is the evidence that it is a charter and not a label.
 * {@code reporting.AggregationPolicy} is a contract enum declared twice and stays declared twice —
 * the two modules do not <em>serve</em> one shape, they keep two vocabularies that a test pins
 * equal, and merging them would make the cross-check compare a thing to itself.
 * {@code documentation.DocPageAttachmentView} and {@code repository.CaseAttachmentView} agree field
 * for field and stay apart — the contract defines them as two schemas, so collapsing them here
 * would be a contract change wearing an extraction's clothes. Each carries the refusal in its own
 * header.
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
 *   <li>{@link com.tcms.api.UserRef} — {@code UserRef} in the contract, the person a list renders
 *       beside a row. <strong>Six copies</strong> until §147, in {@code identity}, {@code project},
 *       {@code repository}, {@code documentation}, {@code execution} and {@code work}, with
 *       {@code reporting} importing {@code project}'s: seven consumers, which is the strongest
 *       reading of "more than one module serves it" this codebase has. Its arrival deleted two
 *       loops that copied a record onto itself field by field
 *       ({@code repository.CaseOwners}, {@code execution.ExecutionPeople}).</li>
 * </ul>
 *
 * <h2>The count is computed now, and that is the part that was actually broken</h2>
 *
 * <p>Three of the six {@code UserRefView} headers asked the next reader to keep an accurate count so
 * that an extraction would have evidence rather than a hunch, and one of them kept it: <em>"{@code
 * ApiProblem} ×2, {@code UserRefView} ×3"</em>. It was six. A hand-maintained count of copies drifts
 * exactly the way the copies do — §136's {@code MAX_IDEMPOTENCY_KEY} finding, one module over — so
 * {@code ArchitectureRulesTest.noRecordOutsideApiRepeatsAnApiShape} compares every production
 * record's components against this module's and fails the build on a repeat, <strong>matched on
 * shape rather than on name</strong>, because the next copy will be called something else.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {})
package com.tcms.api;
