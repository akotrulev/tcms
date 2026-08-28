# §258 — the editor that did nothing when it refused

**Date:** 2026-08-28 · **Branch:** `master` · **One phase, `frontend/` only.** No contract change,
no endpoint, no migration. Three files: `CaseEditorPage.tsx`, its test, and `CaseDraftStatus.tsx`.

Driven by the user, against the `ui-ux-pro-max` skill's UX dataset. The screen it names is the one
§256's entry already recorded as never having been audited: *"no audit in `docs/audits/` has ever
covered the case-authoring screen."*

## THE RULE TO CARRY

**A form whose refusal path runs no code has no refusal path — and every gate will call it green.**

`handleSubmit` was called with **one** argument. React Hook Form's second argument is the invalid
handler, and without it a refused submit executes literally nothing: no announcement, no scroll, no
focus, no log. What the author experienced was a **Save button that did not respond**. The inline
error was there and correct, on a field that on this screen — five panels and two columns tall — is
routinely off screen. Sixty tests, `tsc -b`, oxlint and 1847 green arms all agreed the form was
fine, because every one of them asserts what happens on the paths that *do* run.

This is the same family as §108's `${VAR:?…}`: the defect is in what was **not written**, and a
check of resolved behaviour cannot see an absence.

## What changed

| Seam | What it now is |
|---|---|
| `CaseEditorPage.tsx::ErrorSummary` + `summarizeErrors` | The refusal, named. One focusable `role="alert"` region at the top of the form, one line per invalid field, each line a `setFocus` into its control. Keyed on `submitCount`, so a second refusal is a second mount and focus lands again. Rows are read off `errors`, so it empties itself rather than needing dismissal. `CaseEditor.ErrorSummary` / `.Item`. |
| `CaseEditorPage.tsx::FIELD_LABELS` | One map from form path to the name something other than the field itself calls it. A label written at the control and re-typed in the summary drifts on the first rename, and a summary naming a field the form does not have is worse than no summary. |
| The commit bar (`CaseEditor.CommitBar`) | `Cancel` + `Save` **left the page header** and are pinned to the foot of the viewport, with `CaseDraftStatus` beside them. The status line is a sentence *about a button* — *"use Save changes to publish your work"* — and it used to sit at the top of the form naming a control a thousand pixels below. There is still exactly one `CaseEditor.Submit`. |
| The Conditions panel | Moved from the rail into the **main column, above Steps**. Its own §194 comment had argued for exactly this ("the setup they must have done before the first step") while the panel sat in the metadata rail. The rail is now Placement / Classification / Automation and is `position: sticky`. |
| The step row | One line of chrome instead of two: handle, number, keyword and the **action sentence** share a row; the data cell and expected result sit beneath it, **bottom-aligned** so the two inputs land on the same line rather than offset by the mode switch's height. The corpus caption (`StepActionField`'s new `hint`) appears under the first row only. Rows are sunken fills, not bordered boxes inside a bordered box. |
| `What this proves` | Renamed from *What this case proves* to match `CaseDetail.Summary` — **design-audit finding 19**, whose suggestion was literally *"do it when the case editor is next touched."* Objective and Expected outcome are now side by side; prose fields are capped at a readable measure instead of running ~180 characters to the edge of a 1900px monitor. |
| `CaseDraftStatus` | New `inline` prop — drops the bottom margin for the one caller that spaces the line itself. `minHeight` stays either way; it is what stops the commit bar changing height as the status cycles. |

## Which of §256's five open UX items this closes

§256's entry left five. **Three are now closed** — four always-visible icon buttons per row (they
recede to 0.55 opacity until the row is hovered or holds focus, and never below that, because the
▲▼ pair is the keyboard reorder path); four multiline boxes before the first step with no stated
difference between Objective and Description (three, paired, each with a helper that says what it
is for); and the `Expected outcome` / `Expected result` collision, which is now a collision between
two *panels* rather than two adjacent fields.

**Two remain, both unchanged:** `Enter` still does not advance to the next step (there is still no
`onKeyDown` in the file, and the append still deliberately does not focus — `NO_FOCUS`), and the
Gherkin lens is still a row grid where `POST …/cases/parse-gherkin` and `GET …/cases/{id}/feature`
already round-trip as identity server-side.

## Gates

`npx tsc -b` clean, `npm run lint` 0 warnings, `CaseEditorPage.test.tsx` **60 / 60** including four
new arms for the summary (it does not appear before a submit is asked for; it names every refusal
and does not post; a line moves focus to its field; a line goes as its refusal is answered).

**The full suite was NOT run — at the user's direction, it runs on CI.** The shanks pass was started
and stopped for the same reason. What this entry can claim is the one file it rewrote and the two
static gates; the rest is CI's to report.

## Built and NOT verified

Five browser rows appended — **`§258 CE-1` … `CE-5`**, written and not run. Two of them exist
because jsdom has no layout and therefore cannot see the two things this slice could plausibly have
broken: whether the sticky rail and the sticky commit bar **obscure or collide with** what they sit
over (`CE-2`, `CE-5`), and whether the vertical budget actually came down on a case with ten or more
steps (`CE-3`). No mutation rows: this slice writes nothing new.
