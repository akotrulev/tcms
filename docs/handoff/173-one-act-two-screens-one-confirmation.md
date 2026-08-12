# §173 — one act, two screens, one confirmation

**One phase, `frontend/` only: no migration, no contract change, nothing in `backend/` or
`packages/`.** §172's own recorded gap, taken as the next slice at the user's direction — and taken
as *the pair*, which is the entire content of the decision.

## The gap, and why §172 deliberately left it

`ui-rules` §4 is explicit and takes no exception for small actions: **every destructive action
confirms, naming the specific object**, with a verb-labeled danger-styled button and never a bare
"are you sure?". Neither end of a `doc_link` withdrawal did. §169 shipped the case end's Remove as an
immediate delete; §172 built the documentation end four sections later and **matched it on purpose**,
recording the pair as one follow-up rather than fixing one side.

That was the right call and it is worth stating why, because "the rule says so" would have argued for
fixing it in §172. `ui-rules` §6 — *one name, one concept, one verb per operation* — and §8's
*sweep, don't spot-fix* both apply, and a confirmed Remove on the documentation page beside an
unconfirmed Remove on the case page is a **worse** state than two unconfirmed ones: it teaches the
reader that the control means different things on different screens, and it is the state a
rule-driven fix would have produced.

## The fix is a component, not two dialogs

`components/UnlinkDialog.tsx` — one confirmation raised by both screens, which is `ui-rules` §8's
"fix at the highest-leverage point": a third link surface inherits the behaviour by construction
rather than by whoever writes it remembering. The two ends differ in exactly two places, and both are
the caller's to supply:

| | the object it names | the consequence it states |
|---|---|---|
| documentation page | the **source** — `TC-42 — Refund is refused after 30 days` | *"…this block's coverage is recounted. The case itself is not touched."* |
| case detail | the **target** — `acme/checkout · src/Refund.java:40–58 at 4f9c2b1` | *"Only the record of the link is removed. The code and the case are untouched."* |

The consequence is per-caller rather than shared **because the two genuinely differ**: withdrawing a
link from a block changes what the coverage marker on the screen in front of the reader says;
withdrawing an external link changes nothing but the record. A sentence vague enough to cover both
would be worse than none, which is the same argument `describeError` makes about generic copy.

Everything else is shared and must stay shared — title, button label, danger colour, Cancel, the
reset-on-open, and the decision that **the failure stays on the dialog** rather than going to the
corner toast (`CaseDeleteDialog`'s call: a 403 or 404 about the row the reader just clicked is a
sentence about that row, and a toast detaches it).

## `describeLinkTarget`, and the second implementation of a rule that has already failed once

The case end names a *target*, and its three kinds are rendered as markup by `DocLinkTarget` — an
anchor, a router link, an identifier group. None of those belong in a confirmation: **a link that
navigates away is a control the reader did not mean to use**, and a dialog whose named object can
be clicked out of is a dialog that can be escaped by accident.

So `lib/links.ts` holds the text form. It is a second implementation of one rule and the rule is the
one this repo has already paid for: **switch on `targetKind`, never on which field is populated.**
§169 found a renderer branching on `externalRepo` that had drawn a URL link as a blank line for
seventy sections. `links.test.ts` therefore does not merely check the happy shapes — it feeds a
`EXTERNAL_URL` row **with git fields populated** and asserts the address comes back, which is the
only assertion that can tell the two implementations apart.

Six pure-logic assertions, in a `.test.ts` with no `render()`: the single-line range that must not
read `40–40`, the whole-file link with no range at all, the pin that stays part of the name, and the
degenerate `DOC_BLOCK` row with neither key nor title, which returns *"a documentation block"* rather
than an empty string — because a confirmation naming nothing is exactly the "are you sure?" the rule
forbids.

## What the tests are written to catch

Five new component assertions across the two screens, and the two that matter are the ones no
"does the button call the hook?" test could make: **the click used to *be* the delete, and the whole
change is that it is no longer.** So each end asserts that after clicking Remove, `api.del` has
**not** been called, and that after Cancel it still has not and the row survives — a confirmation
that removed optimistically and reinstated on cancel would pass every other check on the panel.

Both existing withdrawal tests grew a confirm click rather than being rewritten; they still assert
the same URLs and the same post-withdrawal cache behaviour (§169's patch-don't-invalidate on one end,
§172's invalidate on the other), which is what keeps this slice from silently changing them.

## Changed

| Path | What |
|---|---|
| `frontend/src/components/UnlinkDialog.tsx` | **New.** The shared confirmation — title, named object, per-caller consequence, danger confirm, error on the dialog, reset on open. |
| `frontend/src/lib/links.ts` | **New.** `describeLinkTarget` — the text form of a link's target, switching on `targetKind`. |
| `frontend/src/lib/links.test.ts` | **New**, 6 pure assertions. |
| `frontend/src/features/docs/DocsPage.tsx` | `BlockLinks` holds the link being withdrawn (not a boolean — the list renders one control per row) and raises the dialog. |
| `frontend/src/features/cases/CaseDetailPage.tsx` | `DocLinksPanel` the same, naming its target through `describeLinkTarget`. |
| `frontend/src/features/docs/BlockLinks.test.tsx` | +2, and the existing withdrawal test grew a confirm click. |
| `frontend/src/features/cases/DocLinksPanel.test.tsx` | +2, same shape from the other end. |

## Gates

`npm run build` clean · `npm run lint` **0 warnings** · `npm run test:run` **1243 passed across 96
files** (+10, one new test file, from 1233/95). `UnlinkDialog` has no test file of its own on
purpose: it is driven through both screens that raise it, which is where a difference between the
two ends would actually show. No backend or `packages/` change, so neither gate was in scope and
there are no mutation rows.

Browser rows `§173 UL-1`…`UL-3` **recorded and not run** (§65), and `§172 XM-5` was **corrected**
rather than appended to — it asserted the absence of the confirmation this slice added. **This slice
is built and unverified.**

## The next slice

The browser queue moves to the **bottom** at the user's direction (2026-08-12); it was first on this
list for five consecutive sections and is now scoped rather than merely deferred — see
`frontend-browser-checks.md`'s *"Can this be driven on `shanks`?"*.

- **Editing a link's relationship in place** — `M6-38`'s primary reading, taken next;
- **`TeamRef`** — three types, two of them a contract change first;
- **the bound-and-undeclared query parameter**, §171's recorded gap;
- **Git traceability (M4)**, from §170's deletion of `IngestionRequest.branch`/`.commitSha`;
- **`OUTDATED_SPEC_CHANGED` / `UNLINKED`**, derivable from `case_doc_link`;
- the **read-only-archive** decision §159 deliberately did not take;
- **run the browser queue** — ~698 rows, thirteen lanes, one sitting.
