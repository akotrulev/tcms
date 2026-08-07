import { z } from 'zod';

/**
 * What a test case *is* on the client, as one schema.
 *
 * It is deliberately narrower than the read model: it contains only fields the update path
 * actually persists. That is field gating by construction — TypeScript won't let the form bind
 * something the API ignores, so an edit can never show a success toast and quietly discard the
 * value, which is the highest-severity form defect there is.
 *
 * Notably absent: `lifecycleStatus`. Status moves through explicit review and approval actions
 * with their own side effects, so it is not an editable dropdown in the editor — a `<select>`
 * would bypass the transition guards entirely.
 *
 * ## Why this is its own module
 *
 * It lived inside `CaseEditorPage.tsx` and was module-private, which is why its four cross-field
 * refinements went untested through Phase 4 (§36) and stayed named as an open follow-up in §37.
 * Exporting it from the page file works and lints as a warning — a module that exports both a
 * component and a constant defeats Fast Refresh — so the schema moved instead. One consumer today
 * (the editor) and one test file; a second form binding this would be a second case editor, which
 * is not a thing that should exist.
 */

export const StepSchema = z.object({
  id: z.string().nullable().optional(),
  keyword: z.string().nullable().optional(),
  /**
   * Deliberately NOT `.min(1)` — the emptiness rules moved to the two checks below (§94).
   *
   * The editor keeps a blank row permanently at the end of the list so that typing appends the
   * next one, which means "every row has an action" stopped being true of a correct form: the
   * trailing row is an invitation, not a step, and a field-level `min(1)` on it would refuse
   * every save with an error on a row the author never touched.
   *
   * What replaces it is narrower and lands in the same place: a row that carries *anything* must
   * carry an action (the `superRefine` below, path `steps.N.action`), and a form must contain at
   * least one row that carries something.
   */
  action: z.string(),
  testData: z.string().nullable().optional(),
  expectedResult: z.string().nullable().optional(),
  sharedStepId: z.string().nullable().optional(),
});

/** The three authored fields — the shape both emptiness helpers below read. */
type StepLike = {
  action?: string | null;
  testData?: string | null;
  expectedResult?: string | null;
};

const isBlank = (value: string | null | undefined) => (value ?? '').trim().length === 0;

/**
 * A row nobody has written anything into — including one holding only spaces.
 *
 * Whitespace counts as blank in **both** directions on purpose: it is what makes a row typed into
 * and cleared again disappear at submit rather than reaching the API as a step whose action is
 * `"  "`, and it is what stops such a row satisfying "at least one step".
 */
export const isBlankStep = (step: StepLike): boolean =>
  isBlank(step.action) && isBlank(step.testData) && isBlank(step.expectedResult);

/**
 * The rows that are actually steps.
 *
 * **One definition, two consumers** — the schema's "at least one step" refine and the editor's
 * submit — because they must agree exactly. A validator that counts rows the submit then drops
 * saves an empty case; a submit that keeps rows the validator ignored posts a whitespace step. The
 * two halves of that disagreement are individually invisible.
 */
export const liveSteps = <T extends StepLike>(steps: readonly T[]): T[] =>
  steps.filter((step) => !isBlankStep(step));

export const CaseSchema = z
  .object({
    title: z.string().min(1, 'Enter a title.').max(300, 'Keep the title under 300 characters.'),
    /**
     * The folder the case is **filed** in, or `''` for an unfiled case (§96).
     *
     * No `.min(1)` any more, and the empty string is the unfiled value rather than a missing one:
     * a suite is a resolvable collection — filed ∪ added ∪ tagged — so filing is one way of
     * belonging rather than a precondition for existing, and a case may be written before anybody
     * has decided where it goes. The submit maps `''` to `null`, which is what the contract calls
     * unfiled; sending `''` would be a 400 on a uuid field.
     *
     * `''` rather than `null` in the form because MUI's `Select` treats `null` as "uncontrolled"
     * and logs a warning on every render of an unfiled case.
     */
    suiteId: z.string(),
    objective: z.string().nullable(),
    description: z.string().nullable(),
    expectedOutcome: z.string().nullable(),
    priority: z.enum(['CRITICAL', 'HIGH', 'MEDIUM', 'LOW']),
    automationStatus: z.enum(['MANUAL', 'TO_BE_AUTOMATED', 'AUTOMATED', 'NOT_AUTOMATABLE']),
    automationKey: z.string().nullable(),
    notAutomatableReason: z.string().nullable(),
    // Held as the string the number input actually produces and converted at submit, rather
    // than coerced in the schema: a coercion makes the schema's input and output types
    // diverge, which then fights the form's own typing for no benefit here.
    estimatedDurationSec: z
      .string()
      .nullable()
      .refine((value) => !value || /^\d+$/.test(value), 'Enter a whole number of seconds.'),
    stepFormat: z.enum(['STRUCTURED', 'GHERKIN']),
    gherkinSource: z.string().nullable(),
    steps: z.array(StepSchema),
    // Every classification value the case should carry, across all four dimensions — Test Type,
    // Test Level, Execution Phase and the project's Feature tree — as one flat array, because that
    // is the shape the contract takes and splitting it per dimension here would mean reassembling
    // it at submit and disagreeing with the server about what "the classification" is.
    //
    // Deliberately NOT validated for cardinality. The SINGLE-dimension rule needs the project's
    // vocabulary to know which dimensions are SINGLE, and a Zod schema that took a runtime argument
    // would stop being a static schema. The picker makes a second value on a SINGLE axis
    // unselectable instead — a radio group, not a checkbox list — so the rule is enforced by the
    // control's shape rather than by refusing a submission the user could not have made. The server
    // and V2's trigger hold the line for anything that is not the SPA.
    classificationValueIds: z.array(z.string()),
    /**
     * The case's OWN attached conditions, by library id, in the author's order — one array per
     * kind (§95).
     *
     * **Ids, not objects.** The contract takes `[{conditionId}]` and the submit maps to it; the
     * form binds the ids because that is what the two controls produce and what reordering moves.
     *
     * **Own, never inherited.** `TestCaseDetail.preconditions` carries the folder's conditions too,
     * marked `inherited` — and those must never be seeded into this array. Submitting one would
     * attach the folder's condition *directly* to the case, permanently and invisibly, since an
     * inherited row and an own row render almost identically. The defaults filter on `inherited`
     * for exactly that.
     *
     * **Deliberately not validated here.** There is no rule a static schema could hold: whether an
     * id is one this project offers needs the library, which is a runtime value. The picker only
     * offers live entries, and the server refuses anything else with a field error.
     *
     * Order is the content — these are arrays and are never sorted.
     */
    preconditionIds: z.array(z.string()),
    postconditionIds: z.array(z.string()),
    /**
     * The case's organisational tags (§96) — free text, a set, and the thing a suite's tag rules
     * resolve against.
     *
     * **Not `automationTags`**, which are CI selection tags on a different table (§6c) and are
     * still refused by the update endpoint. The editor cannot author those.
     *
     * Trimmed and de-duplicated by the control rather than by a refinement: a duplicate is not a
     * mistake to report, it is the same request — `case_tag`'s key says a tag list is a set — and
     * an error message about a chip the author cannot see twice is unactionable.
     *
     * Length is validated because the server caps it, and a silently truncated tag stops matching
     * the suite rule it was written for, which removes the case from a collection with no symptom.
     */
    tags: z.array(z.string().max(60, 'Keep each tag under 60 characters.')),
    changeComment: z.string().nullable(),
  })
  // Cross-field rules the server also enforces. Both live in the schema rather than in the
  // submit handler, so the message lands on the field that caused it.
  .refine((data) => data.stepFormat !== 'GHERKIN' || !!data.gherkinSource?.trim(), {
    path: ['gherkinSource'],
    message: 'Write the scenario, or switch to structured steps.',
  })
  /*
    NON-EMPTY steps, not `steps.length` — the defect §94's auto-append introduces.

    The editor now keeps a blank row at the end of the list at all times, so `steps.length` is
    never 0 on a structured case and this rule would pass on a form nobody has written a step
    into: an empty case would save clean, with a success toast and a version bump. Counting the
    rows that survive `liveSteps` is the same question asked of the rows that will actually be
    sent.
  */
  .refine((data) => data.stepFormat !== 'STRUCTURED' || liveSteps(data.steps).length > 0, {
    path: ['steps'],
    message: 'Add at least one step, or switch to Gherkin.',
  })
  .refine((data) => data.automationStatus !== 'NOT_AUTOMATABLE' || !!data.notAutomatableReason?.trim(), {
    path: ['notAutomatableReason'],
    message: 'Say why this case cannot be automated.',
  })
  .refine((data) => data.automationStatus !== 'AUTOMATED' || !!data.automationKey?.trim(), {
    path: ['automationKey'],
    message: 'Give the automated test that covers this case, so results can be matched to it.',
  })
  /*
    "A step needs an action" — what `StepSchema.action.min(1)` used to say, restated as a rule
    about the row rather than about the field.

    It has to know about its neighbours to be right: the blank trailing row is exempt (it is not a
    step yet), and a row carrying test data or an expected result is NOT (an author who filled in
    what they expect and left the action blank has written half a step, and the server refuses it).
    That is a cross-field question, so it lives here with the other four rather than on the field.

    Structured only, matching the rule above: a Gherkin case sends no steps at all, and refusing a
    half-written row in a list that is not submitted would be an error with nothing on screen to
    fix — the array is not even rendered.
  */
  .superRefine((data, ctx) => {
    if (data.stepFormat !== 'STRUCTURED') return;
    data.steps.forEach((step, index) => {
      if (isBlankStep(step)) return;
      if ((step.action ?? '').trim().length === 0) {
        ctx.addIssue({
          code: 'custom',
          path: ['steps', index, 'action'],
          message: 'Describe the action.',
        });
      }
    });
  });

export type CaseInput = z.infer<typeof CaseSchema>;

/**
 * The form's values as the API's `TestCaseInput` — **the one mapping**, and it has two consumers
 * that must not disagree.
 *
 * The editor's Save built this inline for as long as Save was the only thing that sent it. §128's
 * autosave is the second, and a second hand-written copy is the defect that shape produces: a field
 * wired into the save and forgotten in the buffer restores as empty, so the author's recovered
 * draft is missing exactly the thing they were typing — with nothing failing at either end, because
 * both requests are individually valid. `documentation`'s `BlockContent.of` is the same rule one
 * module over, arrived at the same way.
 *
 * **It normalizes but does not validate**, which is what lets autosave use it: trimming and
 * dropping blank steps are shape decisions, while "a title is required" is `CaseSchema`'s and is
 * asked only on Save. A buffered draft is half-written by definition.
 *
 * Every collection is sent **including when empty**, because the server reads an absent field as
 * "leave it alone": omitting `tags` when the author has just removed the last one would silently do
 * nothing. That asymmetry is on the contract and is the reason these are not `|| undefined`.
 */
export function toCasePayload(data: CaseInput): Record<string, unknown> {
  /*
    Trim first, then drop the rows that are empty once trimmed — and the order is the point.
    Trimming alone sends a step whose action is `"  "` as a real step: the API accepts it, it
    renders as an empty numbered line for ever, and a run asks a tester to perform it. Dropping
    alone keeps the leading and trailing spaces of every pasted step, which is what makes two
    identical steps two entries in the suggestion corpus.
  */
  const steps = liveSteps(
    data.steps.map((step) => ({
      ...step,
      action: step.action.trim(),
      testData: (step.testData ?? '').trim(),
      expectedResult: (step.expectedResult ?? '').trim(),
    })),
  );

  return {
    title: data.title.trim(),
    // null, not '', for an unfiled case: the field is a uuid on the contract and '' is a 400.
    suiteId: data.suiteId || null,
    objective: data.objective || null,
    description: data.description || null,
    expectedOutcome: data.expectedOutcome || null,
    priority: data.priority,
    severity: data.priority,
    automationStatus: data.automationStatus,
    // Only the chosen branch's content is sent. Posting both would leave the other one stale in
    // the record, and the next reader cannot tell which is authoritative.
    automationKey: data.automationStatus === 'AUTOMATED' ? data.automationKey || null : null,
    notAutomatableReason:
      data.automationStatus === 'NOT_AUTOMATABLE' ? data.notAutomatableReason || null : null,
    estimatedDurationSec: data.estimatedDurationSec ? Number(data.estimatedDurationSec) : null,
    stepFormat: data.stepFormat,
    gherkinSource: data.stepFormat === 'GHERKIN' ? data.gherkinSource : null,
    steps:
      data.stepFormat === 'STRUCTURED'
        ? steps.map((step) => ({
            id: step.id ?? null,
            keyword: null,
            action: step.action,
            testData: step.testData || null,
            expectedResult: step.expectedResult || null,
            sharedStepId: step.sharedStepId ?? null,
          }))
        : [],
    classificationValueIds: data.classificationValueIds,
    // De-duplicated here as well as in the control, because the control is not the only way a value
    // reaches the form — a restored draft is now one of them.
    tags: [...new Set(data.tags.map((tag) => tag.trim()).filter((tag) => tag !== ''))],
    preconditions: data.preconditionIds.map((conditionId) => ({ conditionId })),
    postconditions: data.postconditionIds.map((conditionId) => ({ conditionId })),
    changeComment: data.changeComment || null,
  };
}

/** The blank trailing step row the editor always keeps at the end of the list (§94). */
const BLANK_ROW = { id: null, keyword: null, action: '', testData: '', expectedResult: '', sharedStepId: null };

const str = (value: unknown): string => (typeof value === 'string' ? value : '');
const nullableStr = (value: unknown): string | null => (typeof value === 'string' ? value : null);
const strArray = (value: unknown): string[] =>
  Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : [];

/** `[{ conditionId }]` on the wire back to the ids the form binds — see `preconditionIds`. */
const conditionIds = (value: unknown): string[] =>
  Array.isArray(value)
    ? value
        .map((ref) =>
          ref && typeof ref === 'object' ? (ref as { conditionId?: unknown }).conditionId : null,
        )
        .filter((id): id is string => typeof id === 'string')
    : [];

/**
 * A buffered draft's `content` back into the form's own shape — **the inverse of
 * {@link toCasePayload}**, and the thing a restored draft goes through.
 *
 * ## Why an inverse exists at all
 *
 * The form's shape and the API's are genuinely different, and each difference has a reason
 * recorded on `CaseSchema`: an unfiled case is `''` in the form and `null` on the wire (MUI's
 * `Select` warns on `null`), a duration is the string the number input produces, and conditions are
 * ids in the form against `[{conditionId}]` on the contract. The buffer stores the **API** shape —
 * which is what makes it exactly what `PATCH` takes, and therefore what makes "restore" mean "put
 * this back in the form and press Save".
 *
 * ## It decodes DEFENSIVELY, and that is the whole point
 *
 * The buffer is unvalidated by design, and it may also have been written by an older build of this
 * app. So every field is read with a fallback and nothing here can throw: a draft that has lost a
 * field restores without it, rather than taking the editor down with a `TypeError` on the one
 * screen whose job is not losing the user's work. **A wrong-typed field reads as absent** — the
 * honest outcome, since the alternative is putting `[object Object]` in a title.
 *
 * ## The round trip is lossy in exactly one direction, deliberately
 *
 * `toCasePayload` drops blank step rows and this re-adds one, because the editor's list always ends
 * in a blank row — that is how a step is added since §94. So `from(to(x))` is `x` with its trailing
 * blank restored, which is the same normalization opening an existing case performs.
 */
export function fromCasePayload(content: unknown): CaseInput {
  const raw = (content && typeof content === 'object' ? content : {}) as Record<string, unknown>;

  const priority = str(raw.priority);
  const automationStatus = str(raw.automationStatus);
  const stepFormat = str(raw.stepFormat);
  const duration = raw.estimatedDurationSec;

  const steps = (Array.isArray(raw.steps) ? raw.steps : [])
    .filter((step): step is Record<string, unknown> => !!step && typeof step === 'object')
    .map((step) => ({
      id: typeof step.id === 'string' ? step.id : null,
      keyword: nullableStr(step.keyword),
      action: str(step.action),
      testData: str(step.testData),
      expectedResult: str(step.expectedResult),
      sharedStepId: typeof step.sharedStepId === 'string' ? step.sharedStepId : null,
    }));

  return {
    title: str(raw.title),
    // '' is UNFILED, not missing — the inverse of the submit's `data.suiteId || null`.
    suiteId: typeof raw.suiteId === 'string' ? raw.suiteId : '',
    objective: nullableStr(raw.objective),
    description: nullableStr(raw.description),
    expectedOutcome: nullableStr(raw.expectedOutcome),
    // An unrecognised enum value falls back to the form's own default rather than being bound and
    // rendering a `Select` with no matching option — which is a control that looks empty and
    // silently submits whatever was there.
    priority: (['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'] as const).includes(
      priority as 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW',
    )
      ? (priority as CaseInput['priority'])
      : 'MEDIUM',
    automationStatus: (['MANUAL', 'TO_BE_AUTOMATED', 'AUTOMATED', 'NOT_AUTOMATABLE'] as const).includes(
      automationStatus as CaseInput['automationStatus'],
    )
      ? (automationStatus as CaseInput['automationStatus'])
      : 'MANUAL',
    automationKey: nullableStr(raw.automationKey),
    notAutomatableReason: nullableStr(raw.notAutomatableReason),
    // A number on the wire, the string the input produces in the form.
    estimatedDurationSec:
      typeof duration === 'number' && Number.isFinite(duration) ? String(duration) : null,
    stepFormat: stepFormat === 'GHERKIN' ? 'GHERKIN' : 'STRUCTURED',
    gherkinSource: nullableStr(raw.gherkinSource),
    // The trailing blank row — see the header. Without it a restored draft has no way to add a
    // step, because the blank last row IS the add control.
    steps: [...steps, { ...BLANK_ROW }],
    classificationValueIds: strArray(raw.classificationValueIds),
    preconditionIds: conditionIds(raw.preconditions),
    postconditionIds: conditionIds(raw.postconditions),
    tags: strArray(raw.tags),
    changeComment: nullableStr(raw.changeComment),
  };
}
