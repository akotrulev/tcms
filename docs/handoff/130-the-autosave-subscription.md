# §130 — The autosave subscription that re-rendered the whole case editor

**Date:** 2026-08-08 · **Commit:** `24d6096` · **Follows:** [§128](128-the-draft-buffer.md) (which
introduced the defect) and [§129 phase 3](129-clone-case-plan-run.md) (which measured the cliff and
did not find its cause)

**It arrived as CI red, not as a complaint about typing.** `CaseEditorPage.test.tsx:252` timed out at
30 s on the runner — not an assertion, a budget — with its neighbours in the same file sitting at
22–25 s. A file on the ceiling is the shape §129 phase 3 had just described as "a load-dependent
timeout cliff in a known set of tests". This is the cause under one of those five files.

## The cause

§128 phase 2 put the autosave's whole-form subscription in the page:

```ts
const values = watch();          // in CaseEditorPage
```

`useCaseAutosave` genuinely needs every field on every change — that is what a buffer is. But a
whole-form subscription **held in the page** re-renders the entire 1,400-line editor on every
keystroke: every `Controller`, every MUI field, the step list, both conditions panels.

Measured on the component, not inferred:

| | before | after |
|---|---:|---:|
| page renders per 20 typed characters | 21 | 0 |
| type 20 chars into Title | 4,574 ms | 499 ms |
| type 20 chars into a step | 6,007 ms | 3,179 ms |
| the test file, run alone | 175 s | 83 s |

The residual step-field cost is `StepActionField`'s own `Autocomplete`, one per row, which is the
work that component exists to do.

## The fix, and the one line that is load-bearing

[`CaseDraftStatus`](../../frontend/src/features/cases/CaseDraftStatus.tsx) owns the subscription
**and** the status line, so the per-keystroke render is a `Box` instead of the editor. The
separation is a performance boundary, and the file's header says so, because it looks like tidiness
and would be deleted as such.

**`useWatch` subscribes and `getValues` reads, split on purpose.** `useWatch({ control })` returns a
deep-partial of the form, so using its return value as the payload needs a cast — and a cast there
hides exactly the field-shape drift `toCasePayload` was extracted (§128) to make impossible. RHF has
already written the keystroke into its store by the time this re-renders, so `getValues()` is the
same values, fully typed.

## Two dead ends, recorded so nobody re-walks them

- **`userEvent.setup({ delay: null })` changed nothing.** The cost is render work, not the
  inter-keystroke wait.
- **Swapping `watch()` for `getValues()` showed no whole-file gain**, because it broke three
  autosave tests whose own timeouts swamped the measurement. A render counter is what settled it;
  wall-clock on a suite that is already near a cliff measures the cliff.

## What this does NOT close

`vitest.config.ts` records `testTimeout` being raised **5 s → 20 s → 30 s** for "tests that type
sentences", and says in as many words that each raise treats the symptom. This is the cause those
raises were circling — *for one file*. **The cliff itself is not gone**: under a full parallel run
`DocPageEditorPage`, `PlanCases`, `RunCreateDialog` and `ReviewPanel` still cross 30 s on a loaded
machine, and **none of them import this code**. Their remaining cause is the one already written
down: `userEvent.type` typing whole sentences character by character, where `paste` would assert the
same thing. That change is still not done.

**Gates:** `npm run build`, `npm run lint`, `CaseEditorPage.test.tsx` 30/30. The full frontend run
was not re-measured, so the 1172 figure in the index predates this commit and is not a claim about
it.
