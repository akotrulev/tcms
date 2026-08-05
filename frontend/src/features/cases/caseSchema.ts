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
    suiteId: z.string().min(1, 'Choose a suite.'),
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
