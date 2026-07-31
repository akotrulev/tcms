# §29. Backend M1, slice 8 — case create, the key allocator, and v1 (2026-07-31)

Run through the **`be-java`** skill (agent definition + `database-code`), **Mode B — extend**.
Plan: `docs/plans/2026-07-31-case-create-second-write.md`.

### §28's lesson applied to §28's own next list, and it changed the slice again

§28 named `/versions` first. One grep: **`useCaseVersions` still has zero consumers** —
`CaseDetailPage` does not call it — which is the same dead-hook trap §28 caught in §27, one slice
later. `useCreateCase` *is* wired to a real screen (`CaseEditorPage mode="create"`, route
`cases/new`, reached from two places on `CaseListPage`), so item 2 came first.

**This is now twice in a row that the closing "Next" list was wrong in the same way**, which makes it
a property of writing that list at the end of a slice rather than a mistake. The orientation pass is
not optional and its first question is *which of these has a consumer*.

| Path | Purpose |
|---|---|
| `tenancy/DisplayKeys` | **The one company-scoped key allocator.** One statement, range-capable. |
| `repository/CaseCreateService` | Create orchestration; five outcomes incl. `Replayed`. |
| `repository/TestCase.create` | The factory §28 said the create endpoint would bring. |
| `repository/CaseVersions.initial` | v1, explicitly — see below. |
| `V11__test_case_creation_idempotency.sql` | `created_idempotency_key` + its partial unique index. |
| `tenancy/DisplayKeyAllocationTest` · `repository/CaseCreateEndpointTest` | **New, 57 tests.** |

### The allocator is in `tenancy`, and it allocates a range on day one

Five entity types share `key_sequence` (`TC- TS- TP- TR- DOC-`), so an allocator in `repository`
makes `documentation` import it to number a page — and makes a second copy the obvious alternative.
Two copies of this statement is two chances to reach for `MAX(key_seq) + 1`, which is what `V1`'s own
table comment and `V5`'s header both exist to refuse: concurrent creation reads the same maximum and
issues the same key, failing under exactly the load a client importing a suite produces and never in
a test.

**A range, though today's only caller asks for one.** ERD §5 and plan §6t both say bulk import and CI
quarantine auto-create *must* reserve N in one statement. The SQL is byte-identical for n=1 and
n=500, so the range API costs nothing now and means the bulk slice has nothing to invent.

**Zero rows throws**, naming `provision-company`. An `UPDATE … RETURNING` that matches nothing is an
empty result, not an error — precisely the shape `V5` says "invites the wrong repair".

### v1 is written explicitly, and `decide()` would have got it wrong

`CaseVersions.decide` answers `currentVersionNo + 1`, and a fresh row's counter is `NOT NULL DEFAULT
1` — so a created case would take **v2** and its v1 would never exist. That gap is honest for cases
predating the write path and indefensible for one created after it: every case in the product would
have a missing first version, looking like the same historical accident. `initial(CaseEdit)` returns
`Decision(1, true, hash)` and reuses the same hash function, so a create followed by an unchanged
save still writes no v2.

### Idempotency is implemented, because ignoring the header was the §28 failure one endpoint later

The SPA has sent `Idempotency-Key` on this endpoint since Phase 1 — one key per mounted editor, with
a comment explaining that per-attempt keys are the mistake that makes the header look right and do
nothing — and **the mock has always honoured it while no server read it**. Mock mode was proving a
property live mode did not have. Same class as the `If-Match`-in-the-body divergence.

Stored as a column on `test_case` rather than in a general request table: the key identifies the
creation of one row, so retention is automatic and there is no new RLS policy or exception-list
decision. The honest cost is that a second create endpoint adds its own column; generalising waits
for a second real shape. Unique on **`(company_id, project_id, created_idempotency_key)`** with the
lookup naming the same three — a constraint wider than its lookup is a validated request that 500s on
insert, which `existsByAutomationKeyOtherThan`'s header already documents from the other direction.
**Replay answers 200, a fresh create 201**, matching the mock and letting a client tell "I made this"
from "I already had this".

### `case:create`, and the one qualifier that is NOT implemented

Viewer does not hold it; the other four human roles do. **Automation Service holds it "quarantine
folder only" and that restriction is not enforced** — there is no quarantine folder in the schema
(`suite` has no marker column, `project_configuration` names no default suite) because the concept
arrives with CI ingestion in M3, and `V3`'s comment already calls it "a service-layer rule: a
permission cannot carry a folder". So that role could currently create in any suite of a granted
project. It is unreachable through a client today — API tokens are M3, the only authenticator is a
password login, so the role can only be attached by hand-written SQL — and inventing a
`suite.is_quarantine` column to gate a caller that cannot yet exist would be a schema change made to
satisfy a test. **Recorded in the service javadoc and owed to the ingestion slice**, beside the `OWN`
qualifier owed to `case:delete`.

### Contract, and the wart left alone

Added: `Idempotency-Key` as a component parameter, `BadRequest` as a component response, and
`400/403/404` + `Location` on the POST (which declared **only 201**). `400` also added to the PATCH,
which has been able to answer it since §28. **Not fixed:** the path-level `parameters` block attaches
every *search* query param to `/cases`, so they apply to `POST` too — a real wart, and unpicking it
touches the list endpoint's generated types, which nothing else in this slice goes near.

### Mutation-verified — twelve mutations, and the one survivor was a bad test

| Mutation | Result |
|---|---|
| Gate asks `case:read` (a Viewer may create) | **Killed** |
| First version numbered by `decide()`, so v1 becomes v2 | **Killed** |
| No version row written at all | **Killed** |
| Key allocated before validation, so a typo burns a number | **Killed** |
| Idempotency lookup skipped entirely | **Killed** |
| Idempotency lookup drops the project predicate | **Killed** |
| Owner left unset · new case created `APPROVED` | **Killed** |
| Audit row not written · automation-key check always free | **Killed** |
| Step positions not rewritten from payload order | **Killed** |
| Blank `Idempotency-Key` stored as-is | **Survived — the test was wrong** |

The survivor sent `"   "` and `""` — **two different blanks, which do not collide even without the
trim**, so the assertion could not fail. Fixed to send the *same* blank twice, then killed. Worth
keeping: a test that exercises the right code path can still be structurally unable to observe the
defect, and only the mutation says which kind you wrote.

### Two id collisions the full suite found and the single-class run could not

`CaseCreateEndpointTest` was written on the `c0…` prefix, already owned by
`ConditionLibraryEndpointTest` — so **its no-grant persona became my granted Engineer** and three of
*its* tests failed. `DisplayKeyAllocationTest` had the same collision with `CaseDetailEndpointTest`'s
`d0…` and passed anyway, by luck. Both moved to free prefixes (`b1…`, `b2…`). This is handoff §19's
defect exactly, in a file whose own header warns about it — the warning does not help unless
something enumerates the prefixes already taken, which one `grep -rhoE` does.

### Gate evidence — run, not just built

`./gradlew build` clean with `-Werror`, **491 tests** (434 + 57). `npm run build` clean,
`npm run lint` 0 warnings.

Then **driven live** against `docker compose up`, a freshly provisioned company and a seeded Viewer:

| Assertion | Result |
|---|---|
| Three creates | **TC-1, TC-2, TC-3**, each v1, `DRAFT`, owner = creator, `Location` set |
| Same `Idempotency-Key` twice | **201 then 200**, same id, no `Location` on the replay |
| Retry with a *drifted* body | **200 with the original case**, not a 400 |
| Rejected payloads | blank title / `ownerId` / foreign `suiteId` / 201-char key → **400**, keyed on the field |
| A refused create | **burns no key** — the next create was TC-1 |
| **Viewer: GET then POST** | **200 then 403**, body names no permission key, nothing written |
| Unreachable project · unauthenticated | **404 with 0 bytes** · **401** |
| Rows | `test_case_version` v1 per case; `audit_event` `case.created` with `{"key":"TC-1",…}`; counter at 4 |

And **in the browser**, live mode: New case → the real suite picker → **"Created TC-4"** at **v1**,
Draft; the editor then read **"Edit TC-4 · v1 → v2"** and saved **"Saved TC-4 as v2"** — the created
case's history is gapless from the UI's own point of view — with TC-4 in the grid and the SPA's own
`Idempotency-Key` stored on the row.

**What the browser did *not* verify, stated plainly:** the retry path through the UI. TanStack Query
resolves the mutation before a second Save is possible, so the duplicate-intent case cannot be staged
from the outside — same shape as §28's unprovable 409. It is covered by seven backend tests and by
curl above.

### One console error, and it is not this slice's

`Cannot update a component (BrowserRouter) while rendering a different component (DataGridRaw)` on
the case list. `CaseGrid.tsx` is untouched by this slice (`git diff` empty, last changed in
`ec0f707`), so it is **pre-existing from the case-list slice** and is recorded rather than absorbed
or silently fixed. It is a React 19 + MUI X DataGrid setState-in-render warning; the page works.

### Deliberately not done

- **No DELETE** — still needs the `OWN` qualifier `case:delete` is owed.
- **A create still cannot make a suite**, so a freshly provisioned project has nowhere to put a case:
  the live pass had to seed one suite by hand. **`suite:manage` is now the smallest thing standing
  between a provisioned company and a usable product**, which it was not before this slice.
- The six refused fields, `lifecycleStatus`, the bulk endpoint, and the `created_by` FK gap (§28
  finding 1) are all unchanged.

### Next

1. **`POST/PATCH/DELETE /projects/{projectId}/suites`** — re-derived, not inherited: a company can now
   be provisioned and given cases only if someone writes a suite row by hand. `suite:manage` is held
   by Test Engineer and above, `suite:delete` by leads, and `DisplayKeys.EntityType.SUITE` is already
   built and tested.
2. **`GET …/cases/{caseId}/versions`** — now has real rows *and* a v1 to show, but **still no SPA
   consumer**; it wants the detail page's history panel in the same slice, or it is another dead hook.
3. **The `execution` module** — unblocks `/history` (the one screen with a live link that 404s) and
   turns `pinned` into something that means anything.
4. Frontend **Phase 4 (Vitest + RTL)** — unchanged, still unblocked.
5. The `OWN` qualifier, the quarantine-folder gate (with M3), the evaluator's cache, and the
   pre-existing DataGrid setState-in-render warning above.
