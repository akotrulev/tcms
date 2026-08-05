/**
 * The plan detail form's one Save — **two endpoints in a fixed order, reporting per step**.
 *
 * ## The ordering, and why it is not the other way round
 *
 * 1. **Create every filled-in case row** (`POST /cases` ×N).
 * 2. **`PATCH` the plan** — fields, criteria, both texts, and the sectioned scope items *including
 *    the ids just created*.
 *
 * A case is the durable half. A case that exists without being in scope is findable, re-addable and
 * costs the author nothing but a click; a scope entry pointing at a case that was never created
 * cannot exist at all. Reversed, a failure between the two steps would leave the plan naming rows
 * the project does not have — and the server would refuse the whole `PATCH`, so the author would
 * lose both halves rather than one.
 *
 * ## Partial failure is named per step, and that is the requirement
 *
 * If step 2 fails after step 1 wrote three cases, those three are **loose in the project**. A
 * generic "could not save" leaves an author who presses Save again — the obvious response — with six
 * cases and no idea where the other three came from. So the outcome carries what was created, by
 * key, and the banner says it. `ui-rules` §5's per-item bulk reporting, arriving at a two-step save.
 *
 * ## Retrying is safe, and the row keys are what make it safe
 *
 * Each draft row carries its own `intentKey` (see {@link NewCaseRow}), so a second Save re-sends the
 * same `Idempotency-Key` per row: the server recognises the retry rather than writing a second case.
 * That is also why the created ids are *not* folded back into the draft here — the caller reseeds
 * from the server's response on success, and on failure the draft is left exactly as the author
 * typed it, keys and all.
 */
import { useState } from 'react';
import { useCreatePlanCase, useUpdatePlan } from '../../api/queries';
import type { TestPlan } from '../../api/types';
import { liveCaseRows, planPayload, type CreatedCase, type PlanDraft } from './planDraft';

/**
 * What one press of Save did.
 *
 * Three outcomes and not two: the failure arms differ in whether anything was written, which is the
 * only thing the author needs to know before pressing it again.
 */
export type SaveOutcome =
  /** Both steps landed. `created` is what step 1 wrote, for the confirmation. */
  | { kind: 'saved'; plan: TestPlan; created: CreatedCase[] }
  /** Step 1 stopped partway. `created` is what got through before it did. */
  | { kind: 'casesFailed'; created: CreatedCase[]; failedTitle: string; error: unknown }
  /** Step 1 landed in full and step 2 did not. The cases are loose in the project. */
  | { kind: 'planFailed'; created: CreatedCase[]; error: unknown };

export function usePlanSave(projectId: string, planId: string) {
  const createCase = useCreatePlanCase(projectId);
  const updatePlan = useUpdatePlan(projectId, planId);
  const [saving, setSaving] = useState(false);

  /**
   * Runs the pair. Never throws — every failure is an outcome, because the caller has to render two
   * different sentences for the two failure arms and a `catch` cannot tell them apart.
   */
  const save = async (draft: PlanDraft, plan: TestPlan): Promise<SaveOutcome> => {
    setSaving(true);
    const created: CreatedCase[] = [];
    try {
      // Trimmed and blank-dropped here, once, for the same reason the dirty check uses it: a
      // permanently-present trailing blank row would otherwise POST a case with an empty title on
      // every save.
      for (const row of liveCaseRows(draft.newCases)) {
        try {
          const testCase = await createCase.mutateAsync({
            intentKey: row.intentKey,
            title: row.title,
            // No suite (D2). A case authored inside a plan is not filed anywhere until somebody
            // files it, which §96 made expressible — before it, this field was required and a plan
            // could not author a case without first choosing a folder for it.
            suiteId: null,
          });
          created.push({ rowId: row.rowId, id: testCase.id, key: testCase.key });
        } catch (error) {
          return { kind: 'casesFailed', created, failedTitle: row.title, error };
        }
      }

      try {
        const saved = await updatePlan.mutateAsync({
          lockVersion: plan.lockVersion,
          ...planPayload(draft, plan, created),
        });
        return { kind: 'saved', plan: saved, created };
      } catch (error) {
        return { kind: 'planFailed', created, error };
      }
    } finally {
      setSaving(false);
    }
  };

  return { save, saving };
}
