/**
 * Read-modify-write, for the three `PATCH`es on this surface that are not partial.
 *
 * ## The rule, and why it is not the one `core/mutation.ts` states
 *
 * `core/mutation.ts` says a *flag* edit "states its whole intent in its arguments — `PATCH` reads
 * an absent key as leave it alone". That premise is true of HTTP and **false of this API**.
 * `CaseUpdateRequest`'s header records why: Jackson gives absent and null the same Java value, so
 * the server cannot tell "leave `objective` alone" from "clear it", and resolves both as *clear
 * it*. The same holds for a plan's description and milestone, and a suite's description. Three
 * shapes additionally declare a **required** field — `TestCaseInput` four of them, `SuiteInput`
 * and `TestPlanUpdateInput` a name each — so a delta is not merely lossy there, it is a `400`
 * about fields the caller never mentioned.
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
 * **`DocPageInput`** — `blocks` absent means *leave alone* server-side (`DocPageWriteService`
 * checks `blocks() != null`), and `title` is the only other editable field, which a title-only
 * edit supplies. It is already correct and a round-trip would gain nothing.
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
 * **`ownerId` and `automationTags` are dropped, not echoed.** Both are on `TestCaseInput`, both
 * are served by the `GET`, and both are **refused with a 400 when present** on the write —
 * `CaseUpdateRequest`'s "the two fields this endpoint refuses". Echoing what was read would turn
 * every round-trip into a rejection, which is the one way a read-modify-write can be worse than
 * the delta it replaces.
 *
 * `preconditions` and `postconditions` are dropped for the opposite reason: absent leaves them
 * alone (they are lists), and the read serves `CaseConditionRef`, which is not the shape the
 * write takes. Sending nothing is both correct and the only thing that types.
 *
 * `steps` is projected rather than dropped, because it is **not** left alone: a STRUCTURED case
 * that omits it is a `400` telling the author to add a step they already have.
 */
export function caseInputFrom(current: Schemas['TestCase']): Schemas['TestCaseInput'] {
  return {
    title: current.title,
    suiteId: current.suiteId,
    description: current.description,
    objective: current.objective,
    expectedOutcome: current.expectedOutcome,
    stepFormat: current.stepFormat,
    gherkinSource: current.gherkinSource,
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
    sharedStepId: step.sharedStepId ?? null,
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
 * edit either way. What a CLI cannot yet do is *change* them; that wants a flag, not a projection.
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
