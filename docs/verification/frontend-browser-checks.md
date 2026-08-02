# Deferred browser checks — the queue

**Policy set 2026-08-02 (§65).** Driving each slice in a real browser through the Chrome plugin is
slow — a live pass is many minutes of clicking per slice, and it was being paid *during* feature
work. It is now **deferred and batched**: a slice records what must be driven and what must be
observed, and this file is executed as one session later.

**This reverses a standing rule, and the reversal is the risk.** `CLAUDE.md` says *"the gates prove
it compiles, not that it works"*, and the handoff's evidence is overwhelming: the live pass has
found a defect no gate could see in **most** slices since §42 — §62's unselectable APPROVED option,
§57's permanent loading skeleton, §53's card with no reason on it, §52's clipped tables, §64's
contradictory error messages. Deferring does not make those defects less likely; it makes them
**arrive later and in a batch**, which is the trade being accepted deliberately to ship the
remaining functionality first while requirements are still moving.

**So the entries below are not optional and they are not a wishlist.** Each one is a defect that has
not yet been looked for. Until this file is run, a slice listed here is *built and unverified*, and
should be described that way rather than as done.

## How to run this file

Per `CLAUDE.md`, live mode is the one that counts — mock mode agrees with itself (§64, §54, §52 each
found a defect that existed only because the mock and the SPA shared one wrong assumption).

```bash
docker compose up --build          # REBUILD — §63's trap: a running image predates the change,
                                   # so a stale container answers with the old rules
cd frontend && npm run dev         # with VITE_API_MODE=live in .env.local
                                   # then: rm -rf node_modules/.vite after changing .env.local
```

Then per row: drive the flow, check the observation, and check `window.__tcmsErrors` is empty at the
end of each. **Record what was found in the slice's handoff section**, not here — this file is the
queue, not the findings.

---

## §65 — permission qualifiers on the wire

**What shipped unverified:** `/me/permissions` now carries a qualifier per held key, and
`ArtifactDialogs` uses it to disable the PROJECT-scope choice for a caller whose `artifact:edit` is
`PLAN_SCOPE`.

| # | Drive | Must observe |
|---|---|---|
| 1 | Sign in as a **Test Engineer**, open a project's artifact register, click New | The **"Belongs to" PROJECT option is disabled** with a reason naming the restriction — not enabled-then-refused, which is what §61 shipped |
| 2 | Same caller, choose PLAN scope, fill the form, submit | **201**, the row appears in the plan's register |
| 3 | Sign in as a **Test Lead**, same dialog | PROJECT scope is **enabled and selectable**, and submitting lands a project-scope row. Without this, #1 passes equally well against a dialog that is broken for everyone |
| 4 | Sign in as a **Viewer**, same register | The New button itself is **disabled** (they hold no `artifact:edit` at all) — the qualifier path must not have replaced the plain permission gate |
| 5 | Network tab on any of the above | `GET /me/permissions` returns `permissions: [{key, qualifier}, …]`, **sorted by key**, with no entry whose qualifier is `NONE` |
| 6 | Any authenticated screen — the case grid, the board, the dashboard | **No regression from the response shape change.** Every `useCan` consumer reads the new shape; a missed one fails *open* (`useCan` falls open on error), so it shows as a control that is enabled when it should not be, not as an error |

**Highest-risk row is #6.** The wire shape changed under eleven gated affordances at once, and the
fall-open policy means a consumer left on the old shape looks **normal** rather than broken.

**Also still owed from §61, found and not fixed:** the artifact register's plan filter is `useState`
rather than the URL, so a plan's register **cannot be linked to** while every other filtered screen
can. Not a §65 regression — an older gap that this queue is now the place to track.

---

## §66 — the `OWN` qualifier reaches the row

**What shipped unverified:** `TestCaseSummary.createdBy` is on the wire, and the case detail's
Delete button is gated on it — a Test Engineer sees Delete enabled on cases they wrote and disabled,
with the reason on it, on everyone else's.

**Fixture needed:** at least two live cases in one project with **different** `created_by` values,
one of them the signed-in Test Engineer, plus one row with `created_by IS NULL`. The seeded data has
no such split, so set it directly:

```sql
-- as tcms_owner, with app.current_company set
UPDATE test_case SET created_by = '<engineer-uuid>' WHERE key = 'TC-1';
UPDATE test_case SET created_by = '<lead-uuid>'     WHERE key = 'TC-2';
UPDATE test_case SET created_by = NULL              WHERE key = 'TC-3';
```

| # | Drive | Must observe |
|---|---|---|
| 1 | Sign in as a **Test Engineer**, open TC-1 (theirs) | Delete is **enabled**, and deleting it works — 204, the case leaves the list |
| 2 | Same caller, open TC-2 (the lead's) | Delete is **disabled**, and hovering it shows *"You can only delete test cases you created."* — not "you don't have permission", which is false for this caller |
| 3 | Same caller, open TC-3 (`created_by IS NULL`) | Delete is **disabled**. This is the arm most likely to be wrong and it is invisible in the seeded data — an installation's oldest cases all look like this |
| 4 | Sign in as a **Test Lead**, open TC-2 and TC-3 | Delete is **enabled on both**. Without this, #2 and #3 pass equally well against a page that disables Delete for everybody, which would lock out every lead in the product |
| 5 | Sign in as a **Viewer**, open any case | Delete is disabled with the *ordinary* sentence (*"You don't have permission to delete test cases in this project."*) — the authorship path must not have replaced the plain permission gate |
| 6 | As the Test Engineer, **create** a case, then open it | Delete is **enabled** — `created_by` is set to the creator on the server, so a case you just wrote is always one you can remove |
| 7 | The case list and the case detail, network tab | `createdBy` is present on every row, `null` where the column is, and **is not the same person as `owner`** on the rows seeded above. If the two always agree, the fixture did not take and rows #1–#4 prove nothing |
| 8 | `psql` after #1 | `SELECT deleted_at FROM test_case WHERE key='TC-1'` is set — the delete is soft, as `CaseDeleteDialog`'s copy claims |

**Highest-risk rows are #3 and #4.** #3 because the null-author arm exists only in code and in one
unit test, and the rows it governs are the ones no fixture happens to contain. #4 because every
"disabled" observation above is also what a *broken-for-everyone* gate produces — and `useCan` is
fail-closed while pending, which is exactly how this slice's own component test passed against a
page with the wiring deleted.

---

## §67 — evidence attach and delete

**What shipped unverified:** the execution screen can attach a screenshot or log to a result, render
it inline, download it, and remove it — with the delete gated on `evidence:delete`'s `OWN`
qualifier. `evidence` and `attachment`, both in `V1` since the beginning, have their first writer.

**Fixture needed.** Mock mode already seeds three evidence rows on every FAILED execution — one
authored by the caller, one by a colleague, one with `created_by IS NULL` — so **mock mode needs no
setup**. Live mode does, because nothing the API offers can produce the unauthored row:

```sql
-- as tcms_owner, with app.current_company set
UPDATE evidence SET created_by = NULL WHERE id = '<the third row on the failed execution>';
```

| # | Drive | Must observe |
|---|---|---|
| 1 | As a **Test Engineer**, open a failed execution, Attach a PNG | 201, the row appears with a **rendered thumbnail** (not a broken image), the correct size and your name |
| 2 | Click the filename | The image opens **inline** in a new tab — served from `/evidence/{id}/content`, not downloaded as a file |
| 3 | Same caller, the row **they** attached | Delete is **enabled**, and removing it takes the row away |
| 4 | Same caller, the **colleague's** row | Delete is **disabled**, tooltip reads *"You can only remove evidence you attached."* — not "you don't have permission", which is false for this caller |
| 5 | Same caller, the **null-author** row | Delete is **disabled**. The arm exists only in code and one unit test, and governs the oldest rows in a real installation |
| 6 | Sign in as a **Test Lead**, same three rows | Delete is **enabled on all three**. Without this, #4 and #5 pass equally well against a panel broken for everybody |
| 7 | Sign in as a **Viewer** | All three rows are **visible**; Attach and every Delete are disabled with the ordinary sentence. Disable-don't-hide (§31) — evidence is part of the record of what happened |
| 8 | Attach a file **over 10 MB** | Refused **client-side** with a sentence naming the limit, before any upload happens |
| 9 | Live mode only: temporarily set `max-file-size: 1MB`, attach a 2 MB file | **413** with readable copy (*"Evidence files are limited to…"*), never a bare 500. The container refuses this before the handler runs, so it exercises a completely different path from #8 |
| 10 | Record a result on a step, then attach with that step selected | The file appears **on that step** and the execution-level list stays empty |
| 11 | Network tab, on the attach | Request is `multipart/form-data` with a **boundary**; no hand-set `Content-Type`. A lost boundary is a 400 that looks correct in devtools |
| 12 | `psql` after #3 | The `evidence` **and** `attachment` rows are gone, and the file is gone from `TCMS_EVIDENCE_ROOT`. A hard delete, not a soft one — this is the one thing a customer may need removed for privacy |
| 13 | Attach the **same file twice in a row** | The second attach works. The file input is cleared on change precisely so re-picking an identical file re-fires — the first thing anyone tries after a failed upload |

**Highest-risk rows are #5, #6 and #12.** #5 and #6 for §66's reason: every "disabled" observation is
also what a broken-for-everyone gate produces, and `useCan` is fail-closed while pending. #12
because the delete crosses out of Postgres into a directory, and a failure between the two leaves a
row whose blob is gone (or worse in the other order) — the one place in the product where a partial
write is not rolled back by anything.

**Mock-vs-live divergence, stated up front:** in mock mode `url` is a `blob:`/`data:` URI, because
there is no server to serve `/content`. Rows #2 and #11 are therefore **live-mode only** — mock mode
cannot show them and will look fine either way.

---

## §68 — the classification picker

**Setup (live mode).** The seeded Feature tree is **empty** — `tcms_seed_project_defaults` creates
the dimension and no values — so rows 5–9 need a tree first:

```sql
-- as tcms_owner, with app.current_company set to the company under test
INSERT INTO classification_value (id, company_id, dimension_id, parent_id, path, name, position)
SELECT gen_random_uuid(), :company, d.id, NULL, 'payments'::ltree, 'Payments', 0
  FROM classification_dimension d
 WHERE d.project_id = :project AND d.key = 'feature';
-- then a child of it, and a THIRD value which is then retired:
UPDATE classification_value SET deleted_at = now() WHERE name = 'Cheques';
```

| # | Drive | Must observe |
|---|---|---|
| 1 | Open any case in the editor | The Classification panel shows **four** controls — Test Type, Test Level, Feature, Execution Phase — with the case's current values already selected |
| 2 | Change Test Type, save | Toast, and the case detail shows the **new** Test Type. The version list gains **one** entry |
| 3 | Save again without touching anything | **No new version.** Reorder-churn is the specific risk: the ids go over the wire as an array |
| 4 | Set Test Type to **None**, save | The detail shows no Test Type, and `psql` shows the `case_classification` row **gone** |
| 5 | Tick a **parent** Feature (Payments), save | Accepted. Two milestones of editor comments claimed a parent could not be assigned |
| 6 | Tick a **child** as well, save | Both are stored; `features` on the grid shows both names |
| 7 | Untick one, save | Only that one goes; the other survives |
| 8 | On a case carrying the **retired** value (assign it *before* retiring), open the editor and save any other change | The retired value has **no checkbox** — and `psql` shows its `case_classification` row **still there** afterwards |
| 9 | Reload that case's editor and save again | Still there. The bound is not a one-shot |
| 10 | A project whose Feature dimension has **no values** | Reads *"Nothing to choose from yet — this vocabulary is empty"*, not an empty box or a spinner |
| 11 | Create a **new** case with a Test Type and two Features | Both land on the created case; its v1 snapshot carries them |
| 12 | Sign in as a **Viewer**, open a case | The editor is unreachable as before — this is only to confirm nothing about the panel bypasses the existing gate |
| 13 | Network tab on any save | `classificationValueIds` is present on **every** save, including when empty |

**Highest-risk rows are #8, #9 and #3.**

#8/#9 are the slice's central decision and the only failure that is **silent**: if the bound is
wrong, the save succeeds, the response looks correct, and a classification is gone with nothing on
any screen having mentioned it. Neither the API nor the UI can produce the state they test — a
retired value must be assigned before it is retired — so this is the arm most likely to be wrong,
for §67's null-author reason.

#3 because the reorder property is invisible: a spurious version looks exactly like a real one, and
the version list only becomes obviously useless after it has accumulated a few dozen.

**Mock-vs-live divergence, stated up front:** the mock has **no retired values** — its `dimensions`
fixture is all live — so **rows 8, 9 and 10 are live-mode only** and mock mode will look correct
either way. Mock mode also has one project's vocabulary, so the sibling-project refusal has no mock
equivalent at all; it is covered by the backend suite and by row 5's live analogue only if a second
project's Feature value id is pasted into the request by hand.

---

## §69 — the classification vocabulary editor

**Route:** `/projects/:projectId/settings/classification`, reached from **Classification** in the
nav rail's Project group.

**Personas needed: two, and the whole point of the screen is the difference between them.** A Test
Lead (holds `project:settings` on the project, not `company:vocabulary`) and a Company Admin (holds
both). In mock mode the seeded persona is the first, so the Test Lead column is drivable without
touching a fixture and the Company Admin column is **live-mode only**.

| # | Do | Expect |
|---|---|---|
| 1 | Open the page as the **Test Lead** | Four panels — Feature, Test Type, Test Level, Execution Phase — each with a scope chip reading *This project* or *Company-wide* |
| 2 | Look at the Feature panel | **Add value** enabled; every row's rename and retire enabled |
| 3 | Look at the Test Type panel | **Add value disabled, with a tooltip explaining why.** Not hidden, not missing — §31. This is the headline and the one thing no gate can see |
| 4 | Add a root Feature "Lending" | Appears in the panel; the case editor's Feature picker offers it on the next open |
| 5 | Add "Mortgages" under it via the row's **+** | Renders indented under Lending. `psql`: its `path` is the parent's path plus a `v…` label, and the label is **not** derived from the name |
| 6 | Add a Feature named **支付** (or any name with no ASCII letters) | Accepted, and the row reads 支付. A name-derived path fails this insert outright |
| 7 | Add a second "Mortgages" under Lending | Refused with *"Another value here already has that name"* **on the name field**, not a toast |
| 8 | Add "Mortgages" under a **different** parent | Accepted — two leaves under different parents may share a name (§68) |
| 9 | Rename "Lending" to "Lending & Cards" | Saved. `psql`: `path` is **byte-identical** to before. This is the payoff of the id-derived label and it is invisible in the UI |
| 10 | Retire "Lending & Cards" while Mortgages is under it | **409**, dialog stays open, reads *"2 values sit under this one"* (or 1) — not a toast over a closed dialog |
| 11 | Retire the children, then the parent | 204 each; the rows leave the panel |
| 12 | Reload | The retired values are gone and stay gone; the renamed one keeps its new name |
| 13 | Open a case classified with a value you just retired | The value is **not** in the picker, and the case's stored classification is **still there** in `psql` — §68's bound, from the retiring end |
| 14 | Sign in as the **Company Admin** (live only) | Every panel's controls are enabled, including Test Type's |
| 15 | Add a Test Type value as the admin, then check a **second project** in the same company | The new value is offered there too — that is what "company-wide" means, and it is the reason the permission is separate |
| 16 | As the Test Lead again, POST to `/classification/values` by hand with the **sibling project's** Feature dimension id | **404**, not 403 — the caller must not learn which permission would have applied |
| 17 | A colour: add a value with `#b7791f`, then one with `red` | The first shows a colour dot; the second is refused on the **colour** field |
| 18 | Console throughout | 0 client errors |

**Highest-risk rows are #3, #9 and #13.**

#3 because a page that asked `project:settings` for every dimension renders *identically* for a
Company Admin, passes every Feature assertion, and hands a Test Lead a button the server answers
403 to. The component test pins it, and the component test mocks both permission endpoints — the
live pass is what proves the real `/me/permissions` answers the company question at all on this
screen, which no other screen asks except the project picker.

#9 because nothing in the UI shows a path. If a rename rewrote it, the only symptom would appear
later, in §6q's descendant matching, which is not built yet — so the check has to be made in `psql`
now or not at all.

#13 because it is the same silent-loss shape §68's queue names, approached from the other side: the
retire is what *creates* the state whose reconcile bound §68 built.

**Mock-vs-live divergence, stated up front.** Mock mode has one project and one persona, so rows
14, 15 and 16 have no mock equivalent. Row 5's `psql` half and row 9's are live-only by nature. The
mock does enforce every field rule, the 409 and both gates, so rows 1–4, 6–8, 10–12 and 17 are
drivable against `npm run dev` with no backend.

---

## §70 — the verb the contract checks could not see

**Nothing to drive, and the row exists to say so rather than to leave the slice absent.**

§70 changed `src/api/contractIntrospection.ts`, `contractOperations.test.ts` and
`apiResponseTypes.test.ts` and nothing else. The first is imported **only by the two test files**
(`grep -rln contractIntrospection src/` returns exactly those two), so it is not in any bundle: the
`npm run build` output is byte-for-byte the app §69 shipped. There is no screen, no request and no
rendered string that differs, so any browser row here would be a check of §69's screens wearing
§70's number.

**What stands in for it** is the pair of mutations, and because this slice *is* a check they were
run inline rather than queued — see §70's table: the unmapped client spelling reddens exactly 8, and
an unreadable verb reddens exactly 2 (one guard per file).

**The one thing a later reader might want to re-derive** — cheaper as a command than as a browser
pass, and it is the whole slice in one line:

```bash
cd frontend && grep -rn "api\.delete" src/     # must return NOTHING — the client's verb is `del`
npx vitest run src/api/contractOperations.test.ts src/api/apiResponseTypes.test.ts
                                               # must report 8 DELETE call sites and 1 upload among them
```

---

## §71 — the org chart becomes editable

**The live-mode-only rows come first, because this slice's whole point is a live-mode gap.** Mock
mode has shown three teams since Phase 1 and live mode has shown **none, ever** — `team` had no
writer, so `GET /teams` answered `[]` on every real instance. So the rows that matter most are the
ones mock mode structurally cannot show, and rows 1–3 must be driven with `VITE_API_MODE=live`
against a provisioned company or they prove nothing.

**The mock persona cannot drive the happy path at all.** `companyScopePermissions` is
`['company:read']`, so every org-chart control renders *disabled with its reason* in mock mode —
which is row 4, and is worth driving as-is. To reach rows 5–11 in mock mode, add `'company:teams'`
to that array in `src/mocks/fixtures/core.ts` (the file says so at the point of the decision).

| # | Flow | What must be true | Mode |
|---|---|---|---|
| 1 | Sign in as a Company Admin on a **freshly provisioned** company → Teams & roles | "Team structure" is **empty**, with the no-records copy. This is the state every real company has always been in. | **live only** |
| 2 | New team → "Platform" → Create | The team appears in the tree. Then `psql`: `SELECT id, name, parent_team_id FROM team` shows **exactly one row**, `company_id` the caller's. | **live only** |
| 3 | Grant a role → subject **Platform (team)** → Test Engineer on a project → then Members → tick a colleague | The colleague's own session (second browser / private window) now lists that project on `/projects`, **without signing out and in**. Then untick → the project disappears from their rail on the next navigation. **This is the slice**: the team-grant path has existed since M0 and could never be exercised, because no team could be created for a grant to name. | **live only** |
| 4 | Sign in as the mock persona (or any caller without `company:teams`) → Teams & roles | "New team" and all three row actions are **disabled with a tooltip giving the reason** — not hidden, not absent (§31). "Invite a person" beside them stays **enabled**, which is what shows the two permissions are asked apart. | mock + live |
| 5 | New team → leave the name blank → Create | Field error on the name input, dialog stays open. | mock + live |
| 6 | Create "QA", then create "qa" | Refused with a field error naming **the stored spelling** — `already has a team called "QA"`. The case-folding is the point; an exact-match check accepts this. | mock + live |
| 7 | Create A, create B under A, then Edit A → "Sits under" | B is **not offered** in the select. Then edit A and pick a legal parent → saves. | mock + live |
| 8 | Edit a nested team → "Sits under" → "No parent — a top-level team" → Save | The team moves to the root of the tree. **The PUT-not-PATCH decision is this row**: under PATCH this operation is unreachable. | mock + live |
| 9 | Grant a role to a team, then Delete that team | **409**, and the dialog lists *"1 role grant is made through this team…"*. Cancel, and the grant is still in the Role assignments table. | mock + live |
| 10 | Delete a team that has children | 409 naming the child count. Delete the child first, then the parent → 204 and both are gone from the tree. | mock + live |
| 11 | Members dialog on a team that holds a grant | The **warning banner** names the projects and says anyone added gets them immediately. Tick a person → the row's checkbox stays ticked after the dialog is closed and reopened (the list refetches from the mutation's invalidation, not from a snapshot). | mock + live |
| 12 | Members dialog → tick "Leads" for somebody **not** in the team | The Leads checkbox is **disabled** with a tooltip until they are a member. Ticking "In team" enables it. | mock + live |
| 13 | Members dialog → tick a person, close, reopen, untick them | 204 both times; no duplicate row, and `psql` shows `team_membership` gained one row and lost it. | **live only** |

**The two rows no browser can produce, recorded so nobody goes looking for them:**

- **An orphaned team** (`parent_team_id` naming a team that is not in the list) and **a cycle**.
  The API refuses both, twice over, so they can only be made in `psql` — and the tree now surfaces
  them at the root rather than dropping them, which is `TeamsPage.test.tsx`'s job instead. It
  matters because the tree is the **only** place a team can be edited or deleted, so a row it drops
  is a row nobody can fix. Driving it needs
  `UPDATE team SET parent_team_id = gen_random_uuid() WHERE name = 'Platform';` and is worth doing
  once on the box if the §71 live pass happens at all.
- **A sibling company's account offered as a team member.** The picker only lists this company's
  people, so the refusal it exists for is unreachable from the UI. That one is asserted at the
  server (`TeamEndpointTest`, "a SIBLING COMPANY'S user is refused — 404, and no row is written").

---

## §72 — the run lifecycle: starting, ending, reopening, and the freeze

**Built and NOT browser-verified.** The seventh slice in the deferred queue.

**The live-only rows come first, and for a sharper reason than §71's.** `test_run.status` had no
writer at all, so on every real instance **every run has always been `NOT_STARTED`** with a null
`started_at` — the state mock mode almost never shows, because its fixtures ship runs that are
already `IN_PROGRESS`. Row 1 is therefore about a state live mode has *only* ever been in and mock
mode has *never* been in, which is the exact inverse of §71's asymmetry and just as invisible.

**The mock persona can drive most of this.** `run:close` is in the mock's granted set, so rows 4–11
work in mock mode as written; row 3 needs a caller *without* it, which the frontend test covers and
a browser pass can reach by removing `'run:close'` from `src/mocks/fixtures/core.ts`.

| # | Flow | What must be true | Mode |
|---|---|---|---|
| 1 | Live: create a run → open it | Header chip reads **"Not started"** and the summary "Not started yet". Then record one result → the chip becomes **"In progress"** *without a reload of the run list*, and `psql` shows `status='IN_PROGRESS'` with `started_at` **equal to that execution's `executed_at`**. **This is the slice** — before it, that chip never changed for the life of the run. | **live only** |
| 2 | Live: the run list after row 1 | The row's status chip is coloured, not the grey fall-through. `NOT_STARTED` was **missing from the colour map** and `PLANNED` — not a run status — was in it, so every real run rendered grey. | **live only** |
| 3 | Sign in as a caller without `run:close` (a Test Engineer) → open a run | Complete and Abort are **disabled with a tooltip**, and recording a result on the same screen still works. Not hidden (§31). | mock + live |
| 4 | Open a run with no results → Complete run | The button is **disabled**, tooltip: *"Nothing has been recorded in this run yet. Abort it instead."* Abort beside it is enabled. | mock + live |
| 5 | Record one result, then Complete run | 200; chip → "Completed"; the Complete/Abort pair is **replaced by Reopen**, not joined by it. | mock + live |
| 6 | On the completed run, open an execution | The four verdict buttons are **disabled**, and the panel shows *"This run has ended, so its results can no longer change."* The Attach button and every Remove are disabled with the same reason; **the attachments themselves are still listed and still open**. | mock + live |
| 7 | Live: try the write anyway (devtools, or a second tab opened before the close) | **409** with *"This run has ended…"* — not 403, not 500. The point is that the freeze is the server's, and the disabled controls are a courtesy. | **live only** |
| 8 | Reopen run → record a result | The chip returns to "In progress", `completed_at` is **null** again in `psql`, `started_at` is **unchanged**, and the result saves. | mock + live |
| 9 | Abort a run that was never started, then Reopen it | It returns to **"Not started"**, not "In progress" — the client asked for `IN_PROGRESS` and the server normalized. `started_at` still null. | mock + live |
| 10 | Complete a run, then Complete it again | 200, no error, and `psql` shows `completed_at` **unchanged** and **one** `run.closed` audit row. | **live only** (the audit half) |
| 11 | Complete a run, then try Abort via a stale tab | 409 *"Reopen it before recording a different outcome."* | mock + live |

**The row no browser can produce:** two testers recording the last result and closing the run in the
same instant. There is no `If-Match` on the status endpoint by design (the target state is absolute),
so the race resolves to "the close wins and the result 409s" or the reverse, and both are correct.
Recorded so nobody reads its absence as an oversight.
