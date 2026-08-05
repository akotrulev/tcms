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

> **⚠ The same rule binds the sibling queue, restated 2026-08-02: NOTHING is run inline** — not a
> browser pass, and not a mutation check either, however cheap the individual one looks. See
> `backend-mutation-checks.md`'s header for the correction and why §72–§76's partial runs were a
> drift rather than the policy.

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

---

## §73 — the plan lifecycle: the ladder, and the delete that refuses

**Built and NOT browser-verified.** The eighth slice in the deferred queue.

**The live-only rows come first, for §72's reason and one more.** `test_plan.status` had no writer,
so on every real instance **every plan has always been `DRAFT`** — and the mock's fixtures carried
only `ACTIVE` and `CLOSED` plans, so *the state a fresh install produces was the one state mock mode
never rendered*. §72 wrote that lesson about runs; here it was already true of plans, three files
away. This slice adds `TP-4` (DRAFT, no runs) and `TP-5` (IN_REVIEW) to the fixtures, so rows 3–8
work in mock mode as written.

**The mock persona holds `plan:edit`, `plan:approve` and `plan:delete`**, so row 4 needs a caller
without one of them: remove `'plan:approve'` from `src/mocks/fixtures/core.ts`.

| # | Flow | What must be true | Mode |
|---|---|---|---|
| 1 | Live: create a plan → open it from the list | The list chip reads **"Draft"** in the draft colour, not a grey unlabelled fall-through. The old map was `Record<string, string>`, carried an `ARCHIVED` that is not a plan status, and was **missing `IN_REVIEW` and `APPROVED`** — the two states this slice makes reachable. | **live only** |
| 2 | Live: Submit for review → Approve → Start plan → Close plan | The header chip follows each step. `psql`: `status` moves, `closed_at` is set **only** on the close, `updated_by` is the actor, and `audit_event` holds **four** rows — `plan.submitted`, `plan.approved`, `plan.started`, `plan.closed` — each with `from` and `to`. | **live only** |
| 3 | Reopen the closed plan | The chip returns to **"Active"**, `closed_at` is **null** again, and the audit action is `plan.reopened`, not a second `plan.started`. | mock + live |
| 4 | Sign in as a caller with `plan:edit` but not `plan:approve` (Test Engineer) → open a plan in review | **Approve plan is disabled**; "Return to draft" beside it is **enabled**. Then open an *approved* plan: the same "Return to draft" button is now **disabled**. **The same control, the same target, gated differently by where the plan is** — the slice's central rule, seen from the UI. | mock + live |
| 5 | On a draft plan, press **Close plan** — there is no such control, so use devtools/curl | **409** *"Only a plan that is being executed can be closed. Start the plan, or delete it if it was never used."* The absence of the button is the point; the server refusing anyway is the proof it is not a UI convention. | **live only** |
| 6 | Draft plan (`TP-4`) → Delete → confirm | 204, the plan leaves the list, and the following read is a 404 page rather than an empty panel. | mock + live |
| 7 | A plan with runs (`TP-1`) → Delete → confirm | The dialog **stays open** and shows *"3 test runs have been executed against this plan… Close the plan instead."* Not a corner toast, and not a closed dialog: nothing on that screen could have predicted this refusal, so it is the only place the sentence can land. | mock + live |
| 8 | After row 7, open the run list | Each run still shows its `TP-n` chip and the link still opens the plan. This is what the refusal protects — and it is why the check is *"the runs are still whole"*, not *"the delete failed"*. | mock + live |
| 9 | Live: a plan with exit criteria written by hand into `exit_criteria`, then the plan **list** | The "Exit criteria" column counts only criteria whose `met` is **false**. The list never evaluates anything, so every criterion arrives with `met: null` — the old `!c.met` counted all of them as unmet and would have read "3 unmet" for a plan whose gates were green. Invisible until the column has a writer, which is the next slice. | **live only** |

**The row no browser can produce:** a lead approving while another lead returns the plan to draft in
the same instant. There is no `If-Match` on this endpoint by design (the target state is absolute),
so the race resolves to whichever lands second, and both orders are legal moves. Recorded so its
absence does not read as an oversight.

---

## §74 — the plan editor: the criteria that had no writer, and the scope that could not round-trip

**Built and NOT browser-verified.** The ninth slice in the deferred queue.

**Row 1 is live-only and it is the row that matters**, for a reason this queue has now recorded
three slices running: `entry_criteria` and `exit_criteria` have **never been written by anything**,
so on every real instance both are `NULL` and the plan page's exit-criteria panel — built in §46,
with `PlanCriteria.evaluate` behind it — has rendered an empty list for its entire life. The mock's
fixtures, by contrast, have carried hand-written criteria on `TP-1` since Phase 1. **Mock mode has
been showing a feature the product did not have.** That is §52's "mock agrees with itself" from the
opposite direction, and it is why the panel has to be seen against a real row before this is called
done.

**§73 row 9 becomes runnable here** — it needed a plan with real exit criteria and there was no way
to make one without psql. Run the two together.

| # | Flow | What must be true | Mode |
|---|---|---|---|
| 1 | Live: open a plan → **Edit** → add exit criteria *pass_rate ≥ 95* and *coverage ≥ 100* → Save → look at the statistics page | The exit-criteria panel **renders two gates with verdicts**, evaluated against the plan's real numbers — a green tick and a red cross, not two "not evaluated" dashes. `psql`: `exit_criteria` is a JSONB array, and it was `NULL` before the save. **This is the first time this panel has ever had anything to show on a real instance.** | **live only** |
| 2 | Same plan → Edit → add *open_critical = 0* → Save | That gate renders as **not evaluated** — no tick, no cross — while the two above keep their verdicts. Null means *not evaluated*, which is not *unmet*: the product has no issue tracker, and a red cross here would tell a release meeting a gate had been checked and failed. | mock + live |
| 3 | Edit → set a threshold, clear the number box entirely, Save | The row is **dropped**, not sent as `0`. A `pass_rate >= 0` gate is met by every release that ever runs and reads on screen exactly like one somebody meant. | mock + live |
| 4 | Edit a plan that has folders in scope → **do not touch the scope** → change only the name → Save | The scope checkboxes are **pre-ticked on open** (the field the detail endpoint did not serve before this slice), and after the save `psql` shows `plan_scope_item` **unchanged** and `caseCount` the same. The failure this catches is silent: an editor that sent `[]` for what it did not render would empty the plan. | mock + live |
| 5 | Edit → tick a second folder → Save → reopen the editor | The new folder is ticked, `caseCount` has grown by that folder's subtree, and **`test_plan_version` has a second row** whose `snapshot` names both suites. `psql`: `current_version_no` is 2. | **live only** |
| 6 | Edit → change **only** the name → Save → `psql` | **No new `test_plan_version` row** and `current_version_no` unchanged. A plan version is its *scope* (§6j); a name is content. This is the assertion an implementation that bumps on every save fails, and the screen looks identical either way. | **live only** |
| 7 | Open a plan, and in a second tab edit and save it. Return to the first tab and save. | **409 "Someone else saved first"** in the dialog's own alert, with *"Reload the plan…"*. Before this slice `lock_version` had no `@Version`, so it was `0` for ever and this check could not fire — **the whole point of the row is that it now can**. | mock + live |
| 8 | Close a plan (Start → Close), then look at the header | **Edit is disabled**, with the title *"Reopen the plan to edit its scope and criteria."* Then Reopen → Edit is enabled again. | mock + live |
| 9 | On the closed plan from row 8, force the save through devtools/curl | **409** *"This plan is closed… Reopen the plan to change them."* The disabled button is a courtesy; the server refusing is the rule. | **live only** |
| 10 | Sign in as a caller **without** `plan:edit` (Viewer) → open a plan | **Edit is disabled**, and a `plan:approve`-less Test Engineer still sees it **enabled** — the two keys gate different controls on the same header. | mock + live |
| 11 | Edit → open the metric dropdown | **Five options with readable labels** ("Pass rate (%)", "Open critical defects"), not raw enum spellings. The closed vocabulary is the reason a typo cannot become a gate that is silently never evaluated. | mock + live |

**The row no browser can produce:** two editors saving in the same instant *after both passing the
`If-Match` check*. That is the true race `@Version` catches at flush, mapped to the same 409 by
`PlanController#onWriteRace`, and it needs two interleaved transactions — which this suite has no
mechanism for either, so the handler is reachable in principle and unverified in practice. Recorded
so its absence does not read as an oversight.

---

## §75 — the suite lifecycle: the status nothing ever wrote, and the rescind on cases

**Built and NOT browser-verified.** The tenth slice in the deferred queue.

**The live-only rows lead, and it is §72's inversion exactly.** On every real instance every suite
is `DRAFT` and has been for the product's whole life, because `Suite.create` was the only writer
`lifecycle_status` ever had. The mock's fixtures, meanwhile, have carried `APPROVED` and `IN_REVIEW`
folders since Phase 1 — so **mock mode has been showing a signed-off suite tree the product could
not produce**, and mock mode will look unchanged by this slice while live mode changes completely.

Rows 3 and 4 are the ones that matter most: they are the client half of the defect this slice
found, and both would have looked correct before it.

| # | Flow | What must be true | Mode |
|---|---|---|---|
| 1 | Live: sign in as a lead, open a project's case list, and read the suite rail | **Every folder is unbadged**, because every folder is a draft — the state this slice's first save changes. `psql`: `SELECT DISTINCT lifecycle_status FROM suite` returns one row, `DRAFT`. Take the screenshot before doing anything else; it cannot be reproduced afterwards. | **live only** |
| 2 | Kebab on a draft folder → **Submit for review** → **Approve** | The badge appears and then changes ("IN REVIEW" → "APPROVED"), the tree does not reshuffle, and `psql` shows two `audit_event` rows — `suite.submitted` then `suite.approved` — each carrying `from` and `to`. | mock + live |
| 3 | Sign in as a **Test Engineer**, open the kebab on the folder approved in row 2 | **"Return to draft" and "Deprecate…" are greyed**, and "Execution history" is live beside them. This is the client half of the slice's defect: before §75 the same caller could take that approval back through the bulk case dialog, and nothing on screen said otherwise. | mock + live |
| 4 | Same caller: case list → select two cases, one `DRAFT` and one `APPROVED` → bulk **Set status → Draft** → commit | The draft is reported **changed** and the approved one **skipped**, with *"Only a test lead can change a case that is already approved."* — and `changed + unchanged + skipped == total`. Before §75 both moved. | mock + live |
| 5 | As a Test Engineer, kebab on a **draft** folder | **"Submit for review" is live** and "Deprecate…" is greyed. §6g: submitting needs only edit rights; retiring is the soft form of a delete. | mock + live |
| 6 | As a lead: approve a folder, then open its kebab | **"Submit for review" is not offered at all** — the moves swap rather than accumulate. Offering it would be a control the server answers 409 to. | mock + live |
| 7 | As a lead: deprecate a folder, then open its kebab | **"Restore to draft" is the only move**, and the badge is muted rather than coloured. A retired folder cannot go straight back to approved. | mock + live |
| 8 | Force the refused move through devtools/curl: `PUT …/status` with `IN_REVIEW` on an APPROVED suite | **409**, *"Only a draft suite can be submitted for review. Return this suite to draft first."* — and the same sentence must appear in the rail's own alert, prefixed with the folder's name, if it is reached through the UI at all. | **live only** |
| 9 | Press the same move twice quickly (Approve → Approve) | The second is a **200 with no second audit row**. `psql`: exactly one `suite.approved` row, and `updated_at` unchanged by the second press. | **live only** |
| 10 | Approve a folder, then `psql` `suite_version` | **No new row and `current_version_no` unchanged.** A suite version is its composition; approving does not change what is in the folder. The screen looks identical either way, which is why this row is here. | **live only** |
| 11 | Rail with ~10 folders at three depths, one badged | The badge does **not** crowd the name out — the name still ellipsizes rather than the count or badge wrapping — at the narrowest rail width and at 390 px. This is the reason drafts are unbadged at all. | mock + live |
| 12 | Deprecate a folder that holds cases, then open the case list scoped to it | The cases are **still there and still editable**. Deprecating a folder is a label on the folder, not a soft delete of its contents — nothing in this slice cascades. | mock + live |

**The row no browser can produce:** two leads moving one suite in the same instant. There is no
`If-Match` on this endpoint by design (the target is absolute), so the race resolves to whichever
lands second and both orders are legal moves. Recorded so its absence does not read as an oversight.

---

## §76 — the review record: the panel that shows a sign-off and cannot grant one

**Built and NOT browser-verified.** The eleventh slice in the deferred queue.

**Mock and live disagree by construction here, and both disagreements are deliberate.** The mock's
fixtures carry an open review on `TP-5` and a *rejected* one on `TC-8` — so mock mode can show the
panel's three shapes without driving a flow — while on a real instance **no artifact has a review
row at all** until somebody submits one after this deploy, including the artifacts that are already
`IN_REVIEW`. Row 1 is that state, and it cannot be reproduced once row 2 has run.

| # | Flow | What must be true | Mode |
|---|---|---|---|
| 1 | Live: open a case or plan that is already **In review**, before submitting anything new | The panel reads **"Not in review"** while the artifact's own status chip says In review, and the empty-state body explains that submitting is part of the status. This is not a defect: the record starts now. `psql`: `SELECT count(*) FROM review` is 0. | **live only** |
| 2 | Submit a plan for review (plan screen → Submit for review), then open the panel | State chip **In review**, "Submitted by *you*", the version pinned (`v1` on a fresh plan), an empty trail, and **no Approve button anywhere on the panel**. `psql`: one `review` row, `state = 'IN_REVIEW'`, `subject_version_no` equal to the plan's `current_version_no`. | mock + live |
| 3 | As a **Test Engineer** on the same open review: type a comment, press **Looks good** | 201, the trail gains one row reading *Looks good · your name*, the comment renders under it, **the box clears**, and the plan is **still In review** — a peer verdict moves nothing. | mock + live |
| 4 | Same caller, again: press **Request changes** with a different comment | **Both rows are there, oldest first.** Participations are append-only; a panel that replaced the first row would be hiding the fact somebody changed their mind, which is what the trail is for. | mock + live |
| 5 | As a **Viewer** (holds `case:comment`, not `case:review`) open a case with an open review | Both buttons **greyed with a tooltip**, the trail fully readable. §31's disable-don't-hide over §6g's three tiers — and the tier boundary is invisible unless it is rendered. | mock + live |
| 6 | As a lead: approve the plan from its own screen, then re-read the panel | State **Approved**, "Signed off by *you*" with a timestamp, an `APPROVAL / Approved` row at the end of the trail, and **the verdict form is gone** (not merely disabled). | mock + live |
| 7 | Submit again as person A, then **send it back as person B** (Return to draft) | State reads **Sent back**, and the trail's last row is `APPROVAL / Sent back` naming **B**. | **live only** — needs two accounts |
| 8 | Submit as person A and **withdraw it as A** (Return to draft) | State reads **Withdrawn** and **no verdict row is added**. Rows 7 and 8 are the same ladder edge and must read differently; this is the pair `V22` added a state for. | **live only** |
| 9 | With a review open, edit the artifact so it writes a version (a plan's **scope**, a case's content), then re-read the panel | The warning appears: *"…has changed since it was submitted — the review covers v2, and you are looking at v3."* The version on the record must **not** move. | mock + live |
| 10 | Withdraw and re-submit the same artifact twice | The panel shows the newest review at the top and an **"Earlier reviews"** list beneath it, each with its own state and version. `psql`: exactly one row with `state = 'IN_REVIEW'` for that subject. | mock + live |
| 11 | Have the review approved in another tab, then press **Looks good** in the stale one | **409 in place, inside the panel** — *"This review has already ended. Submit the plan for review again to reopen it."* — not a toast, and the trail refreshes rather than the page. | mock + live |
| 12 | The case detail sidebar at 390 px, with a two-line comment in the trail | The panel does not push the version history off screen, the comment wraps rather than overflowing, and the two buttons stay side by side or stack cleanly. | mock + live |
| 13 | Bulk-submit ~20 cases from the case list, then open two of them | **Each has its own open review**, each pinned to that case's own version. The bulk path is the one §6g calls the common case, and it is the one place a per-row write could silently do nothing. | mock + live |

**The row no browser can produce:** two people submitting the same artifact in the same instant.
`V22`'s partial unique index is what decides it, and the loser gets a constraint violation rather
than a second open review — recorded here so its absence does not read as an oversight, and queued
as a backend check.

---

## §77 — per-project review settings: the switch that decides whether §76 exists

**Built and NOT browser-verified.** The twelfth slice in the deferred queue.

**Read row 1 before any other.** This slice changes the *default* from "review everywhere" to
"review nowhere", so on both mock and live the first observable effect is that **§76's panel
disappears from every screen** until somebody turns it on. Every row in §76's block above now
presupposes that the project has review enabled — which is exactly what row 2 here does, and the
honest way to run the two blocks is §77 row 2 first.

**The mock deliberately disagrees with a fresh install, in one direction only.** `settingsForProject`
seeds the **demo project** with review on so §76's fixture reviews are reachable, and every other
project on the defaults. Both are states a real instance produces; a store that did only one of them
would be §72's finding (fixtures sitting in a state a fresh install cannot produce) or would make the
panel look unbuilt.

| # | Flow | What must be true | Mode |
|---|---|---|---|
| 1 | Live: open any case, suite and plan **before touching the settings screen** | **No Review panel anywhere** — not an empty panel, not a "review is off" placeholder, nothing. §6g's "a project that never turns it on should see no extra UI at all". `psql`: `SELECT * FROM project_settings` has one row per project, all three `review_enabled_*` false. | **live only** — the state disappears once row 2 runs |
| 2 | Project → **Review** in the nav rail. Turn on Test plans only, Save | 200, "Last changed … by you". Re-open a **plan**: the panel is there. Re-open a **case**: still nothing. The opt-in is per artifact type and this is the row that proves the map is not read as any-of. | mock + live |
| 3 | As a **Viewer** (no `project:settings`), open the same screen | The page **renders with every control disabled** and the current values visible — §31's disable-don't-hide — rather than 403ing or hiding the rail item. A Viewer must be able to find out why no artifact shows a panel. | mock + live |
| 4 | As a Viewer, confirm the rail item is present | "Review" appears under Project. Hiding it would leave the reader in row 3's situation with no route to the answer. | mock + live |
| 5 | Set **Peer reviews required** to `21`, then to `-1` | The field goes red with *"Between 0 and 20."* **before** any request is sent, and Save is disabled. The server refuses the same values on the same field; `V23`'s CHECK is the backstop behind both. | mock + live |
| 6 | Turn review on for plans, set peer minimum **1**, leave *the person who submits cannot approve* **on**. Submit a plan as yourself and try to approve it | **409 in place** — *"You submitted this plan for review, and this project asks somebody else to approve it."* The plan stays **In review**. Not a 403: the permission is held and the policy is what refuses. | mock + live |
| 7 | Same plan: have a **second account** press Looks good, then approve as the first | The approval now succeeds. This is the pair row 6 is half of, and it is the only way to see the peer minimum satisfied. | **live only** — needs two accounts |
| 8 | With the minimum at 1: have person B say **Looks good**, then **Request changes**, then try to approve | **Refused** — *"…needs 1 peer review … and has 0."* The latest verdict is what counts, and the trail shows both rows. The single most valuable row here: the cheap implementation passes row 7 perfectly and fails only this. | **live only** |
| 9 | Approve your own plan's LGTM: press Looks good as the submitter, then approve, with the author rule **off** | Still refused on the peer minimum — *"has 0"*. The submitter's own LGTM is not a second opinion, and the two settings are independent. | **live only** |
| 10 | Raise the minimum from 1 to 5 **while a review is open**, then approve that review | It **still approves**. The count is pinned onto the review at submission, so the goalposts do not move under reviewers who were already asked. Then submit a *new* plan and confirm it needs 5. | mock + live |
| 11 | With **Keep the approval** (RETAIN): approve a plan, then rename it | Status stays **Approved**, and the panel shows §76's staleness warning if the edit wrote a version. This is the behaviour the product has always had; the row exists so the contrast in 12 is readable. | mock + live |
| 12 | Switch to **Return it to draft** (RE_REVIEW), approve a plan, then rename it | Status becomes **Draft**. The warning Alert on the settings screen must have been visible before saving. `psql`: an audit row with action `plan.re_review_required` naming you, `from APPROVED to DRAFT`. | mock + live |
| 13 | As a **Test Engineer** (holds `plan:edit`, not `plan:approve`), edit that approved plan under RE_REVIEW | **The edit succeeds and the plan drops to Draft.** This looks wrong and is the design — see `EditAfterApproval.RE_REVIEW`. Charging the approve key would make an approved plan uneditable by the people §6g expects to edit it. The row exists so a future reader meets the decision in the product rather than only in a javadoc. | **live only** |
| 14 | Turn review **off** for plans while a plan is sitting In review | The panel disappears from that plan, and its status chip **still reads In review**. `psql`: its `review` row is **still `IN_REVIEW`** — switching the policy off does not withdraw submissions on anyone's behalf. | mock + live |
| 15 | Turn review off entirely, then move a case through Draft → In review → Approved from the bulk dialog | Every move **succeeds**. The ladder is not the apparatus: a project with review off keeps the four statuses it has used since M1. This is the row that would have caught the alternative reading, which would have silently removed approval from every existing project on upgrade day. | mock + live |
| 16 | The settings screen at 390 px | The three switches, the number field and the mode select stack without overflow; the RE_REVIEW warning wraps; Save and Discard stay reachable without horizontal scroll. | mock + live |
| 17 | Change one switch, then press **Discard changes** | The form returns to the server's values and Save re-disables. Then change one and navigate away and back: the draft is gone, which is correct — nothing here is autosaved. | mock + live |

**Not reproducible in a browser:** two admins saving the settings form at the same instant.
`project_settings.lock_version` is maintained (`@Version`) but **nothing sends an `If-Match`**, so
the second write wins silently. Recorded rather than implied — adding the header is a later slice,
and the column is annotated now precisely so that slice does not first have to discover it was
reading `0` for ever (§74's finding).

---

## §79 — review comments: the tier that may write and may not resolve

**Run §77 row 2 first** — every row here presupposes a project with review switched on for the
artifact being opened, and §77's row 1 is the only chance to see the off state.

The mock's fixtures carry **two threads on the demo project's open plan review** — one with a reply,
one already resolved and naming its resolver — and **none** on the rejected case review. That second
shape is the one to check against a real box: most reviews on a real instance have no conversation
at all, and a fixture set where every review has threads is §72's finding waiting to happen.

| # | Flow | What must be true | Mode |
|---|---|---|---|
| 1 | Open the demo project's plan in review | Under the verdict trail: a **Comments** section with two threads. The first shows a reply indented beneath it; the second is greyed with **"Resolved by …"** and a **Re-open** button. The reply has **no Reply button of its own** — threads are one level deep and the server refuses a reply to a reply. | mock + live |
| 2 | As a **Viewer** (`case:comment`, no `plan:review`), open the same plan | The comment box and **Reply** are **enabled**; **Resolve** and both verdict buttons are **disabled**, not hidden (§31). This is the row the whole slice turns on: one comment key for all three artifact types, and the reviewing tier above it. | **live only** — mock mode signs in as one persona holding everything | 
| 3 | Write a comment and press Comment | It appears at the bottom of the list, attributed to you, with the box cleared. `psql`: one `review_comment` row, `parent_id` NULL, `resolved_at` NULL. | mock + live |
| 4 | Press **Reply** on that thread, write, press Reply | It lands **under** the root rather than as a new thread, and the reply form closes. `psql`: `parent_id` = the root's id. | mock + live |
| 5 | Press **Resolve** on a thread as a Test Engineer | It greys out and reads "Resolved by *you*", with the button now **Re-open**. `psql`: `resolved_at` **and** `resolved_by` both set. | mock + live |
| 6 | Press **Re-open** | Both columns clear together — the row must not keep a `resolved_by` on an open thread. | mock + live |
| 7 | Press **Resolve** twice in a row (or on an already-resolved thread from a second tab) | The timestamp and the resolver **do not change**. The defect this prevents shipped in the slice's first draft: an unconditional write hands the record to whoever clicked last. | mock + live |
| 8 | Approve or reject the plan, then re-open it | The conversation is **still readable**, and **no comment box, no Reply, no Resolve**. A closed review takes no writes, and offering the affordance would be an affordance whose only outcome is a 409. | mock + live |
| 9 | Open a case or plan whose review has **no** comments | The section reads **"No comments yet."** with the box below it while the review is open — and on a **closed** review with no comments the whole section is **absent**, not an empty heading. | mock + live |
| 10 | Paste 5001 characters and press Comment | Refused with *"Keep the comment under 5000 characters."* in place, beside the box. Then clear it: the button disables on empty rather than letting the server refuse a blank. | mock + live |
| 11 | Write a comment containing a blank line and a list | The paragraph break survives (`pre-wrap`). What a reviewer typed is part of what they said; collapsing it changes the remark. | mock + live |
| 12 | The panel at 390 px, on a thread with a reply | The reply's indent does not push the body into horizontal scroll; the two buttons wrap rather than overflow. | mock + live |
| 13 | Live only: comment as one account, then resolve as another | The thread names **two different people** — the author on the remark, the resolver on the resolution line. The pair is the whole reason `V24` added the actor column. | **live only** — needs two accounts |

**Not exercisable here:** the anchor. §6g wants comments *"anchored to a specific step or field
where possible"*, and `anchor_type` / `anchor_id` stay NULL on every row — see `ReviewComment`'s
header for what accepting one would need first (a `PeopleDirectory`-shaped port into `repository`).
There is nothing to drive, and the row exists so the absence is not mistaken for a defect.

---

## §80 — reviewer assignment: asked, and not the same fact as spoke

**Run §77 row 2 first**, for §79's reason: every row here presupposes a project with review switched
on for the artifact being opened.

The mock's fixtures carry **three requests on the demo project's open plan review** — a person who
has also given a verdict, a **team** (QA Chapter), and a person who has said nothing — and **none**
on the rejected case review. The third of those three is the row the whole section exists for, and
the empty case is §72's rule applied to a fixture: most reviews on a real instance were never
assigned to anybody, because §6g's peer review is open to the whole tier and a request is a nudge.

| # | Flow | What must be true | Mode |
|---|---|---|---|
| 1 | Open the demo project's plan in review | Above the verdict trail: an **Asked to review** section with three chips — a person icon, a **group** icon on "QA Chapter", and a third person who appears in **no** verdict row. The trail below still lists only the one person who spoke. | mock + live |
| 2 | Compare the two lists deliberately | A name in "Asked to review" and **not** in the trail is a request nobody has answered — the row a lead opens this panel to find. If the two lists have been merged into one, this row is unverifiable and the slice's premise is gone. | mock + live |
| 3 | As a **Viewer** (`case:comment`, no `plan:review`), open the same plan | The chips are **readable**; the **Ask** button and the picker are **disabled**, not hidden (§31), and the chips carry **no ✕**. Asking is the reviewing tier's act — the bottom tier does not fill other people's queues. | **live only** — mock mode signs in as one persona holding everything |
| 4 | As a **Test Engineer** (holds `plan:review`, **not** `plan:approve`) | The picker and **Ask** are **enabled**. This is the gate decision made visible: the request must not be confined to the people whose sign-off it exists to prepare. | **live only** — same reason as row 3 |
| 5 | Open the picker | It lists project members and teams (teams suffixed "(team)"). **The submitter is absent**, and so is anybody already asked. Both absences are deliberate — the first is a server field error, the second a write that does nothing. | mock + live |
| 6 | Pick a person, press **Ask** | A new chip appears with their name and the select clears. `psql`: one `review_assignment` row, `assignee_user_id` set, `assignee_team_id` NULL, `assigned_by` = you. | mock + live |
| 7 | Pick a team, press **Ask** | A chip with the **group** icon and the team's name — and **no member names**. §6g says any member may action it; expanding the team would freeze the membership as it was when the page loaded. | mock + live |
| 8 | Press the ✕ on a chip | It disappears immediately and no confirmation is asked. `psql`: the row is **gone**, not tombstoned — an assignment is a worklist entry, not part of the audit record. | mock + live |
| 9 | Ask the same person twice (second tab, or re-open the picker after a refetch) | The chip count **does not change** and the original **"asked by"** attribution survives. Re-stamping would hand the request to whoever clicked last — §79's resolution defect in the one place it recurs. | mock + live |
| 10 | Approve or reject the plan, then re-open it | The chips are **still readable** and there is **no picker and no ✕**. A closed review takes no writes, and both affordances would only ever produce a 409. | mock + live |
| 11 | Open a plan in review that nobody has been asked to look at | *"Nobody has been asked yet. Anyone with review rights can still give a verdict."* — and on a **closed** review with no requests the whole section is **absent**, not an empty heading (§6g's "no extra UI"). | mock + live |
| 12 | The panel at 390 px with three chips and the picker | The chips wrap rather than overflow; the select and **Ask** stack rather than pushing the panel into horizontal scroll. | mock + live |
| 13 | Live only: delete a team that has an outstanding request | The chip is gone on the next load and **the person asked individually is untouched**. This is the decision §71's 409 is deliberately not extended to — what cascades is a request, not authority — and the DDL is the only thing enforcing it. | **live only** — needs the teams screen and a migrated database |
| 14 | Live only: ask somebody, then sign in as them | ~~Nothing surfaces on their board or anywhere outside this panel. **That is correct today and is the next slice**~~ → **superseded by §81, which built it.** The expectation is now the opposite: a **Review** card appears on that person's board. Run §81's rows instead of this one — a pass here would now mean the inbox is missing. | **live only** — needs two accounts |

**Not exercisable here:** asking a **sibling company's** account or a sibling company's team. Both
are refused as field errors and neither is reachable from any picker in either mode — the check is
the backend queue's, and the rows exist there.

---

## §81 — the reviewer's inbox: the board is where a request surfaces

**The section §80 ended by naming.** Until this slice a person asked to review found out by opening
the artifact; now the request is a card on the board, which is §6l's *"Review requested → card for
each reviewer"* — the third of its five derived-item generators to exist.

**Run §77 row 2 first** (review switched on for the demo project) — no review exists without it, and
therefore no card. Rows 4 and 5 are live-only for §80's reason: mock mode signs in as one persona,
so a second person's board cannot be seen there.

The mock's fixtures were rebuilt for this and the previous ones were **wrong in a way nothing could
see**: the old generator drew a card for every case whose `lifecycleStatus` was `IN_REVIEW`, in the
`IN_REVIEW` column, assigned to the case's **owner** — a reviewer inbox no server could produce,
shown to the person whose work is under review. If any row below reads like the old behaviour, that
is the regression to catch.

| # | Flow | What must be true | Mode |
|---|---|---|---|
| 1 | Open the demo project's **Board** as the signed-in user | Two **Review** cards in **To do** — `TP-5` (Payments hardening cycle) and `TC-20` (Cart survives a page reload) — each marked `linked`, each reading *"Review requested by …"*. **Not** in the In-review column: To do is where work nobody has started belongs, and In review is for items *being* reviewed. | mock + live |
| 2 | Click the key on the `TP-5` card | Lands on the **plan detail**, with §6g's review panel on it. This is the row the slice's link repair exists for — before it, every card routed to the case list, so a plan review searched the cases for `TP-5` and rendered "no cases match". | mock + live |
| 3 | Click the key on the `TC-20` card | Lands on the **case detail** and its review panel. Together with row 2: one card shape, three subject types, three destinations. | mock + live |
| 4 | Try to drag a review card, or press any of its move buttons | Refused, and the sentence names **a review** — *"TC-20 mirrors a review, so it can't be moved to Done. Update the review and the card follows."* Every derived card said "an execution or a review" until §81; more importantly, moving it must not perform the approval: §6l's eventual drag-to-Done is a gated act with an `If-Match`, and a drag would be a second door to the sign-off. | mock + live |
| 5 | Switch the board to **Assigned to me** | The review cards stay (both are addressed to the signed-in user). This is the inbox as people will use it. | mock + live |
| 6 | Live only: as a lead, ask a **colleague** to review something, then sign in as them and open the board | A Review card for that artifact, on **their** board, naming the lead as the asker. **This is the row the whole slice exists for** — §80's row 14 recorded its absence. | **live only** — needs two accounts |
| 7 | Live only: ask a **team**, then sign in as any member | A card each, for every member, and none of them was named on an assignment row. Then **add somebody to that team** and reload their board: the card is waiting. Expansion happens on the read, which is why membership can move — the write-time expansion §80 refused would have frozen the list at the moment somebody clicked. | **live only** — needs the teams screen and two accounts |
| 8 | Live only: as the person asked, leave a verdict (LGTM or Request changes), then reload the board | **The card is gone** — and gone on "changes requested" too, which is deliberate: they have done what was asked and the ball is with the author. A card that stays until they approve reads as work the reviewer still owes. | **live only** — needs the review panel and a board reload |
| 9 | Live only: approve or reject the artifact, then reload every asked person's board | All of its cards are gone, though the `review_assignment` rows are untouched. The board reads `review.state`; unassigning is the only thing that deletes a request. | **live only** |
| 10 | Live only: delete (soft) a case that has an open review somebody was asked for | The card disappears. "Go and review this" is not something to say about an artifact that is gone — and nothing in the schema removes the review, because the subject triple has no FK. | **live only** — needs a delete |
| 11 | The board at 390 px with two review cards | The cards wrap with the others and the `linked` chip does not push the title into overflow. Nothing new is laid out here, which is the point of one card shape. | mock + live |

**Not exercisable in mock mode:** rows 6–10, all for the same two reasons — one persona, and no
second account to be asked. **Not exercisable anywhere yet:** a review card on a **cross-project**
personal board, because §6l's "My Work" board does not exist; the board is per project, so the inbox
is per project too. That bound is stated in the handoff rather than left to be discovered.

---

## Release version in the footer (2026-08-03)

The footer used to render the string `0.1.0` as a **literal** in `AppShell.tsx`, so the number on
screen was not a fact about the running build: it survived every release unchanged. It is now
`__APP_VERSION__`, a Vite `define` fed from `deploy.yml`'s `version` input.

**This is the one row that cannot be fully checked in mock mode**, and that is inherent rather than
an oversight: `npm run dev` and a local `npm run build` both leave `VITE_APP_VERSION` unset, so the
footer correctly reads `dev`. Only a build produced by the workflow carries a release name. The
`web` job now greps `dist/assets/` for the dispatched version and fails the release if it is
absent — measured against a real build, the string appears exactly once, in the footer node — so
the automated half of this is genuinely covered and row 3 is the part a human must see.

| # | Flow | What must be true | Mode |
|---|---|---|---|
| 1 | `npm run dev`, scroll to the footer | Reads **`TCMS dev`** — not a version number. A plausible-looking number here means someone has re-introduced a hardcoded literal, which is the exact defect this replaced. | mock |
| 2 | `VITE_APP_VERSION=9.9.9 npm run build && npm run preview` | Footer reads **`TCMS 9.9.9`**. Proves the `define` reaches the emitted bundle and not just the dev server. | mock |
| 3 | After a real `gh workflow run deploy.yml -f version=<v>`, hard-reload the deployed site | Footer reads **`TCMS <v>`**, matching the dispatched input and the GHCR image tag. **This is the check the whole change exists for** — the previous release passed every gate and still showed the wrong number. | **live only** — needs a real dispatch |
| 4 | Deploy a second version, then reload | The number **changes**. One correct reading is consistent with a literal that happens to match; two are not. | **live only** — needs two dispatches |

## §82 — §6g's gating rules: the refusal that has to offer a way out

The sixteenth slice in this queue. §6g calls gating *"powerful and genuinely obstructive"*, which
makes this the one feature here whose **failure mode is a person who cannot start work and is not
told what to do about it** — so most of these rows are about the sentence and the affordance beside
it rather than about whether the rule fires.

**Both switches are off in the mock's fixtures on purpose**, so every row below opens by turning
one on at `/projects/:projectId/settings/review`. That is also row 1.

| # | Flow | What must be true | Mode |
|---|---|---|---|
| 1 | Settings → Review, toggle **Only approved test cases can go into a run**, Save, reload | The switch is still on after the reload. The **whole-body save** is the risk: this form `PUT`s all eight settings and the server reads an absent boolean as `false`, so a field dropped anywhere between the draft, the submit and the mock handler switches the gate off with a success message. A unit test asserts the body; this asserts the round trip. | mock |
| 2 | With gating on, change **only** the peer-review number and Save, then reload | The two gating switches are **still on**. The silent-discard defect in the form it would actually take — an admin editing an unrelated field quietly reopens the gate. Nothing on screen would say so. | mock |
| 3 | Turn a gate on and confirm the warning; turn both off | The amber warning appears while either is on and disappears when both are off. §6g requires this to be an explicit choice; the warning is where "explicit" lives. | mock |
| 4 | With cases gating on, start a run from a plan whose cases are drafts | **409 rendered in the dialog's banner**, naming the count and the first few keys — not a corner toast, and not a silent no-op submit. Read the sentence as somebody who did not configure this: does it say what to do next? | mock |
| 5 | Same, as a caller **holding `case:approve`** | **Create it anyway** appears under the refusal, enabled. Click it: the run is created, the dialog closes, and the run's checklist is the full one. This is §6g's documented override and the whole reason a 409 here is not a dead end. | mock |
| 6 | Same, as a caller **without** `case:approve` (a Test Engineer persona) | The button is **present and disabled**, with the gate's why-tooltip on hover (§31). Hidden instead of disabled is the defect: the person who has to go and ask would not know the way out exists. **Mock mode signs in as one persona holding every key**, so this row is **live only**. | **live only** |
| 7 | Edit the run name *after* the refusal, then click **Create it anyway** | The run is created with the **edited** name. The retry re-reads the form rather than replaying the first attempt's values — a replay would create a run the user is not looking at. | mock |
| 8 | With plan gating on, open a draft plan and press **Start** | 409 beside the buttons, and **Start it anyway** appears next to it. Check the sentence names review as the way forward. | mock |
| 9 | Same plan, press **Submit for review** and let it be refused for a ladder reason | **No override button.** The gate can only refuse a start, so an override under a sentence about submitting for review would offer to bypass something that is not blocking. | mock |
| 10 | Approve a plan properly, then Start it | Starts normally, no refusal, no override affordance. The rule satisfied must look like nothing happened. | mock |
| 11 | Start a plan, Close it, then **Reopen** it — with plan gating **on** | **It reopens.** This is the row the whole slice turns on: a target-keyed reading of §6g makes every closed plan permanently un-reopenable, and the screen would show a Reopen button that 409s for ever with a message about approving a plan that cannot be approved. | mock |
| 12 | Switch **all three review opt-ins off** while leaving a gate on, then repeat rows 4 and 8 | The gates **still fire**, and no review panel is rendered anywhere. §82's decision made visible: gating reads the status ladder, review's opt-in suppresses the apparatus. A build where these are coupled looks identical until this row. | mock |
| 13 | Turn a gate on for project A only, then repeat row 4 in project B | Project B is unaffected. Per-project settings, and the read is `useProjectSettings(projectId)` — a cache keyed too broadly would leak A's policy into B. | mock |
| 14 | After an override, check the audit trail | `approvalGateOverridden` is on the row — and is **absent** on an ordinary run created with the gate off. **No audit screen exists** (`audit:view` has no consumer), so this is a `psql` read against `audit_event.changes`, not a UI check. | **live only** |
| 15 | With gating on, create a run through the dialog while the project has **no** unapproved cases | Created normally with no mention of the gate anywhere. A gate that announces itself when it is satisfied is the noise that makes people turn it off. | mock |

## §83 — §6l's cross-project "My Work" board

The seventeenth slice in this queue, and the first screen in the product that is **not** inside the
project frame. Most of these rows exist because mock mode's fixtures are dense in one project and
thin in the other: almost every defect this slice repaired is one where the route's project and the
card's project happened to be the same value, so a row that only looks at OLB proves nothing.

The fixture now seeds **one task in MOB assigned to the signed-in user** for exactly that reason —
without it, My Work in mock mode is byte-identical to OLB's personal board.

| # | Flow | What must be true | Mode |
|---|---|---|---|
| 1 | `npm run dev`, look at the rail before touching anything | **My work** sits **above** the project switcher, with a divider between them. Below it is the frame the switcher sets; a personal board listed inside that frame tells the reader that switching projects changes it, which is the same misstatement the `Teams & roles` grouping made one level further out. | mock |
| 2 | Click **My work**, then switch projects in the rail's switcher | The board **does not change**, and the browser tab title stays "My work". If the content moves, something is reading a project this screen does not have. | mock |
| 3 | On My Work, read the cards | Cards from **both OLB and MOB** are present, each stamped with its own project key. One project only means either the fixture lost its MOB task or the aggregate quietly became one project's board — and every unit test would still pass. | mock |
| 4 | Click the key on a **MOB** card | Lands on a MOB URL that resolves. **This is the row the slice's main repair exists for**: before §83 the link took the board's project, so this card would have gone to `/projects/{OLB}/…` and 404'd. Check the address bar, not just that a page rendered. | mock |
| 5 | Click the key on an **OLB** review card (a case, then the plan one) | Case → case detail with §6g's panel; plan → plan detail. §81's routing still holds when the project comes from the card rather than from the route. | mock |
| 6 | Drag a **task** card on My Work to another column, then open that project's own board | The card is in the new column **on both boards**. The move invalidates the shared `work-items` root; keying it to one board leaves the other showing a card in the column it just left, and neither screen would look wrong on its own. | mock |
| 7 | Drag a **derived** card (an execution or a review) on My Work | Refused in place, with the sentence naming what to update. Identical to the project board — this is one component, and a difference here means a second board has grown. | mock |
| 8 | Look for an "Everyone" / "Assigned to me" chip pair, and for **New task** | **Neither is present.** The chips would produce a 400 the server sends deliberately ("Choose a board"); the button would need a project this screen does not have. An affordance whose only outcome is an error is `ui-rules` §4's render-then-reject. | mock |
| 9 | `tcmsFaults.hang()`, then reload `/my-work` | The region shows its loading state and then **fails on the timeout budget** rather than spinning for ever. The header stays on screen throughout — the page's chrome is not inside the region. | mock |
| 10 | `tcmsFaults.status(503, 30)`, reload, then `tcmsFaults.off()` and press **Try again** | The failed state offers a retry and the retry works. A personal board is the screen somebody has open when the network blips; a dead end here is the one they will report. | mock |
| 11 | Load My Work, then `tcmsFaults.status(500)` and let it refetch | The board **keeps its cards** and marks them stale rather than blanking. A failed *refetch* that empties this screen reads as "your queue is clear", which is the worst possible wrong answer on this particular page. | mock |
| 12 | `tcmsFaults.expire()`, then reload My Work | The session recovers **in place** and the board loads. This screen is a bookmark people open cold in the morning, which is exactly when a token has expired. | mock |
| 13 | Edit the URL to `/projects/<a-uuid-that-does-not-exist>/board` | **A not-found / error state, not another project's board.** The mock used to fall back to the first project for any unknown id, so a stale bookmark rendered OLB's cards under a foreign id and every key linked into a project that does not contain them. No unit test covers this — the handler branch is only reachable by typing a URL. | mock |
| 14 | Sign in and go straight to `/my-work` **before** visiting any project | The screen renders. It must not depend on `useProjects` having answered or on a project being resolved — a member holding no grants at all has this board and nothing else. | mock |
| 15 | Keyboard only: Tab from the top of the page | The skip link, then **My work** as the first rail item, then the switcher. Its active state is marked by the indicator bar and weight, not colour alone, when you are on it. | mock |
| 16 | Narrow the window below `md`, open the hamburger | **My work** is in the drawer, in the same position, and tapping it closes the drawer. The rail and the drawer render the same content — a row that is in one and not the other means the drawer took a different path. | mock |
| 17 | `VITE_API_MODE=live`, sign in as a user with **grants on two projects**, open My Work | Cards from both. The mock has no per-project grant data (`projectMembers` records the same limit), so the "projects you can **see**" half of the aggregate is only genuinely exercised against a real server. | **live only** |
| 18 | Live, as a user with **no project grants at all** | **`200 []`** rendered as the empty state — never a 404 and never an error. The personal board names no project, so nothing can fail to resolve; this is the first screen a newly added member sees and it must read as an empty queue rather than as a broken product. | **live only** |
| 19 | Live, hit `GET /api/v1/work-items` directly with no parameters | **400, "Choose a board"**, with the sentence naming both requests that do work. The SPA never sends it; this row confirms the refusal the SPA's shape is built around actually exists. | **live only** |

## §85 — §6l's team board

The eighteenth slice in this queue, and the **second** screen outside the project frame. Two things
make these rows different from §83's: the reader is looking at *other people's* work, and the answer
is bounded by the reader's own grants — so several rows are only meaningful with two different
sign-ins, and the ones that matter most are **live only**, because mock mode has no project anybody
is barred from.

The fixture now seeds **one OLB task assigned to Priya Nair** (Checkout Squad, not the signed-in
user) for the same reason §83 seeded a MOB task: without it, every card in the store belongs to
Payments Squad and a team board that ignored membership entirely would look right.

| # | Flow | What must be true | Mode |
|---|---|---|---|
| 1 | `npm run dev`, open **Teams & roles**, look at any team row | A **Board** link is present on every row, and it is **not greyed out** — even when Edit / Members / Delete beside it are. It is the one affordance there with no permission behind it. | mock |
| 2 | Sign in as a caller **without** `company:teams` (a Test Engineer), open Teams & roles | Same: the three admin buttons are disabled with a reason, **Board is live**. This is the row the slice's gating decision turns on — a Board button written as a fourth child of that gate looks identical until this sign-in. | **live only** |
| 3 | Click **Board** on *Payments Squad* | Lands on `/teams/<uuid>/board` — check the address bar. The title reads "Payments Squad — board", the tab title "Board · Payments Squad". | mock |
| 4 | Read the cards on that board | Cards assigned to **more than one person**, each card naming its assignee, and cards from **both OLB and MOB** each stamped with its own project key. One person or one project means either the fixture regressed or the board quietly became the personal one. | mock |
| 5 | Go back and click **Board** on *Checkout Squad* | A **different set** — Priya's OLB task is here and the signed-in user's cards are not. Two teams showing the same board is the defect that passes every unit test written from one team. | mock |
| 6 | Click the key on a card from **MOB** on a team board | Lands on a MOB URL that resolves. `linkFor` has needed repair in two consecutive slices (§81, §83); this is the second cross-project board and therefore the second screen that would expose the same class. Check the address bar, not that a page rendered. | mock |
| 7 | Drag a **task** card on a team board to another column, then open that project's own board and My Work | The card is in the new column on **all three**. The move invalidates the shared `work-items` root; a third board is exactly the case §83's key comment predicted. | mock |
| 8 | Drag a **derived** card on a team board | Refused in place with the sentence naming what to update — identical to both other boards. A difference here means a second board has grown. | mock |
| 9 | Look for filter chips and for **New task** | **Neither is present.** `teamId` + `mine=true` is a 400 the client must not be able to send; a create needs a project this screen does not have. | mock |
| 10 | Edit the URL to `/teams/<a-uuid-that-is-not-a-team>/board` | **"No such team"** with a *Back to teams* button that works — **not** an empty board, and not "Not found — Not Found". The server's 404 is empty-bodied, so the sentence is the client's; this row is the only place the copy is visible. | mock |
| 11 | From row 10, press **Back to teams** | Lands on `/admin/teams`. A dead-end error page on a screen reached by a shared link is the failure this button exists for. | mock |
| 12 | Open a team with **no members** (create one on Teams & roles, then open its board) | The **empty state**, worded as a reachable empty board — not the "No such team" error. The two must be visibly different; they are the two answers this screen exists to keep apart. | mock |
| 13 | `tcmsFaults.hang()`, then reload a team board | Loading state, then **failure on the timeout budget** rather than an endless spinner. The header stays on screen throughout. | mock |
| 14 | `tcmsFaults.status(503, 30)`, reload, then `tcmsFaults.off()` and press **Try again** | The failed state offers a retry and the retry works — and it must **not** read "No such team". A 503 wearing the 404's sentence sends the reader looking for a team that is still there. | mock |
| 15 | Load a team board, then `tcmsFaults.status(500)` and let it refetch | The board **keeps its cards** and marks them stale rather than blanking. | mock |
| 16 | `tcmsFaults.expire()`, then reload a team board | The session recovers **in place** and the board loads. | mock |
| 17 | With the org chart failing (`tcmsFaults.only('/teams')` then `tcmsFaults.status(500)`), open a team board | The **board still renders**, under the fallback title "Team board". The name is chrome from a second read; gating the board on it would blank a board that loaded fine. | mock |
| 18 | Keyboard only: Tab through a team row on Teams & roles | Board is reachable and activates on Enter as a link (not a button-that-navigates), and its focus ring is visible against the row. | mock |
| 19 | Narrow the window below `md`, open a team board | The columns stack (`xs: 1fr`) and nothing scrolls the page horizontally. The board is the same component, so this is a regression check on the new frame around it. | mock |
| 20 | **Live**, sign in as **user A** granted on two projects and open a team's board; note the cards. Then sign in as **user B**, granted on only one of them, and open **the same team's board** | **B sees strictly fewer cards** — the ones from the project they hold no grant on are absent, and no error is shown. This is §84's headline decision made visible, and it is *unreachable in mock mode*: the fixture has no project the caller is barred from, so this bound is structural there rather than demonstrated. | **live only** |
| 21 | **Live**, as a member with **no project grants at all**, open any team's board | **`200 []`** rendered as the empty state — never a 404 and never an error. | **live only** |
| 22 | **Live**, as a member who is **not on the team**, open its board | It renders. Membership is not a requirement (§6k) — a team board a lead's manager cannot open is a board for nobody. | **live only** |
| 23 | **Live**, hit `GET /api/v1/work-items?teamId=<uuid>&mine=true` directly | **400, "Choose one board"**. The SPA cannot send it; this row confirms the refusal the SPA's shape is built around actually exists. Note it is *unobservable in the body* for a caller on the team, so the status code is the only evidence. | **live only** |
| 24 | **Live**, hit `GET /api/v1/work-items?teamId=<a team in another company>` | **404, empty body** — identical to an unknown UUID. If the two differ in any way, the operation is a company-wide team-existence oracle (§6t). | **live only** |
| 25 | **Live**, hit `GET /api/v1/work-items?teamId=<valid>&projectId=<valid>` | **200**, the team's work inside that one project. Legal (§6l's swimlanes) and no screen sends it yet — the mock answers it, so this row is what stops the mock's answer being the only definition. | **live only** |

**Highest-risk rows are 20 and 5.** Row 20 is the only place the reader-bound is observable at all;
row 5 is the only place the team-bound is, and both are the kind of defect that looks completely
correct to whoever wrote it, because their own board is right.

---

## §86 — `failing_repeatedly` lights a dashboard tile and a board card

**Live only, all of them, and the reason is structural rather than incidental.** §86 touched no
frontend file: `HealthSignal.FAILING_REPEATEDLY` has been in the contract since §47, labelled in
`lib/labels.ts` and rendered by `DashboardPage`'s "Needs attention" panel and the board's
`CASE_MAINTENANCE` card ever since. What changed is that a server now *asserts* it. **Mock mode
cannot observe any of this** — `src/mocks/db.ts` invents its own health rows and has never been fed
by a fact log — so every row below is `live`, and mock mode agreeing is not evidence.

**Getting into the state at all takes a fixture and a wait**, which is what makes these rows worth
writing down rather than assuming. The scheduler runs at `PT3M` after startup then every `PT15M`; a
faster loop is `docker compose exec` nothing — there is no CLI verb for it — so either wait for the
tick or restart the container after seeding.

| # | Drive | Expect | Mode |
|---|---|---|---|
| 1 | **Live.** Provision a company, create a project, one case, and record **three** `Failed` results for it across three runs (the unique constraint forces one run each). Wait for the tick. Open `/projects/{id}/dashboard`. | The **"Needs attention"** panel shows a **Failing repeatedly** tile with count **1**, severity WARNING (amber, not red — §47's severity split puts this below "the case is now wrong"). Before this slice the tile could only ever read 0. | **live only** |
| 2 | **Live.** Same case, now record a **fourth** result: `Passed`. Wait for the next tick. Reload the dashboard. | The tile drops back to **0** and the case's chip disappears. **This is the self-healing property §6o's whole design rests on** — "no 'mark as not-stale' button exists to lie with" is only true if the computation actually takes it back. A signal that latches on is indistinguishable, on screen, from one that is correct. | **live only** |
| 3 | **Live.** Record **three `Blocked`** results for a *different* case. Wait for the tick. Reload. | That case is **not** listed under Failing repeatedly. The predicate is `status_kind = 'FAILED'`, not "did not pass" — a blocked case is an environment problem, and flagging it here sends the reader to fix the wrong thing. The backend has a test for this; the row exists because the *tile* is where somebody would notice it and misread it. | **live only** |
| 4 | **Live.** With row 1's case flagged, open `/projects/{id}/board`. | A **`CASE_MAINTENANCE`** card for that case, naming the signal, in the right column. **Structurally empty since §53** — `BoardFacts` has read `case_health` since the board shipped and only two of nine signals had a writer, neither of which the card was built around. This is the first time the card can appear for this signal at all, and its `linkFor` must resolve (§81 and §83 each repaired a link that did not). | **live only** |
| 5 | **Live.** Before any scheduler tick has run on a fresh company, open the dashboard. | The panel reads **"not computed yet"**, not an empty "nothing needs attention". `healthComputedAt` is what distinguishes them and `CaseHealth`'s header calls the distinction the reason the column is mapped at all. **§86 makes this harder to see, not easier**: reporting's refresh now stamps `computed_at` too, so the window in which it is null is the first three minutes of a deployment. Worth confirming it still exists. | **live only** |
| 6 | **Live.** Two companies, both with a failing-repeatedly case. Sign in as a member of one. | The dashboard counts **only** that company's case. The projection carries no company predicate — `FORCE ROW LEVEL SECURITY` is the entire mechanism on both the `SELECT` and the `INSERT` — and this is the on-screen half of a property the backend asserts with two companies. | **live only** |
| 7 | **Live.** Soft-delete a flagged case, wait a tick, reload the dashboard. | The tile count drops and the case is gone. `case_health` rows are derived for live cases only, while the **fact log deliberately keeps the deleted case's results** — the two opposite calls in one slice, and the screen is where their consistency is legible: no orphan chip, and no report row lost. | **live only** |
| 8 | **Live, and this one is about cost rather than correctness.** With a project of a few hundred cases, watch the dashboard's response time before and after a tick has populated `failing_repeatedly`. | No visible regression. **`V1` ships four partial indexes on `case_health` — `outdated_spec_changed`, `stale_approval`, `never_executed`, `unlinked` — and none on `failing_repeatedly`**, so this signal's tile is the first that has to scan. Found while reading the DDL for this slice and deliberately **not fixed**: adding an index is a migration, and §86 states it has none. If this row shows a real cost, the index belongs in R3's `V27` alongside the job table. | **live only** |

---

## §87 — the report pipeline (R3)

**There is no screen yet, and that is what these rows are for.** F1/F2 (the request form and the
on-screen report view) are later slices; what §87 shipped on the frontend is the corrected mock
handler for three operations nothing calls. Every row below is therefore driven **from the browser
console** with `fetch`, not by clicking — which is the honest way to record a check on a handler
that has no UI, rather than deferring the whole thing until a screen exists and inheriting whatever
the mock happens to do by then.

**What was wrong until this slice** is the reason the rows exist at all: the handler answered
`{ id, status: 'QUEUED', format: 'PDF', requestedAt }` — `id` where the contract says `jobId`, a
`requestedAt` the contract never declared, and `QUEUED`, **never one of the four values
`ReportJob.status` has had**. §52's "the mock agrees with an idea of the feature", §81's finding one
slice earlier, and cheap only because it is being corrected before a screen depends on it.

Run these in mock mode (`npm run dev`, default) from the console on any signed-in page. `P` is a
project id from the URL.

| # | Drive | Expect | Mode |
|---|---|---|---|
| 1 | **Mock.** `await (await fetch(`/api/v1/projects/${P}/reports/generate`, {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({type:'run_summary', format:'MD'})})).json()` | **202**, and a body carrying **all seven** contract-required fields: `jobId` (not `id`), `type`, `status: 'PENDING'`, `format`, `downloadUrl: null`, `modelUrl: null`, `error: null`. No `requestedAt`. A `Location` header pointing at `…/reports/jobs/{jobId}`. | mock |
| 2 | **Mock.** Poll `…/reports/jobs/{jobId}` **three times** in a row. | `PENDING` → `RUNNING` → `COMPLETED`, one state per poll. **The intermediate states are the point** — a mock that completed on the first poll would leave the two states a real client spends its whole loading life in unreachable in dev, so the polling UI would be developed against a state machine with one state. | mock |
| 3 | **Mock.** On the `COMPLETED` job, follow `downloadUrl` and `modelUrl`. | Both resolve — Markdown and JSON respectively. `modelUrl` is decision F's other half: the on-screen view (F2) reads the same artifact the export was rendered from, so a null here is a screen that cannot be built without a second computation. | mock |
| 4 | **Mock.** Ask for `format: 'PDF'`, then for `format: 'DOCX'`, then for `type: 'quarterly_vanity_metrics'`. | **400 each**, with the error under `format`, `format` and `type`. The mock's refusals read its own `TYPES`/`FORMATS` registries rather than literals, mirroring the server — the same request must not be a 400 here and a 202 in live mode. | mock |
| 5 | **Live.** Same as row 1 against a real backend, signed in as a Test Lead. Then poll. | **202**, then a job that reaches `COMPLETED` **without polling advancing it** — the real worker is on a `PT15M` schedule with a `PT3M` initial delay, so this either waits for a tick or follows a container restart. The difference from row 2 is the check: the mock's poll-driven clock must not be what a client depends on. | **live only** |
| 6 | **Live.** Sign in as a persona holding `report:view` and **not** `report:export` (needs a grant edit — no built-in role has that shape), and `POST …/reports/generate`. | **403 with a problem body**, and a poll of an existing job **200**. **Unobservable in mock mode**, which signs in as one persona holding every key — the backend asserts this with two custom fixture roles (§87 phase 6) and the browser is where a client would meet it. | **live only** |
| 7 | **Live.** The same persona follows a completed job's `downloadUrl`. | **403 with an empty body** — deliberately bodiless, unlike the other two refusals, because the response is typed to a binary resource and a JSON problem document under a status the client can already read tells it nothing. Worth seeing in a browser: confirm it does not render as a corrupted download. | **live only** |
| 8 | **Live.** As a Test Lead, follow `downloadUrl` in the address bar. | The file **downloads** rather than rendering — `Content-Disposition: attachment`, named `run_summary-{artifactId}.md`. Evidence is served `inline` (a screenshot is shown beside the step it failed on); a report is a file somebody keeps. | **live only** |

---

## §88 — the report header and the HTML renderer (R4)

Still no screen: F1 and F2 are later slices, so these are console-driven for §87's reason. What R4
changed on this side is the **shape of the stored model** (`{ header, report }`) and the fact that
`format: 'HTML'` is now a 202 rather than a 400 — both of which F2 and F1 will be built against, so
they are worth seeing before the screens exist rather than inherited.

| # | Drive | Expect | Mode |
|---|---|---|---|
| 9 | **Mock.** Generate with `format: 'HTML'`, poll to `COMPLETED`, then open `downloadUrl` in a tab. | **202** (not the 400 it was before R4 — the mock's `FORMATS` registry moved with the server's), and a page that renders as a **document**: a `<title>`, an `<h1>`, no broken frame. The mock serves a whole self-contained file rather than a fragment on purpose; a snippet would make the download look like it worked while producing something no browser opens as a report. | mock |
| 10 | **Mock.** On the same job, `await (await fetch(modelUrl)).json()`. | `{ header, report }` — **both keys**. `header` carries `reportType`, `description`, `company`, `projectScope`, `scopeNote`, `generatedBy`, `generatedAt` and a `selection` array; `report` carries `title`, `metadata`, `sections`. A bare `{ title, sections }` here is the shape F2 would be built against and the server does not serve it — §81's and §87's finding, one slice apart. | mock |
| 11 | **Live.** As a Test Lead, generate `format: 'HTML'` for a real run, wait for the tick, and open `downloadUrl`. | A rendered report with the **provenance block at the top**: company, project scope, the §390 scope sentence, who generated it, when, and `Selection — runId`. Then **turn the network off and reload the saved file** — it must render identically. That is the check §6e asks for: "a self-contained file that needs a CDN to draw its diagrams is not self-contained", and the unit test asserts the absence of URLs while only a browser proves it *looks* like a report without them. | **live only** |
| 12 | **Live.** In the same document, read the bar chart, then use the browser's **Print preview**. | Bars drawn in CSS with the number printed beside each one, and a print preview where no table row is split across a page boundary (`break-inside: avoid`). This page is what the PDF slice will hand to a headless browser, so the print view is the deliverable's dry run — and the number beside each bar is what survives a mail client that strips the stylesheet. | **live only** |
| 13 | **Live.** Name a project (or a run) with `<img src=x onerror=alert(1)>` in it, generate an HTML report, and open the file. | The text appears **as text**, no alert, no broken layout. Every value in a report is user-controlled; the renderer's escaping is asserted by a counted unit test, and this is the one place the claim is checked against a real browser's parser rather than against a string assertion. | **live only** |

---

## §89 — the Reports screen (F1)

**The first rows in this file that drive a screen rather than a console.** §87 and §88 queued
console rows because there was nothing to click; F1 is the click. Row 8's live check is now
*obsolete as written* and is restated below as row 22 — following `downloadUrl` in the address bar
sends **no `Authorization` header**, so what it actually proves is a 401, and the screen deliberately
does not use an anchor for exactly that reason.

The three §65-shaped risks this slice carries are rows 15, 19 and 22. Everything else is ordinary.

| # | Drive | Expect | Mode |
|---|---|---|---|
| 14 | **Mock.** Open a project, click **Reports** in the rail. | The screen renders: header, a **Generate** button, and a "Saved reports" panel showing the **empty state**, not a spinner and not a table with no rows. The rail item is under **Execution**, marked active (`aria-current`), and is present for every persona — it is ungated on purpose (§89), so no persona sees the rail item disappear. | mock |
| 15 | **Mock.** Click **Generate**, choose run `TR-1`, leave the format at HTML, submit. Watch the card. | A card appears **immediately** carrying `Queued` — not a skeleton, because the 202's body is seeded into the cache. Then `Building`, then `Ready`, one state per poll, roughly two seconds apart. **This is the row that proves the poller runs at all**: the three states are why the mock advances one per poll, and a card that jumps straight to Ready means the seeding or the interval is wrong. | mock |
| 16 | **Mock.** On the `Ready` card, click **Download HTML**. | A file **saves to disk** named `run_summary-{artifactId}.html`. Open it: a whole document with a `<title>` and an `<h1>` naming `TR-1` — the run actually chosen, which is the mock reading the stored `parameters` rather than the job id. | mock |
| 17 | **Mock.** With devtools' Network tab open, repeat row 16 and inspect the download request. | It carries an **`Authorization: Bearer …` header**. That is the whole reason the button is a button and not a link — see row 22. | mock |
| 18 | **Mock.** Generate a second report while the first is still `Building`. | **Two cards**, newest first, each advancing on its own. Neither loses its state to the other: the query key carries the job id, and a key naming only the project would make the second answer overwrite the first's `downloadUrl`. | mock |
| 19 | **Mock.** In the console, `await (await fetch(`/api/v1/projects/${P}/reports/generate`, {method:'POST', headers:{'Content-Type':'application/json','Authorization':'Bearer x'}, body:JSON.stringify({type:'run_summary', format:'MD', parameters:{}})})).json()` — then paste that `jobId` nowhere; instead generate through the UI in a project **whose runs list is empty in the store**. Simpler path: temporarily edit `parameters` in `GenerateReportDialog` to send `{}`. | The card reaches **`Failed`**, red, carrying the worker's sentence, with **no download button** and **no spinner**. Distinguishable at a glance from a `Ready` report that happens to contain nothing — §85's 404-vs-`200 []` argument, and the defect the M2.5 plan names for this screen. **§89 added this path to the mock for this row**: before it, `FAILED` was unreachable without a backend. | mock |
| 20 | **Mock.** Choose format **PDF** and submit. | A **400** whose sentence lands **under the Format field** — *"PDF rendering is not installed on this server. It can produce: MD, HTML."* — not in the banner at the top. The picker is where the reader can act on it. PDF is offered on purpose: the enum is the contract's, not this deployment's. | mock |
| 21 | **Mock.** Reload the page while a card is showing. | The cards are **gone**, and that is the current design, not a bug to file: nothing lists jobs, so the screen can only show what this tab asked for. Confirm the running card said so before the reload. **If this reads as broken to you, that is the argument for R5/a list endpoint** — record it, do not patch it client-side. | mock |
| 22 | **Live.** Sign in as a Test Lead. Copy a completed job's `downloadUrl` and paste it into the address bar. Then download the same report **through the screen's button**. | The address bar gives **401**; the button gives the file. This supersedes §87's row 8, which assumed a browser navigation would authenticate. The session is a bearer token in a header and `SecurityConfiguration` is `STATELESS` with no cookie, so **no `<a href>` into `/api/v1` can ever authenticate** — the finding that shaped this slice's transport. | **live only** |
| 23 | **Live.** Same persona, generate a report and wait for the real worker's tick (`PT15M` schedule, `PT3M` initial delay). | The card sits at `Queued`/`Building` for **minutes**, not seconds, and the copy under it holds up over that duration — this is the only place the wait is real. The mock's poll-driven clock is what makes it two seconds; a client that only ever saw the mock would have copy written for a two-second wait. | **live only** |
| 24 | **Live.** Sign in as a persona holding `report:view` and **not** `report:export` (a grant edit — no built-in role has that shape). | **Generate is disabled with a why-tooltip**, never hidden (§31), and the Reports rail item is **still there**. Poll and definition reads still work, so the screen is fully readable. **Unobservable in mock mode**, which signs in as one persona holding every key. | **live only** |
| 25 | **Live.** Same persona, click a `Ready` card's Download (reachable only if a job was generated by someone else — or re-grant `report:export`, generate, then remove it). | **403**, handed back **beside the button** in the card rather than as a corner toast, with the card and its status still on screen. The route's 403 is deliberately empty-bodied, so this also confirms `describeError` produces a readable sentence from the status alone. | **live only** |

---

## §90 — the on-screen report view (F2)

**Eight rows.** Row 26 is the one that proves the whole slice — the screen and the downloaded file
must be the *same numbers*, because that is what §6e's one-model architecture claims and no unit
test can compare an HTML file to a React tree. **Row 31 is the slice's finding** and is live-only:
the artifact route is gated on `report:export`, so the on-screen view costs the same key as carrying
the report away, which contradicts the split §6e names and §87 built a custom role to express.

Rows 27 and 30 exist because the two states they drive — a report with nothing in it, and a stored
model this client cannot read — both render as *something is missing*, and telling them apart at a
glance is the whole of this screen's error design.

| # | Drive | Expect | Mode |
|---|---|---|---|
| 26 | **Mock.** Generate an HTML report, wait for `Ready`, click **View report**. Then click **Download HTML** and open the saved file beside the tab. | The screen shows the provenance block, the section and its table; the file shows the same values. **Compare them line by line** — company, project scope, the scope sentence, who generated it, when, `Selection — runId`, then the section's numbers. Any difference is the drift decision F exists to prevent, and it is invisible to every gate: the two renderers share a model and nothing checks they render it the same. | mock |
| 27 | **Mock.** On the report view, read the section body. | The bar chart draws bars **proportional to the largest value in the series**, with the number printed beside each one, and the table's cells read exactly as the file's do — `50.0%`, not `50%` and not `0.5`. The number beside the bar is not decoration: it is what survives a reader who cannot judge length against a scale they cannot see. | mock |
| 28 | **Mock.** Copy the report view's URL, reload the page, then open the URL in a **new tab**. | The report renders **both times**. This is the durable handle the reports screen does not have — row 21 records that the cards are lost on reload, and this URL is what survives it. Confirm the breadcrumb still reads *Project → Reports → the report's title*, and that **Reports** links back to a screen with no cards on it, which is the honest state. | mock |
| 29 | **Mock.** Open a report view URL for a job **that is still building** (click View the instant the card offers it, or paste the URL while the card reads `Building`). | The page shows *"Building — this page updates itself when the report is ready"* with a spinner, **not** a report skeleton and not an error — and then **turns into the report by itself**, with no reload. The model request must not be made before the job completes: check the Network tab shows no artifact request until then. | mock |
| 30 | **Mock.** Break the stored model on purpose: in `mocks/handlers/reporting.ts`, make `mockModel` return `{ report: { title: 'Half a model' } }`. Open a report view. | A **failed region with a retry**, whose sentence says the stored model is not in a shape this version can display — **not** an empty report and not a blank panel. This is the one rendering that separates "the report contains nothing" from "this client cannot read the report", and a cast instead of a parse makes them the same screen. Undo the edit afterwards. | mock |
| 31 | **Live.** Sign in as a persona holding `report:view` and **not** `report:export` (a grant edit — no built-in role has that shape). Open a completed job's report view URL. | The job renders (the poll needs only `report:view`), and the report is **refused with the worded 403**: *"You can watch this report being made, but not open it"*, naming `report:export`. **This is the finding, not the expected product behaviour** — §6e's split is "may read on screen, may not carry off", and today the screen costs the export key because the model is served through the download route. Record what it looks like; the fix is the server's. | **live only** |
| 32 | **Live.** As a Test Lead, generate a report for a real run and open its view. | The header block carries the **real** company, the `KEY — Name` project scope, the §390 scope sentence, the signed-in person's display name and a **UTC-labelled** timestamp — all of them values only a live deployment can produce (the mock's are literals). Compare the `Generated at` against the run's own timestamps for a timezone that reads sensibly. | **live only** |
| 33 | **Live.** With the report view open, use the browser's **Print preview**, then narrow the window to a phone width. | The tables scroll **inside their own container** rather than making the page scroll sideways (`ScrollX`), and the print view is legible. This screen is not the PDF deliverable — §88's HTML file is — but it is the one a lead will print from a laptop in a release meeting. | **live only** |

---

## §91 — the way back to the picker, and the rail that collapses

**Six rows.** Row 34 is the one the slice exists for and takes four seconds; rows 36–37 are the ones
worth the time, because they cover the two things a unit test cannot see — what an icon-only rail
*looks* like when the labels are gone, and whether the collapsed width leaves the content area
sensible on a real screen rather than in jsdom, which has no layout at all.

Row 38 is the honest one: the rail is the app's most-shared component and it now has two widths, so
every screen has a second appearance nothing has ever looked at.

| # | Drive | Expect | Mode |
|---|---|---|---|
| 34 | **Mock.** Sign in, open a project, navigate two or three screens deep (a case detail). Then click **All projects** in the rail. Then do it again with the **TCMS** wordmark. | Both land on the picker with its cards. Before this slice neither existed and `/` was reachable only by editing the address bar. Confirm **All projects** is marked current there — indicator bar, heavier weight, `aria-current` — and marked on **no other screen**, which is the assertion the unit test pins but cannot show you. | mock |
| 35 | **Mock.** On the picker, confirm the ordering in the rail's top block. | **My work**, then **All projects**, then the **Project** switcher — the last two adjacent on purpose ("all of them", then "this one"). If the switcher has drifted above them, the rail is telling the reader that switching projects changes what `All projects` shows, which is the §83 misstatement one level out. | mock |
| 36 | **Mock.** Click the collapse chevron at the bottom of the rail. Then hover each icon in turn, and tab through the whole rail with the keyboard. | The rail narrows to 64px with the content reflowing beside it. Every icon shows a **tooltip on hover with the item's label**, and — the part that matters — the tooltip also appears **on keyboard focus**, not only on hover. The group dividers stand in for the Repository/Execution/Project headings, so four icons do not read as one undifferentiated column. The active item still carries its indicator bar. | mock |
| 37 | **Mock.** Collapsed, click the project key button where the switcher was. Then reload the page. Then expand again and reload. | The menu lists every project with its key and name, the current one marked selected, and choosing one navigates. **The collapsed state survives the reload both ways** — this is the whole reason it is written to storage, and it is the one behaviour that is invisible in a single session. Confirm the rail does **not** render expanded for a frame and then snap shut (it is read during the initial state, not in an effect). | mock |
| 38 | **Mock.** Collapsed, walk the app: case list, case detail, plan statistics, board, docs, reports. | **Nothing reflows badly at the wider content area** and no screen assumed a 232px rail. This is the row that justifies the slice's size: the rail is on every screen, so a second width is a second appearance for all of them, and the DataGrid pages are where a width change is most likely to show. | mock |
| 39 | **Live.** Sign in as a member with **no project grants at all**. | The rail shows **My work** and **All projects** and an **empty** project switcher — not a missing control and not a broken rail. The picker itself says "You don't have access to any project yet". Collapse it: the switcher becomes a folder icon rather than a key, because there is no project to name. **Unobservable in mock mode**, whose fixture user holds everything. | **live only** |

---

## §92 — saved report definitions (R5, backend)

**Five rows, and the numbering skips nothing** — §92's rows follow §91's because a concurrent strand
took that number while this slice was in flight (see `docs/handoff/92-*.md` §0).

**This slice has no screen.** R5 is a server surface and the Reports page still has a definitions
panel with no Save control, so rows 40–42 are driven from the **browser console** in mock mode and
rows 43–44 are live. They are queued rather than optional: the mock now stores definitions and the
next frontend slice will be built against it, which is exactly the arrangement §81 and §87 both
found wrong after the fact.

| # | Drive | Expect | Mode |
|---|---|---|---|
| 40 | **Mock.** From the console on a project's Reports page: `POST`, then `GET`, then `POST /generate` with only the id and a format. <br>`const p = location.pathname.split('/')[2];`<br>`const h = {'Content-Type':'application/json', Authorization:'Bearer '+JSON.parse(localStorage.getItem('tcms.session')).token};`<br>`const d = await (await fetch(`/api/v1/projects/${p}/reports`,{method:'POST',headers:h,body:JSON.stringify({type:'run_summary',name:'Cycle',parameters:{runId:'<a run id>'}})})).json();`<br>`await (await fetch(`/api/v1/projects/${p}/reports`,{headers:h})).json();`<br>`await (await fetch(`/api/v1/projects/${p}/reports/generate`,{method:'POST',headers:h,body:JSON.stringify({definitionId:d.id,format:'MD'})})).json();` | The save answers **201** with `owner` filled; the list answers an array containing it; the generate answers **202** with `type: "run_summary"` — **without the request naming a type**, which is the whole of "one click". Poll the job to `COMPLETED` and download it: the report must be **of the saved run**, which is the evidence the parameters came from the definition and not from an empty object. | mock |
| 41 | **Mock.** Repeat the generate with `{definitionId: d.id, format:'MD', parameters:{note:'this run'}}`, then read the job's `parameters` via the model artifact's header. | The header's **Selection** block lists **both** `runId` (from the definition) and `note` (from the request). A selection showing only `note` is the shallow merge having become a replacement — the defect that renders a perfect report over the wrong scope, and the reason it is worth seeing on a rendered document rather than in a JSON blob. | mock |
| 42 | **Mock.** `DELETE /api/v1/projects/{p}/reports/{d.id}`, then re-poll the job from row 40 and follow its `downloadUrl`. | **204**, the definition is gone from the list, and **the report still downloads**. This is V28's behaviour end to end: deleting a saved report must not delete the history of what it produced. Then re-run the generate with the deleted id — a **400 with a field error on `definitionId`**, not a 404 and not a 500. | mock |
| 43 | **Live.** As two different people on the same project, each save a definition, then each list. | **Each sees only their own.** This is the slice's access rule and it is **structurally unobservable in mock mode**, which signs in as one persona — so "mine" and "the project's" are the same set there and an implementation that dropped the owner filter would pass every mock check. Then have the second person try `DELETE` on the first's id: **404**, and the row survives. | **live only** |
| 44 | **Live.** As a persona holding `report:view` and **not** `report:export` (a grant edit — no built-in role has that shape, and it is the role §87 created for the suite), save a definition, list it, delete it. Then try to generate from it. | The three definition operations **succeed** and the generate is **403**. That pair is the key decision made visible: saving a selection is not exporting a report, and the persona §6e's split exists for must be able to keep its selection. Note that this is the same persona row 31 records the on-screen-view finding against — the two rows read together are the whole state of that split. | **live only** |

## §93 — the plan document (ui)

**Six rows, and this slice has more riding on them than most**: it has **no automated tests of its
own**, so these are currently the only thing that would catch it being broken. All mock mode unless
stated.

| # | Drive | Expect | Mode |
|---|---|---|---|
| 45 | Open a plan → **Document** tab. | The tab exists beside **Progress**, `?tab=document` is in the URL, and the empty state reads "This plan has no document yet" with a **Start the document** button — **not** a global "Something didn't load". The 404-to-null in `usePlanDocument` is the only thing standing between the ordinary first state and an error toast. | mock |
| 46 | Press **Start the document**. | Five headed sections appear in order: Business requirements · Test artefacts (with **Test environments** and **Accounts** beneath it) · Scope · Out of scope · **Test cases**. Bullets under Test cases are **visibly indented at three depths** — that is `level` on a list block, and a flat list means the widening did not reach storage. Every paragraph is a prompt, not content. | mock |
| 47 | Edit a heading's text, click away, reload. | The change persisted. Then check the documentation space (`/docs`): the same page is in the tree, titled `TP-n — <plan name>`. One page, two routes. | mock |
| 48 | Type a title, pick a suite, press **Add case**. Then do it **twice more without reloading**. | Three DIFFERENT cases exist in the case list, each `DRAFT`, and three bullets appear under Test cases each carrying a chip with its own `TC-` key. **Three identical chips pointing at one case is the idempotency-key defect** `useCreateDraftCase` exists to avoid, and it is invisible on a single add. | mock |
| 49 | Click a chip's × , then click the chip body. | The × removes the **link** and leaves the case in the repository (check the case list). The chip body navigates to the case. Two affordances on one control and only one is destructive — if the × deleted the case, that is the row that finds it. | mock |
| 50 | Rename the **Test cases** heading to something else, then add a case. | The bullet lands at the **end of the document** and an info alert says so. It must not vanish and must not throw. | mock |

## §94 — writing steps (phases 2–3)

**Eleven rows (51–61), none run.** This is the slice that changes how a test case is *written*, so
it is almost entirely gesture and timing — the two things no gate here can observe. Three of them
(55, 57, 58) are about a state that only exists because the trailing blank row is now permanent,
which is the defect class this slice introduced deliberately and paid for in the schema.

**Two are live only, and row 59 is the one that matters most**: the corpus is derived from the
project's own cases, and mock mode's fixtures give every structured case the *same four steps*, so
ranking, soft-delete exclusion and project scoping are all unobservable there by construction.

| # | Drive | Expect | Mode |
|---|---|---|---|
| 51 | Open a case for editing, then **New test case**. | Both open with the step list ending in a **blank row**, and the new case opens on exactly one. There is **no "Add step" button** anywhere. Before this slice a new case opened on zero step rows. | mock |
| 52 | In the last row's Action, type one word. Then keep typing. Then type in the new last row. | A blank row appears **after the first keystroke** and **exactly one** — not one per character. The caret **stays where you are typing**: if focus jumps to the new row, `shouldFocus` has come back. Repeat with a **paste** rather than typing: still exactly one new row. | mock |
| 53 | Type a fragment of a step the project already uses (in mock mode, "Refund"). | A popup lists suggestions, each with the action, a **"n cases"** count, and the test data → expected result beneath. It appears **after you stop typing**, not per keystroke — watch the network panel: one request for the word, not one per letter. | mock |
| 54 | Pick a suggestion that has test data and an expected result. Then pick one that has **neither** (mock: "Refund it twice") into a row where you had already typed test data. | The first fills **all three** fields. The second **clears** the test data you had typed rather than leaving it attached to a step it was never written for. Then edit the action: nothing else on the screen changes — a pick is a copy, not a link, and no "Shared" chip appears. | mock |
| 55 | On a **new** case, fill in the title and suite, touch no step, and press **Create case**. | Refused, with **"Add at least one step"** rendered in the step panel. Two things are being checked at once: that the permanently-blank row no longer satisfies the rule, and that the message is **visible** — it was computed and rendered nothing at all before this slice, so the form simply did not respond to the button. | mock |
| 56 | Write one step with **leading and trailing spaces** in all three fields, leave the trailing row blank, save, and reopen the case. | One step, all three values **trimmed**. No empty numbered step at the end. Then check the case's steps on the **detail** page — an empty step there is what a tester is asked to perform. | mock |
| 57 | Type into the trailing row, then clear it back to empty (or to spaces), then save. | The row is dropped. **Whitespace is not a step**: a step whose action is `"  "` is accepted by the API, renders forever as an empty line, and cannot be told from a rendering bug. | mock |
| 58 | Remove the **trailing blank row** with its bin button. | A blank row is still there afterwards. With no Add button, removing the last row would otherwise be a dead end with nothing on screen saying so. | mock |
| 59 | **Live.** On a project with real content, type a fragment matching a step used by several cases; check the counts. Then soft-delete a case that uses the top suggestion and type the fragment again. Then repeat the fragment on a **second project**. | The counts are **distinct cases**, so a case repeating a step twice adds one and not two. The deleted case's usage **drops out** of the count. The second project offers **its own** steps and none of the first's. All three are structurally unobservable in mock mode — the fixtures give every structured case the same four steps and `DELETE` removes the row entirely. | **live only** |
| 60 | Grab a step by its handle and drop it two places down. Then drop one **outside** the list, and drop one back **where it started**. Then do the same with the keyboard: tab to a handle, Space, arrows, Space. | The step moves, and the **numbering renumbers** while the text stays with its row. The two null drops change nothing **and leave the form un-dirty** — on an edit form, watch that **Save stays disabled** (it says "Nothing has changed yet"): a `move(i, i)` would offer to write a new version of an unchanged case. The ▲▼ buttons are still there and still work. | mock |
| 61 | Drag a step in a case that has **evidence or results** against it (an executed case, live). | The steps reorder and the **evidence stays with its step**, not with the position. `step_result` FKs to `test_step.id`, so this is expected — the row exists because if it were ever false, it would be discovered by a tester reading someone else's evidence under the wrong step. | **live only** |

## §95 — conditions get a writer (phases 1–3)

Built and unverified. Rows written per §65; **none of them run.** Rows 68 and 69 are **live-only**
and they are the ones carrying the slice's real risk — mock mode has one project, so the two
refusals that matter most (a sibling project's condition, and a suite attachment's true reach) are
structurally unreachable in it.

| # | Drive | Expect |
|---|---|---|
| 62 | Open a case for editing. Attach a precondition from the picker, then attach a second, then save and reopen. | Both are there, **in the order they were attached**. Position is the content — a condition list is a procedure, and the payload's order is the only way an author can express it. |
| 63 | Attach one condition, then open the picker again. | The one already attached is **not offered**. Attaching the same entry twice in one list is a row with no meaning; the server collapses it silently, and removing it from the picker is the honest version of that. |
| 64 | Attach the same condition as **both** a pre- and a postcondition. | Accepted, and both survive a save-and-reopen. Setup that is also cleanup ("reset the payment data") is the canonical case, and the two lists are validated independently. |
| 65 | On a case whose **folder** carries conditions, open the editor. Then save without touching the panel, and reopen. | The inherited rows render, **named with their folder**, with no remove / reorder / drag affordance — and after the save they are **still inherited**, not converted into the case's own. **This is the slice's most dangerous defect**: an own row and an inherited row look almost identical, so an editor that seeded them into the form would attach the folder's conditions directly to every case anybody edits, permanently and invisibly. |
| 66 | Detach the **last** remaining condition and save. | It is gone after a reload. Absent and empty are different instructions to the server — absent means "leave them alone", so a client that omitted the empty list would report success and change nothing. |
| 67 | Reorder conditions with the ▲▼ buttons, then with a **drag**, then with the **keyboard** (tab to the handle, Space, arrows, Space). | All three paths reorder and all three produce the same saved order. The ▲▼ path is the screen-reader and touch route and is deliberately kept beside the drag handle (§94's rule). |
| 68 | **Live.** Edit a condition attached to several cases: read the impact line, confirm, then open a **different** case that attaches it. | The impact line names the condition and gives **two separate figures** — cases attaching it directly, and suites attaching it — and never a single blended number. After the save the other case shows the **new** text: the attachment is a reference and the edit propagates with no prompt. |
| 69 | **Live.** With the edit dialog open on a condition, have somebody else change that condition, then save. | A **409** in the dialog saying to reload and reapply — not a toast, and not a silent overwrite. `condition.lock_version` was mapped for the first time in §95 *with* `@Version`; §74 found three entities where the plain-column spelling makes the precondition unable to fail, and this is the check that would notice. |
| 70 | Create a condition from inside the editor (the **+** beside the picker). | It is created, **attached to the list it was created from**, and its title renders immediately — not "Unknown condition". The library lists are cached per search term, so the entry is not resolvable from a query until one refetches; leaving the author to find it in the picker they just left is the workflow defect. |
| 71 | Try to save a condition with a blank title, then with an all-blank body. | Both refused, with the message on the field. The all-blank body matters more than it looks: an empty condition is a picker row that attaches nothing and cannot be told apart from one that failed to load — and it would appear in every case attaching the entry. |
| 72 | Open the editor as a user **without `library:manage`**. | Attach, detach and reorder still work — attaching is a case edit, not a library edit — while **create and edit are refused**. Check the refusal is legible rather than a bare 403. *(Live-ish: mock mode holds every key.)* |
| 73 | Break the condition endpoint (`tcmsFaults.only('/conditions')`, then `tcmsFaults.status(503, 30)`) and open the editor. | The panel shows its own warning and **the rest of the case still saves**. A failed library read must not take the editor down with it — the failure is scoped to the region that depends on it. |
