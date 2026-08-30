import { cn } from '../../lib/cn';
import { useWatch, type Control, type UseFormGetValues } from 'react-hook-form';
import { formatTimeOfDay } from '../../lib/format';
import { toCasePayload, type CaseInput } from './caseSchema';
import { useCaseAutosave } from './useCaseAutosave';

/**
 * The autosave buffer's subscription and its status line, as their own component — **and the
 * separation is a performance boundary, not tidiness.**
 *
 * `useCaseAutosave` needs the whole form's values on every change, and the only way to get them is
 * a whole-form subscription. Held in `CaseEditorPage` (as `const values = watch()`, where §128 first
 * put it) that subscription re-renders **the entire 1,400-line editor on every keystroke** — every
 * `Controller`, every MUI field, the step list, the conditions panels. Measured on the case editor:
 * 21 renders for 20 typed characters, ~215 ms each. Moved here it is 2 renders of the whole editor
 * and 20 of this Box, and typing twenty characters into the title went from 4,574 ms to 480 ms.
 *
 * It surfaced as CI red rather than as a complaint about typing: `CaseEditorPage.test.tsx` crossed
 * vitest's 30 s per-test budget on the Linux runner, on a test that types a sentence. The budget
 * had already been raised twice for this shape of cost (see `vitest.config.ts`), and this is the
 * cause it was being raised around.
 *
 * `useWatch` subscribes; `getValues` reads. Splitting them that way is what keeps the payload typed
 * as `CaseInput` — `useWatch({ control })` returns a deep-partial of the form, so using its return
 * value directly would need a cast, and a cast here would hide exactly the kind of field-shape
 * drift that `toCasePayload` exists to make impossible. RHF has already written the change into its
 * own store by the time this re-renders, so `getValues()` is the same values, fully typed.
 *
 * This is also the ONLY place a failed autosave is reported. The mutation is `meta.handled` so
 * nothing else announces it: a toast fired by a timer, mid-sentence, once per interval, about
 * something the author did not ask for and cannot act on, is worse than the silence it replaces.
 * `role="status"` + `aria-live="polite"` so a screen reader is told when the work became safe
 * without being interrupted while typing.
 */
export function CaseDraftStatus({
  control,
  getValues,
  enabled,
  save,
  commitLabel,
  testId,
  inline,
}: {
  control: Control<CaseInput>;
  getValues: UseFormGetValues<CaseInput>;
  enabled: boolean;
  save: (content: Record<string, unknown>) => Promise<unknown>;
  /**
   * What the button that ends the buffer's job is called — `Save changes` or `Create case`. Named
   * rather than derived from a `mode` prop, because this component's whole contribution is one
   * sentence and the sentence has to point at a control the reader can see. D1 gave create mode a
   * buffer of its own (`useNewCaseDraft`), and telling somebody on the New Case form to press
   * "Save changes" would name a button that is not there.
   */
  commitLabel: string;
  testId: string;
  /**
   * Drop the bottom margin, for a caller that already spaces this line itself.
   *
   * The commit bar is the one such caller: the line sits beside the button it names rather than
   * above the form, so the gap that used to separate it from the first panel is now the bar's own
   * padding. `minHeight` stays either way — it is what stops the bar changing height as the
   * status cycles through saving → saved.
   */
  inline?: boolean;
}) {
  // Subscribe to every field. The return value is deliberately unused — see the header.
  useWatch({ control });

  const autosave = useCaseAutosave({
    enabled,
    // The SAME mapping the submit uses — see `toCasePayload`. A second copy here is how a field
    // wired into Save and forgotten in the buffer comes back empty from a restore.
    payload: toCasePayload(getValues()),
    save,
  });

  return (
    <div
      role="status"
      aria-live="polite"
      className={cn('min-h-5', inline ? 'mb-0' : 'mb-4')}
      data-testid={testId}
    >
      {autosave.status === 'failed' ? (
        <span className="text-[12px] text-[color:var(--color-failed)]">
          Could not save a backup of your changes. Your work is still here — use {commitLabel} to
          keep it.
        </span>
      ) : autosave.status === 'saving' ? (
        <span className="text-[12px] text-ink-2">Saving a backup…</span>
      ) : autosave.status === 'saved' ? (
        <span className="text-[12px] text-ink-2">
          Backup saved {formatTimeOfDay(autosave.savedAt)}. This is not the case itself — use{' '}
          {commitLabel} to publish your work.
        </span>
      ) : null}
    </div>
  );
}
