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
| 14 | Live only: ask somebody, then sign in as them | Nothing surfaces on their board or anywhere outside this panel. **That is correct today and is the next slice**: §6l's *"Review requested → card for each reviewer"* needs the queue this table now makes possible. Recorded so the absence is not read as a defect. | **live only** — needs two accounts |

**Not exercisable here:** asking a **sibling company's** account or a sibling company's team. Both
are refused as field errors and neither is reachable from any picker in either mode — the check is
the backend queue's, and the rows exist there.

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
