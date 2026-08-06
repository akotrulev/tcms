# §104 — Two links per execution row: the key opens the case, the title opens the verdict

**Date:** 2026-08-06 · **Lane:** frontend only · **Migrations:** none · **Contract:** unchanged

The ask was *"in test run, add columns testid and test case name in the grid, add tooltip to the id
that will take you to the test case itself, while the title takes you to the run to pass/fail"*.

Both columns already existed — §100 phase C shipped Grid mode with a **Key** column and a **Case**
column. What did not exist was the navigation the ask is actually about, and reading the two grids
side by side showed why: they had **opposite** answers to one question.

| | key column | title column |
|---|---|---|
| `RunDetailPage` (the run's checklist) | → the **test case** | → the **execution** |
| `ExecutionGrid` (Grid mode) | → the **execution** | *inert text* |

So from Grid mode — the mode built for sweeping a re-run, where a tester is most likely to want to
check what a case actually says — there was **no way to reach the test case at all**. Not a crash,
not a type error, not a lint warning: a screen that worked and could not do one thing, while its
sibling two files away could.

## What changed

| File | Change |
|---|---|
| `features/runs/ExecutionRowLinks.tsx` | **new** — `CaseKeyLink` and `ExecuteCaseLink`: the two hrefs, the tooltip, and the two accessible names, decided once |
| `features/runs/ExecutionGrid.tsx` | both cells replaced by the shared pair; the key now goes to the case and the title to the execution, carrying `?mode=`. A fifth rule added to the file's header, which lists the rules it would be easy to lose |
| `features/runs/RunDetailPage.tsx` | the same pair, replacing its own hand-rolled copy; row testids collapsed onto a `RowTestId` const |
| `features/runs/ExecutionRowLinks.test.tsx` | **new** — 5 tests over **both** grids |

**No contract change, no `gen:api`, no migration, no mock handler change, no backend file touched.**
`TestExecutionSummary` has carried `caseId` beside `caseKey` since M1 — the field the ask needs was
already required on the schema and already served by the mock (`db/executions.ts` stamps it from the
source case). Nothing had to be faked client-side and nothing had to be widened.

## The decision: one module, two grids, two different layouts

`ui-rules` §8 says the highest-leverage fix is one renderer rather than per-page copies, and this is
the textbook shape of the defect it describes — one rule, implemented once in each direction, with
each implementation locally defensible.

But the fix is deliberately **not** one shared *cell*. The two grids give this information genuinely
different room: `RunDetailPage` puts both links in a single **Case** cell beside the pinned
`v{caseVersionNo}` chip, while Grid mode gives the key its own narrow column so keys align down the
page for scanning. Forcing one cell component onto both would have made the layout the shared thing
and left the *destinations* — the part that was actually wrong — still decided twice.

So the shared unit is the pair of **links**: `CaseKeyLink` and `ExecuteCaseLink`, each owning its
href, its tooltip and its accessible name, composed by whichever cell layout the grid wants. A third
grid over executions composes them rather than re-deciding where a key points.

### Why that way round

**The key opens the test case.** A key is what a tester quotes in a defect report and searches for;
it names the case, not this one attempt at it. **The title opens the execution** — the pass/fail
screen — because it is the bigger target, it is what a scanning eye recognises, and it is what the
tester is on this screen to do. That is the ask verbatim, and it is also what `RunDetailPage` had
already concluded independently.

## The accessibility decision, which is the part a gate cannot see

Two links in one row pointing at two places is the shape that produces a screen-reader dead end: a
link list reading `TC-41`, `TC-41`, `TC-41` with no way to tell which one goes where. So:

- **The key's accessible name is the tooltip's sentence** — *"Open test case TC-41"* — set as an
  explicit `aria-label` **and** as the `Tooltip` title, the same string in both. MUI's `Tooltip`
  already sets its own `aria-label` from `title` when the child has none, so writing the two
  separately would leave two sentences free to drift, one of them never read aloud. The tooltip is
  also the answer to the sighted reader's question, which is why the ask asked for it: the affordance
  of a bare monospace key is not obvious, and *"add tooltip to the id that will take you to the test
  case"* is a request for the destination to be stated, not for a second copy of the key.
- **The title's name is its visible text plus the destination** — *"&lt;title&gt; — record a result
  for TC-41 in this run"*. The visible label is contained in the accessible name, so WCAG 2.5.3
  (Label in Name) holds and voice control still works on the words on screen.
- **The title's tooltip stays the untruncated title.** That cell ellipsises, so its tooltip is a
  *truncation* affordance, not a destination one. Cramming the destination in there too would make
  one tooltip answer two questions and answer the more useful one worse. The destination went to the
  accessible name instead, where it costs a sighted reader nothing.

## Testids

`.CaseLink` (key → case) and `.Link` (title → execution), composed onto each grid's static row
locator per `test-ids` rule 6 — never interpolated with a row or execution id. `RunDetailPage`'s two
locators are **unchanged by name**; Grid mode gains `Execution.Grid.Row.CaseLink`, and its existing
`Execution.Grid.Row.Link` keeps its meaning of *the row→detail affordance* while moving from the key
to the title. Zero collisions.

## Testing

`ExecutionRowLinks.test.tsx` asserts over **both grids**, not over the shared module alone — a unit
test on `ExecutionRowLinks` would have been green throughout the state §100 actually shipped, because
the bug was one caller not using the rule rather than the rule being wrong. Five tests: the key's
href (whole string, because `caseId` and `execution.id` are two uuids of the same shape on the same
row and a swap is invisible), the title's href with `?mode=grid`, the mode-less default, the two
distinct accessible names, and the run-detail grid holding the same pair with no mode suffix.

**Mutation-checked**: pointing `caseHref` at a wrong path reddens exactly 3 of the 5.

## Gates

`npm run build` clean · `npm run lint` 0 · `npm run test:run` **941/941 across 69 files** (from
936/68 — five new tests, one new file; no new `api.*` call site, so §70's contract-hygiene checks
generated nothing).

**Built and unverified** — 7 browser rows queued (M4-41…M4-47), **none run** (§65). Two of them
(M4-44 keyboard, M4-45 screen reader) are the ones that carry this slice's real risk: the accessible
names are the half of the change that no automated gate in this repo and no glance at the screen can
confirm.

## Found and not fixed

Nothing new. The one thing worth naming as deliberate: `ExecutionQueue` — Split mode's rail — was
left alone. Its whole row is one link to the execution, with the key and title *inside* it; splitting
it into two would mean a link inside a link, which is invalid HTML. A tester in Split mode reaches
the case through the execution screen's own header link (`Execution.CaseLink`, since §100), so the
capability is not missing there in the way it was missing from Grid.
