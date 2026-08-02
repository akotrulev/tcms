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
