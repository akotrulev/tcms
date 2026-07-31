# §35. Backend M1, slice 12 + frontend — the case's version history, and the gap it makes legible (2026-07-31)

Run through the **`be-java`** skill (agent definition + `database-code`), **Mode B — extend**, and
the **`fe-react`** skill for the second half. Lane B of a three-lane parallel run; the backend
surface is confined to the `repository` module and **no Flyway version was taken**.

`GET /projects/{projectId}/cases/{caseId}/versions`, and the panel that reads it. Both halves in one
slice, deliberately — see below.

### `useCaseVersions` was seven slices old and had never been called

§27 named `/versions` next. §28 checked and found the hook dead, and shipped the first write instead.
§29 checked again, found it still dead, and shipped create instead. §29's own list then named it
again with the qualification that it "wants the detail page's history panel in the same slice, or it
is another dead hook".

That qualification is the whole shape of this slice. The endpoint on its own would have been the
fourth consecutive appearance of `/versions` on a "Next" list and the fourth time nothing called it.
**A read endpoint whose consumer is a different slice is a read endpoint nobody has ever run
against real data** — the version panel is what turned three of this slice's decisions from
plausible to checkable, and the mock defect below was only findable by rendering it.

| Path | Purpose |
|---|---|
| `repository/EntityVersionView` | The contract's `EntityVersion` — **without the snapshot**. |
| `repository/CaseVersionService` | The two gates, and the 200-vs-404 distinction. |
| `repository/TestCaseVersionRepository.findForCase` | `ORDER BY version_no DESC`, unpaged, no new index. |
| `repository/CaseController.versions` | The mapping. |
| `repository/CaseVersionEndpointTest` | **New, 23 tests** (prefix `b3…`). |
| `api/queries/cases.ts` — `CaseVersion` | The hand-widened type, and the contract divergence. |
| `features/cases/CaseVersionPanel.tsx` | The panel. |
| `mocks/db/{store,cases}.ts` · `mocks/handlers/cases.ts` · `mocks/fixtures/cases.ts` | The mock stops deriving history from a counter. |

### An empty **list** is a 200; an empty **Optional** is a 404 — and most of an installation is the first

Every case authored before §28's write path has **no version rows at all**, which is an ordinary
state of a perfectly readable case rather than a missing resource. Collapsing the two — the obvious
`filter(list -> !list.isEmpty())` — would 404 the panel on most of the cases in any installation
being upgraded, and a 404 on a detail screen reads as *this case has been deleted*. The mutation that
adds that filter is killed by exactly one test, which is the point of having written it.

The panel's empty state says **why** it is empty ("This case has not been edited since version
history was recorded. Its next save will be its first recorded version"), because "nothing here" and
"history has not started" are different facts and only one of them is reassuring.

### The v1 gap is served, not filled — and the panel is what makes it a fact rather than a bug

`CaseVersions` already refuses to backfill a synthetic v1 (§28: it would assert that the pre-edit
content was the post-edit content, in the one table whose purpose is that past claims stay true).
The reader must not undo that, so it synthesizes nothing.

But a reader looking at a history that starts at v2 has no way to tell that apart from a defect. So
the panel says it: **"v2 is the earliest recorded version. This case is older than its history —
what it said before then was not kept."** Rendered only when the oldest row is not v1, so a case
created through `POST` (§29, gapless by construction) never shows it. The two populations stay
distinguishable by the one fact that matters — whether a v1 exists — rather than by a flag.

### Two contract divergences, both found by reading rather than by failing — and **neither is fixed here**

Lane B does not hold the contract this run. Both are reported rather than edited, per the lane rules.

1. **`EntityVersion.createdBy` is `required` and non-nullable, and the data cannot honour it.**

   ```yaml
   EntityVersion:
     type: object
     required: [versionNo, createdBy, createdAt, pinned]
     properties:
       versionNo: { type: integer }
       createdBy: { $ref: '#/components/schemas/UserRef' }
   ```

   `test_case_version.created_by` is `UUID` — **nullable, and with no foreign key to `app_user`**
   (§28, finding 1: 23 `created_by` columns, zero references). So both *nobody was recorded* and
   *the recorded id names no account* are states a real row is in, and there is no third answer that
   is not a lie about who changed a test. The server serves `null`.

   **The fix is the one `TestCaseSummary.owner` already carries** for exactly this reason — stay
   `required`, add `nullable: true` via the `allOf` wrapper:

   ```yaml
       createdBy:
         allOf: [{ $ref: '#/components/schemas/UserRef' }]
         nullable: true
   ```

   Until then the SPA carries `CaseVersion` in `queries/cases.ts` — `Omit<EntityVersion,'createdBy'>
   & { createdBy: UserRef | null }` — with the divergence in its javadoc and a note to delete the
   alias when the contract lands. Same workaround, same reason, as `CaseHistoryResponse` (§28,
   finding 2).

2. **The path declares only a 200.** No `404`, though an unreachable project, a case in a sibling
   project, a deleted case and an absent one all answer one — and must, or the endpoint becomes the
   inference channel §6t exists to close. Same omission §29 found on the `POST` (which declared only
   201) and fixed in the contract; this one is left for the holder. `403` is correctly absent: this
   is a read and takes the read gate.

### The gate is the read gate, and that is a decision rather than an omission

`ProjectService.readVisible` — §23's shared gate, asking `project:read`, held by all six built-in
roles — plus `TestCaseRepository.findLiveInProject` for the second path segment. Nothing in
`CaseVersionService` asks what a caller holds.

That is right **because this response is a strict subset of what the case detail already discloses
to the same caller**. The tempting hardening is to put it behind `case:edit` on the grounds that
history is "editorial"; that would hide the audit trail from the Viewer, who is the client's own
stakeholder invited to read coverage (roles spec §A7), and nothing else in the suite would notice.
`Gates.aViewerMayRead` is the assertion that goes red.

The second gate matters more here than it looks. Without `findLiveInProject`'s `projectId`
predicate, `/projects/{granted}/cases/{elsewhere-in-the-company}/versions` serves the **change
comments** — free text an author wrote about a case in a project the caller cannot open — and every
cross-company test in the suite still passes, because the row genuinely is in the caller's company.
§27's leak, one endpoint later, and the mutation is killed by one test.

### The snapshot is not on the wire

`EntityVersionView` deliberately carries no `snapshot` and no `contentHash`. The contract's shape is
a list entry, and a list that shipped the snapshot would send the full text of every version of a
case to render a five-line panel — fifty copies of a case edited fifty times. The snapshot is what a
diff view reads, one version at a time, through an endpoint that does not exist yet. Pinned by a test
that asserts the fixture's snapshot text appears nowhere in the response.

### Mutation-verified — six mutations, no survivors, and one was informative

| Mutation | Result |
|---|---|
| `ORDER BY versionNo DESC` dropped | **Killed** — and by *nine* assertions, because the unsorted plan really does return oldest-first |
| Empty list collapsed into a 404 | **Killed**, by exactly one test |
| `findLiveInProject` → `findById` (project predicate dropped) | **Killed** — sibling-project leak *and* the deleted case |
| `pinned` hard-coded `false` | **Killed** — nothing writes an execution yet, so every other test passes |
| The read gate skipped entirely | **Killed**, only by the no-grant persona |
| The null check on `createdBy` removed | **Killed — and it exposed a hazard I had mis-documented** |

**The last one was not the defensive nicety its comment claimed.** `CaseOwners.resolve` returns
`Map.of()` when there is nothing to resolve, and **`Map.of().get(null)` throws `NPE` by contract** —
so a case whose every version is unattributed (an imported one, the commonest kind in a migrated
installation) would 500 the whole panel rather than render one wrong name. It is killed by the
**tenancy** test, of all things, because that fixture's Umbra versions happen to have no authors.
The comment now records the real failure mode instead of the imagined one.

### The mock was deriving history from a counter, and that hid two things at once

The old handler built the list from `currentVersionNo` on every request: `Array.from({length: total})`,
newest to v1, author `userRef(0)`, one hard-coded date, `pinned` for versions 3–7.

Two properties fell out of that, both invisible until something rendered them:

- **A gapless 1..N no server can produce.** Mock mode would have agreed with itself and disagreed
  with live mode, and the "earliest recorded" line would have been unreachable until it met a
  customer's imported data. Exactly §27's condition-order defect wearing different clothes.
- **No save could ever appear in its own history.** A version list that is a function of a counter
  cannot carry the comment its author just typed, so the one round-trip a stateful mock exists to
  prove — edit, then see your change — was unprovable.

Now `db.caseVersions` holds it, seeded to **start at v2** for the fixture population, and both
writers append: `PATCH` records the version it wrote with the payload's `changeComment`, `POST`
records **v1** (mirroring `CaseVersions.initial`, or a created case would show an empty history in
mock mode and v1 in live mode). `TC-12`'s fixture drops to `currentVersionNo: 1` so the genuinely
empty history has somewhere to be rendered.

**One trap in the writer, recorded because it is silent:** `recordCaseVersion` materializes the
existing history *before* prepending. Writing straight to `db.caseVersions` would find nothing for a
case whose panel nobody had opened, seed the map with only the new row, and permanently lose every
earlier version — the lazy seeding only ever runs on an absent key. The symptom is a case that had
six versions showing one, immediately after a save, which reads as the save having destroyed the
history.

### Gate evidence — run, not just built

`./gradlew build` clean with `-Werror`, **602 tests** (579 + 23), 0 failures, 0 skipped, against
Testcontainers Postgres 17 in this worktree's own `backend/build/`. `npm run build` clean,
`npm run lint` 0 warnings.

Then **driven in a real browser** (headless Chrome over CDP), mock mode on the lane-B stack (`:6173`):

| Assertion | Result |
|---|---|
| TC-5 — a long history | v7…v2, newest first; v7 marked **Current**; v3 **Pinned**; v2 **Unknown author** |
| The v1 gap | "v2 is the earliest recorded version…" on TC-5 and TC-1 |
| TC-1 — a short history | v3 (Current, Pinned), v2 (Unknown author) |
| TC-12 — never edited | the **empty state**, not an error and not a blank panel |
| Comments | v6/v5's real text rendered; a version with none reads "No comment", not a blank line |
| **Edit through the real editor** | title + change comment → Save → **v4 at the top**, "Reworded the title so it matches the spec", "just now", **Current moved off v3** while v3 kept **Pinned** |
| A created case | `201`, `currentVersionNo 1`, versions `["v1 by Ana Petrova \"Created\""]` — **no gap** |
| `tcmsFaults.status(503,2)` scoped to `/versions` | the panel fails **in place** — "Couldn't load this", a **Try again**, and a correlation reference — while the case, its steps and the rest of the sidebar keep rendering |
| Fault cleared, **Try again** pressed | recovers in place to two rows |
| Console | clean, except the **pre-existing** `BrowserRouter`/`DataGridRaw` setState-in-render warning §29 already recorded, and the expected client-error report for the injected fault |

`useUpdateCase` has invalidated `keys.caseVersions` since §28 — written when nothing consumed it,
and correct the first time it was exercised.

### Deliberately not done

- **No live pass.** The lane-B compose stack was not started; every browser assertion above is mock
  mode. The backend half is covered by 23 integration tests over HTTP against real Postgres under
  RLS, including both cross-company directions, and that is what is claimed — not a curl transcript.
- **`openapi.yaml` untouched, and `npm run gen:api` not run.** Lane A holds the contract; the two
  divergences above are handed over.
- **No Flyway migration, and none was needed.** `uq_case_version (case_id, version_no)` is a b-tree
  whose leading column is this query's seek key and whose second is its sort key, so the list reads
  one contiguous range backwards. V9 and V10 each added an index because their tables had nothing to
  seek on at all; this one already has the index the access path wants, and a second would be a
  duplicate maintained on every write.
- **No paging.** The list is bounded by how many times a human has saved one case, and §6j's
  retention bounds it from above. A page cursor would also make "v2 is the earliest recorded"
  unanswerable without a second request. The panel scrolls at 320px instead.
- **No diff view, and therefore no `snapshot` reader.** Two versions selected and compared is its own
  slice, and it is the only thing `snapshot` is for — which is why nothing reads it yet.
- **`pinned` is served and never written.** Nothing writes `test_execution`, so it is `false` on
  every row the application creates. The panel renders the marker because the column and the
  contract both allow `true`, and one seeded mock row exercises it.
- **`GET …/cases/{caseId}/history`** — still the `execution` module's, still 404 live. The panel is
  deliberately *not* it: that one answers which runs executed which version and keeps its own screen.
- **`GET …/suites/{suiteId}/history`** — `suite_version` has a writer (§30) and `useSuiteHistory` has
  a consumer, so it is the same slice one entity over. Left for whoever owns `repository` next.
- **No `EXPLAIN` against a running database.** The index claim above is read off `V1`'s constraint
  rather than measured, unlike §27's, because no compose stack was started in this lane.

### Next

1. **`GET /projects/{projectId}/suites/{suiteId}/history`** — the same shape one entity up, with a
   live consumer already on the suite tree. Re-derive it against the code first, as always.
2. **The two contract divergences above**, whenever the contract holder next has it.
3. **The diff view** — what `snapshot` exists for, and the thing that makes a version list more than
   a log of who saved.
4. **The `execution` module**, which unblocks `/history` and turns `pinned` into something that means
   anything.
