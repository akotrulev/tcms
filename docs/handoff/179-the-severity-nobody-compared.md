# §179 — the severity nobody compared, and the order that hid the failure behind a `+1`

**Two phases: `backend/` + contract + both `schema.d.ts` copies + `packages/`, then `frontend/`. No
migration, no new table, no new column** — the value this slice puts on the wire has been on the
enum since M0 and on the wire *once*, for a dashboard tile, the whole time.

§178's named next slice, the first entry on its list: **`SEVERE_SIGNALS` versus
`HealthSignal.Severity`.** §178 found the two copies disagreeing about two of the ten members,
recorded it at the site, and did not fix it — deliberately, because *"the honest fix is a comparison
rather than an edit: the question is whether severity belongs on the wire for a bare signal, which
deletes the list."*

**Decided: on the wire.** `TestCaseSummary.healthSignals` is an array of `RaisedSignal`
(`{signal, severity}`) instead of an array of enum codes, `SEVERE_SIGNALS` is gone, and the chip no
longer holds an opinion about which signals are severe.

## The two copies, and which of the two properties each argument sends where

The interesting part of this slice is that **the frontend kept a hand copy of two of the enum's three
properties, and this repository already contained an argument about each — pointing in opposite
directions.**

`HealthSignal` carries three things beside each constant: the JPA `attribute` (for Criteria), the
`severity`, and the `label`. Its header argues that severity and label sit there *"so that adding a
tenth signal is one line here and nothing anywhere else — the alternative, a severity map in
`DashboardService`, is a second list to keep in step"*.

That sentence was **false for as long as there was a chip**, and it named the wrong file. The second
severity map was not in `DashboardService`; it was `SEVERE_SIGNALS` in `StatusChip.tsx`, one stack
over, where nothing on the Java side could see it. Its own comment predicted the outcome — *"if a
signal ever renders here with a severity beside it, this list should go rather than be kept in
step"* — and by §178 the two had drifted about **two of the ten members**: the list carried
`FAILING_REPEATEDLY` (server: WARNING) and lacked `CONDITION_DRIFT` (server: ERROR). So one case read
**red on a dashboard tile and amber on its own chip**, on two screens the tile links between.

**Severity goes to the server. The label stays with the SPA, and that is not an inconsistency.**
`CaseService.facets` has argued the label direction since M1, in writing: every facet *value* is sent
with its enum constant as both id and label *"because the SPA labels enum values from its own
catalogue — the one place a new member is a compile error until it is labelled. A second set of
labels here would be the one that goes stale, and the symptom would be a filter chip and a grid cell
disagreeing about the name of the same status."* There is also a hard reason the wire cannot own the
words: the filter bar needs a label for **every member of the enum**, including `INCOMPLETE`, which
no server constant can ever send — a per-case payload carries only the raised subset.

Severity is not words. It is a judgement about how wrong a case is, made where the derivations are,
and it is needed only for signals that were actually raised. The two properties therefore go opposite
ways, each with an argument already in the code, and after this slice both arguments describe the
code.

## What that leaves standing, measured rather than assumed

**`HealthSignal.label()` is on the wire through `DashboardTile.label`, which is exactly the second set
of words `CaseService.facets` refuses — and six of the ten disagree with `lib/labels.ts`:**

| Signal | Server (`DashboardTile.label`) | SPA (`healthLabels`) |
|---|---|---|
| `OUTDATED_SPEC_CHANGED` | Outdated — spec changed | Linked spec changed |
| `STALE_APPROVAL` | Stale approval | Approved before its last edit |
| `CONDITION_DRIFT` | Condition drift | Shared condition changed |
| `STALE_AUTOMATION` | Stale automation | Automation stopped reporting |
| `UNLINKED` | Unlinked | Not linked to any spec |
| `ORPHANED` | Orphaned | Owner deactivated |
| `NEVER_EXECUTED`, `LONG_UNEXECUTED`, `FAILING_REPEATEDLY`, `UNDECLARED_AUTOMATION` | *(agree)* | *(agree)* |

The dashboard renders `tile.label` and every other surface renders `healthLabels`, so clicking the
tile *"Outdated — spec changed"* lands on a case list whose filter chip and grid chips read *"Linked
spec changed"* — the drill-down `DashboardTile.filter` exists for, arriving under a different name
than the tile that sent you. **Not taken here**, and the reason is that it is not a member being
dropped: the tiles are not all signals (`DashboardService.lifecycle` sends `LifecycleStatus.label()`
through the same field, and a lifecycle tile's key is not a signal code), so the repair is a change to
how a tile is built and it takes the lifecycle labels with it. Recorded at `HealthSignal`'s header, in
`RaisedSignalView`'s, and as the next slice below. And `BoardPage.describeCard`'s claim that it labels
*"through the same `healthLabels` map the dashboard's 'Needs attention' panel uses"* is **false** —
that panel uses the server's — which is §162's shape again: prose naming the wrong mechanism reads as
coverage.

## The second finding, which nobody was looking for: the order was doing damage

`CaseGrid` renders `healthSignals[0]` as the row's one visible chip and the rest as a `+n`. The list
arrived in **enum declaration order**, in which `UNLINKED` sits five places ahead of
`FAILING_REPEATEDLY` and one ahead of `ORPHANED`.

So a case that was both unlinked and had stopped reporting automated results showed **"Not linked to
any spec"** and hid the failure behind a `+1`. This was harmless while `case_health` was mostly empty
and **§178 made it live**: `UNLINKED` fires on most cases of a project that has not started linking,
so from §178 the grid's Health column is, for most rows of most projects, showing the least important
thing true of that case.

`DashboardService.health` sorts its tiles and writes the reason out: *"Sorted here rather than left to
the SPA, which sorts by severity too: the order is a property of the answer, and a client that forgot
would render a broken case below a missing link."* Same enum, same module, and the grid was doing
precisely what that sentence forbids. `CaseHealth.signals()` sorts by severity now, stably, so ties
keep the enum's own order — and the SPA deliberately does **not** sort, because a client-side sort
would be the second copy of the ranking this slice just deleted.

**Nothing could have caught this.** The wire was right, the component was right, and neither end holds
"which of a case's problems is the one to show". It took reading the list for an unrelated reason,
which is the same way §178 found the severity drift.

## The third: deleting the list would have deleted its only test

`HealthChip.test.tsx` existed for one reason, stated in its own header: the `STALE_AUTOMATION` /
`UNDECLARED_AUTOMATION` asymmetry is a decision whose violation is *"indistinguishable from a
tidy-up"*, and — in its words — *"the server draws the same line in `HealthSignal.Severity` and it is
not on the wire for a chip, so nothing but this test holds the two sides together."*

That test held the wrong side. It pinned the SPA's copy, which had drifted; and removing the copy
removes the test's subject. **A slice that moves a decision to the side that owns it and leaves its
only coverage behind is §153's finding** (`ProvisionCompanyCommand.isRequestedBy`: six assertions
aimed at the dead copy while the live guard had none), so `RaisedSignalTest` arrives in the same
commit that empties the frontend one:

- **`DECIDED`, a table of all ten severities written out rather than read off the enum.** A test that
  asked `severity()` what the severity is would assert the enum equals itself. The point is that each
  member is an argued decision with a paragraph behind it, so changing one has to be typed twice.
  Pinned as a set both ways, so a new signal with no entry fails.
- **`theAutomationPairStaysAsymmetric`** — the frontend assertion, in the same words, on the side that
  decides it.
- **`severityIsDeclaredWorstFirst`** — because a `Comparator` over an enum orders by **ordinal**, so
  alphabetising `Severity`'s four constants (the tidiest possible edit) silently inverts both this
  sort and the dashboard's tiles, and fails nothing else.
- **The order group**, including the regression by name: `{UNLINKED, STALE_AUTOMATION}` must come back
  worst first, and `tiesKeepDeclarationOrder` must stay green when the sort is deleted — a tie-break
  assertion that fails without the sort is asserting the sort.

It is a **no-database, no-context** class (§150's lane): the two properties under test are a `switch`,
a comparator and a table of constants. The fixture builds a `CaseHealth` by reflecting over the very
attribute names `HealthSignal` publishes, so a constant whose attribute stopped resolving fails here
as well as in `CaseHealthAttributesTest`, and a fixture that silently raised nothing fails loudly
instead of passing vacuously.

## Two smaller things the contract change turned up

**`DashboardTile.severity` was an anonymous inline enum**, and therefore in no population any rule
could see — §170's inline-shape finding, arriving at an enum rather than at an object. It is a named
schema (`HealthSignalSeverity`) with a `ContractEnumTest` owner entry now, which is the first time the
four values have ever been compared to `HealthSignal.Severity`'s. §161's audit could not have found
it: it reads `enumSchemas()`, and this was not one.

**`CaseGrid`'s `+n` tooltip printed raw enum codes** — `STALE_AUTOMATION, UNLINKED` — the one place in
the SPA a reader could meet a constant, in the file whose whole convention is that words come from
`lib/labels.ts`. Invisible without two signals on one case, which before §178 was rare. Fixed here
because the same expression had to change anyway; queued as `§179 RS-2` because only a browser can see
it.

## The costs, stated

- **Severity is repeated per signal per case** rather than sent once as a catalogue. A page of two
  hundred cases carries a short enum name it could have looked up. Paid deliberately: a chip renders
  where its row is, and a catalogue fetched separately makes a *border colour* depend on a second
  request completing. `DashboardTile` already works this way one screen over.
- **The case version snapshot changes shape.** `CaseVersions` serializes `TestCaseDetailView` as the
  version body, so v-next rows record `[{signal, severity}]` where older ones record `["UNLINKED"]`.
  Inert, and checked rather than assumed: `TestCaseVersion.getSnapshot()` has **no reader** in
  `src/main` at all — §153's write-only seam — so nothing deserializes either shape. The content hash
  is computed over `CaseEdit`, not over this view, so no recompute can cause a save that changed
  nothing to write a version.
- **`INFO` still renders in the same amber as `WARNING`.** Since §178 that means most rows of a young
  project carry an amber chip. Quietening it is a change to the grid's reading plan rather than a
  mapping fix, so it is a decision recorded (`severityColors`' comment, `HealthChip.test.tsx`) and not
  smuggled into a slice about who owns the value.
- **`@tcms/mcp` sends the codes, not the pairs.** A pair doubles the tokens of a field that fires on
  most cases, for a ranking the *order* now carries — and that block's stated rule is that a field
  costing tokens on every case has to earn them. A tool that needs the severity itself should send the
  pair rather than reintroduce a table of them, which is what this slice deleted from the SPA.

## Changed

**Phase 1** — `docs/specs/openapi.yaml` (`HealthSignalSeverity` and `RaisedSignal` new;
`DashboardTile.severity` becomes a `$ref`; `TestCaseSummary.healthSignals` items and the order note);
both `schema.d.ts` copies; `RaisedSignalView` (new); `CaseHealth` (`signals()` returns the pairs,
sorted; the order argument); `HealthSignal` (the "nothing anywhere else" correction, the label
divergence, the `Severity` ordinal note); `TestCaseSummaryView` + `TestCaseDetailView` (component
type, javadoc); `CaseSummaries.signalsOf`; `ContractConventionTest.RESPONSE_RECORDS` (+1);
`ContractEnumTest.OWNERS` (+1); `CaseListEndpointTest` (a stale claim corrected — the fixture never
runs the recompute, which is not "nothing computes it yet"); `RaisedSignalTest` (new, 9 tests);
`packages/mcp/src/reads.ts` + `reads.test.ts` (+1).

**Phase 2** — `api/types.ts` (`HealthSignalSeverity`, `RaisedSignal`); `components/StatusChip.tsx`
(`SEVERE_SIGNALS` deleted, `severityColors` added, `HealthChip` takes both);
`features/cases/CaseGrid.tsx` (the pair, the labelled tooltip, the do-not-sort note);
`features/cases/CaseDetailPage.tsx`; `components/HealthChip.test.tsx` (rewritten premise, 6 tests).

## Gates

**Run on `shanks`** per `CLAUDE.md`'s remote-gate section.

Backend `./gradlew build` on `shanks` **SUCCESSFUL in 7m11s — 2573 tests across 521 result classes, 0
failures, 0 errors** (+8 tests, **+2** result classes: `RaisedSignalTest$Severities` and
`RaisedSignalTest$Order` report separately, which is the same arithmetic §157's `StaleApproval` group
had). Frontend `npm run test:run` on `shanks` **1262 passed / 1262 across 96 files in 187.3 s** (+2
tests, no new file — `HealthChip.test.tsx` went from 4 to 6, and §129's timeout cliff did not recur).
`npm run build` and `npm run lint` clean locally. `packages/` built in order (client → cli → mcp),
lint clean, **230** tests (+1, in `mcp/src/reads.test.ts`).

Browser rows `§179 RS-1`…`§179 RS-4` and mutations `§179 RS-M1`…`§179 RS-M6` **recorded and not run**
(§65). **Built and unverified.**

## The next slice

§178's list, minus the entry this one took, plus the one this one found and measured.

- **`HealthSignal.label()` on the wire versus `lib/labels.ts`** — six of the ten disagree, the table is
  above, and `CaseService.facets` already argues which side should win. It is bigger than it looks:
  `DashboardTile.label` is generic over tile kinds, so closing it takes `LifecycleStatus.label()` and
  the lifecycle tiles with it, and the SPA must be able to label a tile from its `key`. The finding
  that makes it worth doing is that the two vocabularies meet on **one navigation** — a tile's own
  drill-down;
- **`ORPHANED`, the last writerless health signal** — needs a decision before a statement: what "the
  owner left the project" means when a grant is revoked, a membership is deactivated, or a team is
  dissolved. It is also the column `Boundaries.otherSignalsAreNotClobbered` uses, so that slice pays
  for a new assertion;
- **`CaseHealth.incomplete`** — writerless *and* readerless, and `ORPHANED`'s shape;
- **quietening `INFO`** — the reading-plan decision this slice deliberately did not take, now that
  `UNLINKED` puts an amber chip on most rows;
- the **read-only-archive** decision §159 did not take;
- **serving the relabel audit** — §174's recorded absence;
- **`CurrentUser.teams` has no reader** — §175 kept the array on a permission argument;
- **a run has no edit endpoint at all** — §177 leaned on that twice and did not build it;
- **run the browser queue** — ~719 rows, thirteen lanes, one sitting. At the **bottom** at the user's
  direction (2026-08-12).
