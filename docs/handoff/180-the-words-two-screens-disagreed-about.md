# §180 — The words two screens disagreed about, and the colour nobody had ever compared

**Two phases: `backend/` + contract + both `schema.d.ts` copies + `packages/`, then `frontend/`. No
migration, no new table, no new column, no new endpoint.** §179's named next slice, taken as it was
measured: **`DashboardTile.label` is gone, `HealthSignal.label()` is deleted, and the dashboard
labels a tile from its `key`.**

## The defect, which is one navigation long

`HealthSignal` carried three properties: the JPA attribute, a severity and a **label**. §179 sent the
severity to the wire and deleted the SPA's copy of it. It measured the label at the same time, wrote
the table down, and left it — because unlike the severity this was not one member being moved, and
the reason is in the shape of the field it rides on:

| Signal | Server (`DashboardTile.label`) | SPA (`healthLabels`) |
|---|---|---|
| `OUTDATED_SPEC_CHANGED` | Outdated — spec changed | Linked spec changed |
| `STALE_APPROVAL` | Stale approval | Approved before its last edit |
| `CONDITION_DRIFT` | Condition drift | Shared condition changed |
| `STALE_AUTOMATION` | Stale automation | Automation stopped reporting |
| `UNLINKED` | Unlinked | Not linked to any spec |
| `ORPHANED` | Orphaned | Owner deactivated |
| `NEVER_EXECUTED`, `LONG_UNEXECUTED`, `FAILING_REPEATEDLY`, `UNDECLARED_AUTOMATION` | *(agree)* | *(agree)* |

**Six of ten.** The dashboard renders `tile.label`; every other surface in the app renders
`healthLabels`. So a reader on the "Needs attention" panel clicks the tile marked *"Outdated — spec
changed"*, arrives — through the tile's own `filter`, which is the entire design of §6o — at a case
list whose filter chip and whose every grid chip read *"Linked spec changed"*, and has no way to
tell whether they are looking at what they clicked. **Each screen was internally consistent. The
defect existed only in the transition between them**, which is why nothing on either side of it
could see anything wrong, and why the fix is not "make the strings match".

`CaseService.facets` has argued the direction since M1, in writing: enum *words* belong to the SPA's
catalogue, *"the one place a new member is a compile error until it is labelled. A second set of
labels here would be the one that goes stale, and the symptom would be a filter chip and a grid cell
disagreeing about the name of the same status."* That is a description of what happened, written a
hundred and seventy slices before it was measured. There is also a hard reason the wire cannot own
these words even if it wanted to: `healthLabels` must name `INCOMPLETE`, which the server's enum does
not contain and no per-case payload can ever raise.

## What the label was actually paying for, and why it turned out to be nothing

`LifecycleStatus`'s header held the counter-argument, and it is a good one:

> a tile is a generic `(key, label, count, filter)` shape precisely so a *new* signal computed
> server-side renders as a working, drillable tile with no frontend change at all. That property is
> worth one duplicated set of four English words.

**The property is real; the label was not buying it.** `HealthSignal` is a *contract* enum. A new
member changes `openapi.yaml`, regenerates both `schema.d.ts` copies, and makes
`healthLabels satisfies Record<HealthSignal, string>` a **compile error** in the SPA until it is
named — and the case grid's chip and the filter bar's dropdown need that word regardless of what the
dashboard does. There has never been a path by which a new signal reached a tile and no other
surface. What genuinely makes a tile extensible is that its `filter` is **opaque to the client**:
`tileHref` serializes a map it does not interpret. That is untouched, and it is the whole of the
property.

So the trade was: pay a duplicated vocabulary, receive a guarantee already provided by something
else. It came due, as duplicated vocabularies do, at six of ten.

The graceful-degradation half is kept explicitly rather than by accident. `labelForKey` falls back to
the key itself, so a tile whose key this build has never heard of renders as
`SIGNAL_FROM_THE_FUTURE` and still drills. That is a legible bug report where a blank tile is a
rendering fault nobody can describe, and it has its own test.

## The second finding: the colour was the same defect, one field over, with no test on either side

`DashboardBreakdown`'s segments carried a `color`. The server composed it:

```java
"var(--color-" + status.name().toLowerCase(Locale.ROOT).replace('_', '-') + ")"
```

and `StatusChip.tsx` held, privately, the identical four strings:

```ts
const lifecycleVar: Record<LifecycleStatus, string> = { DRAFT: 'var(--color-draft)', … };
```

**Two mechanisms — string concatenation and a hand-written table — producing one mapping, in two
stacks, compared by nothing.** This is §179's `SEVERE_SIGNALS` finding exactly, at a colour instead
of a severity, and it was in worse shape: `SEVERE_SIGNALS` at least had a test on the chip it
coloured, while `SegmentView.color` had **no assertion anywhere in either suite**. `git grep color`
over `DashboardEndpointTest` returned nothing. It had been on the wire since the dashboard shipped.

Its javadoc defended it, and the defence is sound about a question nobody asked:

> a CSS custom property name rather than a hex value, so the segment follows the theme the rest of
> the page is drawn in — including dark mode, which a server-side hex would break in exactly one
> place on one screen.

Every word of that is true and it argues *custom-property versus hex*. It says nothing about whether
the mapping should be on a wire, and reading it, you agree with it and move on. **An argument that
wins a narrower question than the one in front of you is one of the more effective ways for a
duplicate to survive review** — §168's shape, where the obstacle named in a comment had already
moved.

The colour came off the wire with the labels. `lifecycleVar` and `statusVar` moved to
`lib/statusColors.ts` — the colour half of what `labels.ts` is the word half of — because exporting
them from `StatusChip.tsx` trips oxlint's `only-export-components`, and the gate is zero warnings.

## Where the line is drawn, because it is not "the client owns all words"

Three fields on this one response are English text, and only two of them left:

| Field | Stays? | Why |
|---|---|---|
| `DashboardTile.label` | **gone** | a rendering of a closed enum the client already labels |
| segment `label` / `color` | **gone** | same, for `LifecycleStatus` |
| `DashboardBreakdown.label` | **stays** | `Payments › Refunds`, or a person's display name **in this company** — data no client can resolve |
| `LifecycleStatus.label()` | **stays, one caller** | `CaseBulkService.leadOnly` composes *"Only a test lead can change a case that is already approved."* |

That last row is the interesting one. The accessor survives the deletion of everything it was
originally written for, on a different justification: a **refusal is a sentence**, and §149 settled
that the server's sentence is printed as it arrives. The client cannot compose it — the sentence is a
judgement about a row in a four-hundred-row bulk selection the caller never saw. The test of the
distinction is what a disagreement costs: two spellings of a *label* put one status under two names
on two screens, where a sentence is read once and quoted nowhere. `§180 TL-M4` is the mutation that
holds it, and `§180 TL-M3` mutates the *other* side — deleting a bar's own label must redden three
tests, because a slice that read "labels belong to the client" one step too far would make that
mutation green.

## A third thing, found by reading the file rather than looked for

`DashboardService.quietDays` — *"the same thirty days, all empty, for a project with nothing in it"* —
**had no caller.** §96 deleted the empty-tree guard that was its only one, and the method stayed.

Worth a note on why four slices of rules written to find exactly this did not:
`everyDeclaredRepositoryMethodHasACaller` (§152) ranges over repository interfaces and
`everyPublicBeanMethodIsExercisedInProduction` (§153) over public bean methods. A **private static
helper** is in neither population, and javac has no lint for one. It was invisible to every gate this
project owns, on both counts — §170's finding at an enum, now at a method. Deleted, with the
reasoning left in a comment where the method was.

## Changed

**Phase 1** — `docs/specs/openapi.yaml` (`DashboardTile` loses `label`, its `required` shrinks, and
the description now says why; `DashboardBreakdown`'s inline segment loses `label` and `color`, and
the schema gains the description that draws the data-versus-presentation line); both `schema.d.ts`
copies; `DashboardView` (`TileView` and `SegmentView` components, and the headers on all three
records); `DashboardService` (`lifecycle`, `health`, `segments`, the `Locale` import, `quietDays`
deleted); `HealthSignal` (`label` field, constructor arg, accessor and all ten constant arguments
deleted; header rewritten); `LifecycleStatus` (header replaced — the accessor stays for
`CaseBulkService`); `DashboardEndpointTest` (`keysAreStableAndLabelsAreNot` becomes
`tilesCarryNoWords` and asserts **absence**; new `barsKeepDataWordsAndSegmentsLoseRenderedOnes` in
`ByFeature`).

**Phase 2** — `lib/labels.ts` (`labelForKey`, and `healthLabels`' header closes the divergence note
§179 left); `lib/statusColors.ts` (**new** — `statusVar` and `lifecycleVar` moved out of
`StatusChip.tsx`); `components/StatusChip.tsx` (the two maps removed, imported instead);
`features/dashboard/DashboardPage.tsx` (`TileRow` takes a `labels` catalogue; both rows pass theirs;
segments label and colour from the key; `tileHref`'s extensibility claim narrowed to the link);
`features/dashboard/HealthPanel.test.tsx` (fixtures lose `label`);
`features/dashboard/TileLabels.test.tsx` (**new**, 4 tests); `features/board/BoardPage.tsx` (the
`describeCard` comment §179 corrected into being *false* is true again, and all three states are
left in the file in order).

## Gates

**Run on `shanks`** per `CLAUDE.md`'s remote-gate section.

- Backend `./gradlew build`: **SUCCESSFUL in 7m17s — 2574 tests across 521 result classes, 0
  failures, 0 errors** (**+1 test, no new result class**: `DashboardEndpointTest` gained
  `barsKeepDataWordsAndSegmentsLoseRenderedOnes` in the existing `ByFeature` group and rewrote
  `tilesCarryNoWords` in place).
- Frontend `npm run test:run` on `shanks`: **1266 passed / 1266 across 97 files in 185.1 s** (**+4
  tests, +1 file** — `TileLabels.test.tsx`; §129's timeout cliff did not recur).
- Frontend `npm run build` and `npm run lint` clean locally — the lint gate is what moved the two
  colour maps into `lib/`, so it is load-bearing on this slice rather than incidental.
- `packages/`: built in order (client → cli → mcp), lint clean, **230 tests**. No package reads the
  dashboard endpoint; the only change there is the regenerated `schema.ts`.

Browser rows `§180 TL-1`…`TL-4` and mutations `§180 TL-M1`…`TL-M4` **recorded and not run** (§65).
**Built and unverified.**

`§180 TL-3` is worth flagging as more than a formality: **the segment colour has no observer that is
not a browser.** `toHaveStyle` compares the declaration, not the resolved value, so a test can assert
`var(--color-draft)` while the property is undefined in the stylesheet and the bar paints nothing.
The dark-mode half of that row is checking the exact property the server's version was defended for.

## The next slice

§179's list, minus the entry this one took, plus what this one found.

- **`ORPHANED`, the last writerless health signal** — needs a decision before a statement: what "the
  owner left the project" means when a grant is revoked, a membership is deactivated, or a team is
  dissolved. It is also the column `Boundaries.otherSignalsAreNotClobbered` uses, so that slice pays
  for a new assertion. **Now the strongest candidate**: with the labels settled there is exactly one
  signal in the enum whose chip can never appear, and its tooltip, its label and its severity are all
  already written;
- **`CaseHealth.incomplete`** — writerless *and* readerless, `ORPHANED`'s shape, and the member the
  contract has and the server's enum does not;
- **quietening `INFO` on a chip** — §179's recorded reading-plan decision, still not taken, and
  `UNLINKED` still puts an amber chip on most rows of a young project;
- **`severityRank` in `DashboardPage` is a second copy of the server's ordering** — noticed here and
  left, deliberately: it is four lines over a *closed* enum whose order the server also sorts by, and
  §179 already argued that the SPA sorting again is a property of the screen rather than of one
  server version. Recorded because "we looked at it" and "it is not there" are different claims;
- the **read-only-archive** decision §159 did not take;
- **serving the relabel audit** — §174's recorded absence;
- **`CurrentUser.teams` has no reader** — §175 kept the array on a permission argument;
- **a run has no edit endpoint at all** — §177 leaned on that twice and did not build it;
- **run the browser queue** — ~723 rows, thirteen lanes, one sitting. At the **bottom** at the user's
  direction (2026-08-12).
