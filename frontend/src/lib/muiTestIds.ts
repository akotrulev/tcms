/**
 * Test ids for the two MUI slots whose prop types are too narrow to accept `data-*`.
 *
 * MUI types a slot's props as that element's known DOM props, which excludes custom attributes.
 * The attribute reaches the DOM perfectly well; only the type refuses it. So the assertion lives
 * here, once, instead of at every call site.
 *
 * **This file exists because there were already two copies of it** — `ClassificationPicker`'s
 * `inputTestId` and `CaseEditorPage`'s `selectTestId` — and §71 was about to write a third. That is
 * the rule `CLAUDE.md` states for `lib/labels.ts` and `lib/format.ts`, applied to a helper rather
 * than to display logic: a second implementation is how a hundred consistent pages become a hundred
 * slightly different ones. The copies each carried the *reason* in a comment, which is exactly the
 * shape §58's E1 and §70 both punished — a rule maintained by a comment saying it must be
 * maintained.
 */

/**
 * A test id on a MUI `Checkbox`/`Radio`'s **input** slot.
 *
 * Targeting the input rather than the root is the whole point. A bare `data-testid` on a MUI
 * `Checkbox` lands on the **wrapper span**, where the checked state is not readable — so a test can
 * find the element, click it successfully, and assert nothing at all about selection. That is
 * precisely how §62's option test passed while measuring the wrong thing, and §68 hit the same
 * lesson in its own test file.
 */
export const inputTestId = (id: string, ariaLabel?: string) =>
  ({
    input: { 'data-testid': id, ...(ariaLabel ? { 'aria-label': ariaLabel } : {}) },
  }) as never;

/** A test id on a MUI `Select`'s display element, via `SelectDisplayProps`. */
export const selectTestId = (id: string) =>
  ({ 'data-testid': id }) as React.HTMLAttributes<HTMLDivElement>;
