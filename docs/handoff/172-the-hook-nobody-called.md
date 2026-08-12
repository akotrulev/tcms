# §172 — the hook nobody called, and the charter that had already assumed the screen

**One phase, `frontend/` only: no migration, no contract change, nothing in `backend/` or
`packages/`.** §171's named next slice list, taking the entry that was a missing *capability* rather
than a missing *check* — **`useLinkBlock`'s missing screen**, §169's second recorded gap.

## What was actually missing

`useLinkBlock` and `useUnlinkBlock` have existed in `src/api/queries/documentation.ts` since §97,
with careful javadoc about their cache behaviour and **no caller anywhere in `src/`**. So the whole
of `doc_link`'s documentation end was built and unreachable:

- the two endpoints (`POST .../blocks/{blockId}/links`, `DELETE .../links/{linkId}`),
- `DocBlock.links`, in the required set since §168 and served on every page read,
- `CoverageMarker`, which §168 brought alive and which therefore said **"Not tested"** on every
  block of every specification,
- `TestCase.relatedDocuments`, which §167 served through `case_doc_link`,
- and `ExternalLinkDialog`, which §169 built for the *other* two target kinds.

Five slices around one hole. The marker plan §6i built *to make people maintain links* pointed at an
affordance that did not exist, and the product's only way to create a `DOC_BLOCK` link was the API.

**Nothing could see it.** The hooks compiled, were exported from the barrel, and were covered by
nothing — a hook with no caller is not a type error, not a lint warning, and not a failing test. The
grep that finds it is `useX` against `src/` minus `api/queries/`, and this repo has now paid for that
twice in four sections (§169 found it; §172 closed it).

## The charter had already described the screen

The most useful thing this slice turned up is in `docs/verification/frontend-browser-checks.md`, at
the top, in the fixture charter written at §109:

> **`DOC-1` "Payments"** … Link cases to the bullets **from the documentation editor**
> (`M3-19`'s affordance) …

There was no such affordance. The live browser pass — the one that has been first on the next-slice
list for four consecutive sections — would have reached its third lane, been told to link five cases
to five blocks through a screen that did not exist, and stalled with `M6-36`'s coverage ladder,
`M6-38`'s relationship change and `§165 RC-2`'s both-ends pair all unreachable behind it.

That is a second observation about the same defect class as §165 and §171: **prose that was accurate
about the decision and inaccurate about the code, surviving because no test reads prose.** Here the
prose was a *plan to verify* the product, and it described the product as it was designed rather than
as it was built. A queue row is not evidence that the thing it drives exists.

## This half invalidates, and §169's half must not — same table, opposite rule

The two hooks are four days apart in the log and take opposite cache decisions on rows of one table.
It is not an inconsistency, and the reason is worth carrying:

| | write | read model | rule |
|---|---|---|---|
| §169, case end | `useLinkCaseExternally` | `TestCase.relatedDocuments`, served from **`case_doc_link`** — a projection an outbox event refreshes up to a minute later | **patch the cache, never invalidate.** Invalidating refetches a projection that has not moved and erases the link the server just created: a 201 that reads as a failure. |
| §172, block end | `useLinkBlock` | `DocBlock.links`, assembled off **`doc_link`** inside the same GET (`DocPageService` → `BlockLinks.forPage`) | **invalidate.** The refetch returns the row that was just written, and it is the server — not the client — that knows whether the POST created a link or found one. |

The rule is the read model, not the endpoint. `invalidatePageEverywhere` also invalidates the plan
key by prefix, because one page is reached two ways and a link written from the plan's Business
requirements tab must not leave the documentation screen stale.

## Where the affordance went, and the three decisions that shaped it

**Below the block body, not in the coverage margin.** The margin is 132px and a case reads
`TC-1284 — Refund is refused after 30 days`. More than layout: the two answer different questions,
which is the same split the wire makes — `links` is present and possibly empty because the server
knows exactly who claims this block, `coverage` is null when nobody does.

**Offered on a heading too, though a heading gets no coverage marker.** A heading is structure and
the page's aggregate excludes it; the server nonetheless accepts a link to any block. Withholding the
control on the client's own authority would be the client holding a second, staler copy of the rule —
the argument the block editor's Remove already makes one file over — and it would strand an existing
heading link with no way to withdraw it. `links` renders on every block for exactly that reason.

**`sourceType` comes from the toggle, never from which picker was touched.** `ExternalLinkDialog`'s
rule, and it bites harder here: pick a case, change your mind, pick a suite, and an inferring form
sends `TEST_CASE` carrying a suite's id. That id is a real row of the wrong table, so the server's
project check **passes**, the response is a 201, and the link points at something nobody chose.
`BlockLinks.test.tsx` drives exactly that sequence, because it is invisible from the response.

The relationship is offered rather than defaulted for §169's reason, one degree sharper: this is the
screen that fills the coverage marker, so a form that always sent `VERIFIES` would make every link on
every specification read as proof — including the ones somebody meant as context. `M6-38` in the
browser queue is the row that catches the opposite error from the report end.

## Two things left open, both said out loud

**Remove does not confirm, and that is a match rather than an omission.** `ui-rules` §4 wants a
named-object confirmation on every destructive action; the case end's Remove — the same `doc_link`
row withdrawn from the other side — fires immediately, and has since §169. One act must not have two
behaviours depending on which screen it is reached from, so this half matches the half that shipped.
**The pair is one follow-up, not a spot-fix here** (`ui-rules` §8: sweep the class).

**A link's relationship cannot be edited in place** — only created and withdrawn. `M6-38` asks for
exactly that change and names its own fallback (*"record it as a new link and delete the old"*),
which this slice makes executable. Whether the in-place edit is worth an endpoint is a decision, not
an oversight, and it is recorded here rather than discovered from the queue.

## Changed

| Path | What |
|---|---|
| `frontend/src/features/docs/LinkBlockDialog.tsx` | **New.** The two-kind picker: toggle, server-side case type-ahead (`BindTestDialog`'s, with `filterOptions={(all) => all}` so the server's answer is never re-filtered), suite select, relationship, field-keyed refusals. |
| `frontend/src/features/docs/DocsPage.tsx` | `BlockView` gains a `pageId` prop and a `BlockLinks` row under every block: the chips, their destinations, Remove, and the gated Link button. |
| `frontend/src/lib/labels.ts` | `linkSourceTypeLabels` — keyed off the **contract's** two-value enum, not the column's four, so a value nothing can produce cannot get a label implying it can. |
| `frontend/src/features/docs/BlockLinks.test.tsx` | **New**, 5 tests. Three are about the payload, because the rendering is the half a green build already covered. |
| `docs/agent-context/fe-react.md` | The §172 seam entry, the baseline re-stamp, one session-log line. |
| `docs/verification/frontend-browser-checks.md` | `§172 XM-1`…`XM-5` in lane `A2`, with the charter finding above them. |

Two test-id prefixes one character apart were renamed away before they could be confused:
`Docs.Block.Links.*` is the list, `Docs.Block.LinkDialog.*` is the dialog.

## Gates

`npm run build` clean, `npm run lint` **0 warnings**, `npm run test:run` **1233 passed across 95
files** (+5, one new file, from 1228/94). No backend or `packages/` change, so neither of those gates
was in scope — and no mutation rows, for the same reason.

Browser rows `§172 XM-1`…`XM-5` **recorded and not run** (§65). **This slice is built and
unverified.**

## The next slice

§171's list, minus the entry this one took, plus what this one turned up:

- the **browser queue**, still never run since §110 — **first on this list for four consecutive
  sections**, and this slice is the second reason to stop deferring it: its charter has now been
  shown to describe the product as designed rather than as built, which is a class of defect only
  running it finds;
- **the unconfirmed Remove, on both link screens** — one follow-up covering the case end and the
  documentation end together;
- **editing a link's relationship in place** — `M6-38`'s primary reading, a contract change before
  it is a Java or a TypeScript one;
- **`TeamRef`** — three types, two of them a contract change first; §170 narrowed it to *should the
  three be one*;
- **the bound-and-undeclared query parameter**, §171's recorded gap;
- **Git traceability (M4)**, from §170's deletion of `IngestionRequest.branch`/`.commitSha`;
- **`OUTDATED_SPEC_CHANGED` / `UNLINKED`**, derivable from `case_doc_link`;
- the **read-only-archive** decision §159 deliberately did not take.
