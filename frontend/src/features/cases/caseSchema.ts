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
  action: z.string().min(1, 'Describe the action.'),
  testData: z.string().nullable().optional(),
  expectedResult: z.string().nullable().optional(),
  sharedStepId: z.string().nullable().optional(),
});

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
    changeComment: z.string().nullable(),
  })
  // Cross-field rules the server also enforces. Both live in the schema rather than in the
  // submit handler, so the message lands on the field that caused it.
  .refine((data) => data.stepFormat !== 'GHERKIN' || !!data.gherkinSource?.trim(), {
    path: ['gherkinSource'],
    message: 'Write the scenario, or switch to structured steps.',
  })
  .refine((data) => data.stepFormat !== 'STRUCTURED' || data.steps.length > 0, {
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
  });

export type CaseInput = z.infer<typeof CaseSchema>;
