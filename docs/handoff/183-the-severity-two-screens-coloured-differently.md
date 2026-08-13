# §183 — The severity two screens coloured differently, and the row that had said so for five slices

**One phase, `frontend/` only: no migration, no contract change, nothing in `backend/` or
`packages/`.** §182's named next slice, taken as the decision it has been since §179: **INFO stops
being amber.** What the slice turned out to be about is not the colour.

## The decision, which is a reading plan and not a mapping

> **INFO is `var(--color-info)`. Amber means work; blue means context.**

`severityVar` (`lib/statusColors.ts`) is now the only severity→colour table in the SPA, and INFO is
the one value that moves.

The argument for it was written by §179 and got stronger twice while it waited. Amber had been
carrying two meanings at once — *something is wrong now* and *worth knowing* — which is affordable
only while INFO is rare. It stopped being rare in three steps: §178 gave `UNLINKED` a writer and it
fires on any case with no spec link (plan §6o's own mock-up shows `Unlinked 156`), §181 gave
`ORPHANED` one, §182 gave `INCOMPLETE` one. Three of the ten signals are INFO, one of them is most
of a young project, and a colour that appears on most rows has stopped being a signal — it is the
column's background. The cost is paid by the *other* chips: a reader scanning for amber finds it
everywhere and stops scanning, so the WARNING that meant triage-this is the thing actually lost.

**Blue rather than fainter, and that was the whole of the choice.** `--color-text-muted` was the
other candidate and it is wrong here for a reason the table itself states: it is `NEUTRAL`'s colour,
and NEUTRAL is what the dashboard uses for a tile that is *not a signal*. An INFO chip is a signal.
Quiet is not the same as absent, so the treatment has to be a **different claim**, not a weaker one.

## What the slice actually found

**There were two severity→colour maps and they disagreed about exactly the value this slice was
coming to change.**

| | `StatusChip.tsx` (`severityColors`) | `DashboardPage.tsx` (`severityColor`) |
|---|---|---|
| ERROR | `--color-failed` | `--color-failed` |
| WARNING | `--color-blocked` | `--color-blocked` |
| **INFO** | **`--color-blocked`** | **`--color-info`** |
| NEUTRAL | `--color-text-muted` | `--color-text-muted` |

So one severity had two colours. The same signal read as work in the case grid and as context on
the dashboard tile one navigation away — and the navigation is not incidental, it is the product:
a tile's whole purpose is to drill into the cases behind its count.

This is the **third instance of one shape in five slices**, and the previous two are on the same
two screens:

- §179 — `SEVERE_SIGNALS`, the SPA's hand-written list of which signals are severe, drifted from
  `HealthSignal.Severity` about two of ten members;
- §180 — `DashboardBreakdown.color`, composed server-side by string concatenation, against
  `lifecycleVar` in the SPA;
- §183 — this one.

Each was found by the slice standing next to it and each was invisible to both suites. What is new
here is that **both copies were in this stack, in files two slices had just edited, four directories
apart**, and still nobody compared four lines against four lines. §180 moved the *other* two
palettes into `lib/statusColors.ts` for exactly this reason and left this one where it was, because
the slice was about labels. A cleanup that stops at the boundary of its own slice leaves the copy it
did not name.

**Nothing had ever asserted a colour on a tile.** That is the mechanical answer to why it survived:
`HealthChip.test.tsx` pinned the chip's three colours, `HealthPanel.test.tsx` pinned the tiles'
order, links and words, and the tile's `borderLeftColor` had no observer at all. The new test
asserts it **against `severityVar`, never against a literal** — a literal in a test is the fourth
copy, and it is the copy that makes the next disagreement pass.

## The finding worth more than the fix

**The check that would have caught this was already written, in this repo, unrun.**

`§178 SL-1` in `docs/verification/frontend-browser-checks.md` has said since §178:

> The tile is **INFO/neutral, not amber and not red** … Then the case's own chip: "Not linked to any
> spec", **in the quiet colour**.

That row was written against a SPA whose chip rendered amber. It is not an oversight in the row —
it is the correct expectation, recorded by an author who was reasoning about what the reader needs
and had not looked at `severityColors`. It then sat in an unrun queue for five slices while the code
disagreed with it, which is the price §65's batching charges, stated as plainly as it has been
stated: **a written check that is never driven is a claim, not an observer.**

The other two rows went the other way. `§181 OS-1` and `§182 CC-1` each describe the INFO chip as
*"in the same amber as any other INFO chip"* — written by slices that read the code and copied what
it did. Both are **corrected in place with their ids kept** (§65's rule: ids are quoted from handoff
sections and are never renumbered), and the correction is worth noticing on its own: two of the
three rows describing this chip were made false by a decision the third row had already assumed.

## Changed

`frontend/` only, five files:

- **`lib/statusColors.ts`** — `severityVar` arrives (the third table here, after §180 moved
  `statusVar` and `lifecycleVar` in), carrying the decision, the two-map history, and why `NEUTRAL`
  is mapped at all.
- **`components/StatusChip.tsx`** — `severityColors` deleted; `HealthChip` reads `severityVar`. It
  must be exported from `lib/` rather than from here: oxlint's `only-export-components` fires on a
  non-component export from a component file and the gate is zero warnings (§180's finding, applied
  a second time).
- **`features/dashboard/DashboardPage.tsx`** — `severityColor` deleted; the tile reads the shared
  table. **The `?? 'NEUTRAL'` fallback went with it**: `DashboardTile.severity` is required on the
  wire, and the `Record<NonNullable<…>>` it was typed against was a leftover from when it was not.
- **`components/HealthChip.test.tsx`** — the INFO test inverted. It used to assert the amber *and
  explain that the amber was deliberate*, which is the shape of a test that pins a deferral; it now
  asserts the blue **and** that INFO ≠ WARNING, because the inequality is the property the decision
  is about and it survives a change of token. The second assertion reads the table rather than
  rendering a second chip — two renders in one test put two `chip` testids in the document.
- **`features/dashboard/HealthPanel.test.tsx`** — the first assertion in this repo on a tile's
  colour.

## Gates

Run on `shanks`. Frontend **1271 tests across 97 files in 289.4 s** (+1, no new file), `npm run
build` and `npm run lint` (0 warnings) clean. **`backend/` and `packages/` were not touched and were
not re-run** — no contract change, so both `schema.d.ts` copies are byte-identical and no server
code is involved: colour left the wire in §180.

Rows `§183 IC-1`…`IC-4` **recorded and not run** (§65); `§181 OS-1`, `§182 CC-1` and `§179 RS-4`
**revised in place, ids kept**. No mutation rows — nothing in `src/main` on either server stack
changed. **Built and unverified.**

## The next slice

§182's list, minus the entry this one took, and the first item is now the one this slice kept
brushing against:

- **`severityRank` in `DashboardPage` is a second copy of the server's ordering** — §180 looked at
  it and left it, §182 named it, and §183 deleted the colour copy standing beside it in the same
  file while leaving the ordering copy there. `HealthPanel.test.tsx`'s own comment defends it
  (*"the panel sorts again so the order is a property of the screen rather than of one server
  version"*), which makes it a **decision to re-take rather than a defect to fix** — and the two
  arguments are the ones §179 already weighed at the grid, where the answer was the opposite one
  (*"do not sort here"*). Whatever the answer, the two screens should stop giving different ones;
- the **read-only-archive** decision §159 did not take;
- **serving the relabel audit** — §174's recorded absence;
- **`CurrentUser.teams` has no reader** — §175 kept the array on a permission argument;
- **a run has no edit endpoint at all** — §177 leaned on that twice and did not build it;
- **run the browser queue** — ~735 rows, thirteen lanes, one sitting. At the **bottom** at the
  user's direction (2026-08-12).

**Noticed and not fixed, because it is not this slice's file to edit:** `HANDOFF.md`'s state
section still says *"Flyway head is `V44` … `V45` is the next free number"*. `V48` shipped in §174.
The Flyway numbering rule is the one place in this repo where a stale number is a runtime failure on
an already-migrated database rather than a documentation defect, so it is worth a line in whatever
slice touches that section next.
