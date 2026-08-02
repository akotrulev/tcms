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
