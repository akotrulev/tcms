/**
 * Read-modify-write, for the three `PATCH`es on this surface that are not partial.
 *
 * ## The rule, and why it is not the one `core/mutation.ts` states
 *
 * `core/mutation.ts` says a *flag* edit "states its whole intent in its arguments — `PATCH` reads
 * an absent key as leave it alone". That premise is true of HTTP and **was false of this API**:
 * Jackson gives absent and null the same Java value, so the server could not tell "leave
 * `objective` alone" from "clear it" and resolved both as *clear it*.
 *
 * **B1 fixed that on the case endpoint** — it keeps the body beside the binding and lays what was
 * sent over the stored case, so an absent field is now left alone there. The plan's and the
 * suite's `PATCH`es are **unchanged** and still clear what they do not carry, which is why this
 * module still exists and why its case arm has not been deleted with the defect that prompted it.
 *
 * Three shapes additionally declare a **required** field — `TestCaseInput` four of them,
 * `SuiteInput` and `TestPlanUpdateInput` a name each — and that has not changed either: the
 * generated types demand them, so composing the whole set is what types, whatever the server would
 * now tolerate.
 *
 * §143 drove it: `tcms case edit TC-1 --title x` answered three field errors and **had never
 * worked once**. The stubs passed because a stub answers 200 to whatever arrives.
 *
 * So a delta edit against these three resources is composed the only way it can be: read the
 * resource, project it back into its own input shape, and lay the caller's change on top.
 *
 * ```ts
 * const current = await client.GET(…);
 * const body = { ...caseInputFrom(current), ...delta };
 * ```
 *
 * ## Why this does not reopen the `If-Match` hole `core/mutation.ts` closed
 *
 * That hole is a body composed from an **earlier** read carrying a **fresh** version: the
 * precondition asserts a state the body was never based on, so the server accepts it and whoever
 * saved in between is gone. Here the base and the version come from the *same* read, one
 * millisecond before the write, so the pair is consistent and the `If-Match` is honest — the
 * argument the plan arm already made for threading `name` through, applied to every field that
 * needs it. **The version must come from this read**, not from a second one; the call sites pass
 * `() => current` for exactly that reason.
 *
 * What it does mean is that a concurrent change to a field the caller never named is now
 * overwritten by the value this read saw rather than left alone — but the alternative was
 * clearing it outright, and the `If-Match` still refuses the write if anything changed between
 * the read and the `PATCH`. The `--file` / `blocks` route keeps requiring an explicit
 * `lockVersion`; nothing here touches it.
 *
 * ## What is deliberately absent
 *
 * ~~**`DocPageInput`** — `blocks` absent means *leave alone* server-side (`DocPageWriteService`
 * checks `blocks() != null`), and `title` is the only other editable field, which a title-only
 * edit supplies. It is already correct and a round-trip would gain nothing.~~
 * → **half right, and the half it missed is the opposite edit.** `DocPageInput` declares
 * `required: [title]`, so a body edit that sends **`blocks` and no `title`** is a `400` on a field
 * the caller never meant to change — which both adapters have composed since the arm existed, and
 * which nothing caught because no test drove a whole-body page edit as far as the `PATCH`. 022's
 * markdown route added the first, and the contract-exchange check named it on the first run. See
 * {@link docPageInputFrom}.
 *
 * **`ExecutionResultInput`** — recording a result is a whole statement, not an edit to one: the
 * verdict and its note are submitted together, `statusId` is always sent, and amending a verdict
 * with no note is a legitimate "no note" rather than a field somebody forgot.
 */
import type { components } from '../schema.js';

type Schemas = components['schemas'];

/**
 * A case's whole editable set, as the server would re-store it unchanged.
 *
 * **`ownerId` and `automationTags` are dropped, not echoed.** Both are on `TestCaseInput` and both
 * are served by the `GET`. ~~Both are refused with a 400 when present.~~ → **B2: refused only when
 * they would CHANGE something**, so echoing them is no longer a rejection. They stay dropped
 * anyway, and the reason is now the one the lists below give rather than self-defence: absent is
 * "leave alone", the projection's job is to state an unchanged save, and the shortest true
 * statement of "do not touch the owner" is to say nothing about it.
 *
 * `preconditions` and `postconditions` are dropped for the opposite reason: absent leaves them
 * alone (they are lists), and the read serves `CaseConditionRef`, which is not the shape the
 * write takes. Sending nothing is both correct and the only thing that types.
 *
 * `declinedConditionIds` is dropped too (§187), and it is worth saying that the reason is only
 * *half* the one above. The shapes here do line up — `[...declinedPreconditions,
 * ...declinedPostconditions].map((ref) => ref.id)` is exactly what the write takes — so "it does
 * not type" is not available as an argument and the whole of it is that **absent leaves them
 * alone**. Echoing would also be correct and buys nothing; not echoing keeps this function's rule
 * one rule ("lists are left alone") instead of two.
 *
 * **§189 gave both callers a flag for it and the drop still stands**, which is the case worth
 * recording because a projection and a delta answer different questions. The projection asks
 * *what does an unchanged save send*, and for a list the answer is nothing. The delta asks *what
 * did the caller say*, and `--decline` / `declinedConditions` is now where that is said. The
 * projection could have carried it — the ids the read serves are exactly the declinable ones, and
 * `CaseConditions.reconcileOptouts` bounds its deletions by `declinable` so a decline the client
 * was never shown survives either way — but it would state as an assertion what the caller never
 * mentioned, and this function's rule stays "lists are left alone; a flag changes one".
 *
 * `steps` is projected rather than dropped, because it is **not** left alone: a case that omits it
 * is a `400` telling the author to add a step they already have. Since §255 that is true in BOTH
 * lenses — a GHERKIN case is step rows with keywords, so the round trip has one body to carry
 * rather than two to keep consistent.
 *
 * **The scenario blob is not echoed, because it is no longer on the input.** `TestCase` still
 * SERVES one, deprecated, for a case whose rows were never backfilled; sending it back would be an
 * undeclared property the API ignores and this package's contract check refuses. `002-` deletes
 * the column.
 */
export function caseInputFrom(current: Schemas['TestCase']): Schemas['TestCaseInput'] {
  return {
    title: current.title,
    suiteId: current.suiteId,
    description: current.description,
    objective: current.objective,
    expectedOutcome: current.expectedOutcome,
    stepFormat: current.stepFormat,
    priority: current.priority,
    // Conditional, and the contract is why: `TestCase.severity` is nullable and
    // `TestCaseInput.severity` is not, so an unset severity has **no spelling on the write** —
    // there is no `null` to send. Omitting it is the one thing that reproduces it, because this
    // endpoint reads an absent scalar as "clear it". The asymmetry is real (`test_case.severity`
    // has no default, deliberately) and it happens to round-trip; a `PATCH` that became genuinely
    // partial would break this line, which is the note to read then.
    ...(current.severity === null ? {} : { severity: current.severity }),
    automationStatus: current.automationStatus,
    automationKey: current.automationKey,
    notAutomatableReason: current.notAutomatableReason,
    estimatedDurationSec: current.estimatedDurationSec,
    classificationValueIds: current.classificationValueIds,
    tags: current.tags,
    steps: current.steps.map(stepInputFrom),
  };
}

/**
 * A stored step as the input that re-stores it.
 *
 * **The `id` is the whole point.** `PageContent`'s rule one module over holds here too: a step
 * sent with its id keeps that id, and a step sent without one is a *new* step. Dropping the ids
 * on a round-trip would delete every step and recreate it, which renumbers nothing visibly and
 * breaks every `stepResults` entry pinned to the old ones.
 */
function stepInputFrom(step: Schemas['TestStep']): Schemas['TestStepInput'] {
  // `?? null` rather than the value straight through: the read's optional-nullable fields and the
  // write's are the same JSON and different TypeScript, and under `exactOptionalPropertyTypes` an
  // explicit `undefined` is not a legal value for an optional key. Null is what the server does
  // with an omitted one anyway, so this changes nothing on the wire.
  return {
    id: step.id,
    keyword: step.keyword ?? null,
    action: step.action,
    testData: step.testData ?? null,
    expectedResult: step.expectedResult ?? null,
    docString: step.docString ?? null,
    dataTable: step.dataTable ?? null,
  };
}

/**
 * A suite's whole editable set.
 *
 * `tagRules` and `caseIds` are lists and would survive being omitted, but they are included
 * anyway: a base body that is the resource is one rule to read, and "these two fields alone are
 * safe to leave out" is the kind of exception that stops being true when a field is added.
 *
 * **`preconditions` and `postconditions` are the field that was added** (§186), and they are the
 * exception the paragraph above predicted — dropped, on `caseInputFrom`'s grounds: the read serves
 * `CaseConditionRef` and the write takes `CaseConditionInput`, so echoing the resource is not a
 * thing that types here. Absent leaves them alone, which is the correct behaviour for a `--name`
 * edit either way. ~~What a CLI cannot yet do is *change* them; that wants a flag, not a
 * projection.~~ → **§189 built the flag** (`--precondition` / `--postcondition`, and
 * `preconditions` / `postconditions` on the tool), and the sentence it replaces is the reason the
 * drop is still right: the change goes in the delta, where the caller's own ids are, rather than
 * through a projection that would have to invent a `conditionId` for an inline row that has none.
 */
export function suiteInputFrom(current: Schemas['Suite']): Schemas['SuiteInput'] {
  return {
    name: current.name,
    description: current.description ?? null,
    tagRules: current.tagRules,
    caseIds: current.caseIds,
  };
}

/**
 * A plan's whole editable set — every **scalar**, and none of the lists.
 *
 * The split is the server's, not a shortcut: `PlanEdit.validateUpdate` resolves an absent
 * `suiteIds`/`caseIds`/`tags`/`sections` against what is stored and an absent
 * `entryCriteria`/`exitCriteria` likewise (`TestPlan.applyEdit` guards both on `!= null`), while
 * every scalar it validates — description, milestone name, milestone date, and the two prose
 * fields — is written straight through. So the lists are genuinely partial here and the scalars
 * are genuinely not, and echoing the lists back would mean resolving `PlanSection` into
 * `PlanSectionInput` for no gain.
 *
 * `scopeText` and `outOfScopeText` are the two this catches that nobody would have thought of:
 * neither is reachable from any flag or tool argument, so before this projection **every** plan
 * edit silently emptied both.
 */
export function planInputFrom(current: Schemas['TestPlan']): Schemas['TestPlanUpdateInput'] {
  return {
    name: current.name,
    description: current.description,
    milestoneName: current.milestoneName,
    milestoneDate: current.milestoneDate,
    scopeText: current.scopeText,
    outOfScopeText: current.outOfScopeText,
  };
}

/**
 * The one field a whole-body page edit must carry beyond its blocks.
 *
 * Deliberately **not** the whole resource, unlike its three siblings above. `blocks` absent means
 * *leave alone* server-side, and `parentId` on a `PATCH` must equal the page's current parent or
 * it is a `400` — so echoing either buys nothing and the second buys a way to fail. `title` is
 * the whole of it, and it is here rather than in each adapter because the two composing that body
 * differently is exactly what this module exists to prevent.
 */
export function docPageInputFrom(current: Schemas['DocPage']): { title: string } {
  return { title: current.title };
}
