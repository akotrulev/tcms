# §28. Backend M1, slice 7 — the first write, and why §27's own next-step list was wrong (2026-07-31)

Run through the **`be-java`** skill (agent definition + `database-code`), **Mode B — extend**.
Plan: `docs/plans/2026-07-31-case-update-first-write.md`.

### The slice changed before it started, and the orientation pass is why

§27 named `/cases/{caseId}/versions` and `/history` as next. All three of its premises were wrong:

| §27's claim | What the code said |
|---|---|
| `/versions` is a read the SPA is waiting for | **`useCaseVersions` has no consumer.** A dead hook. So is `useSharedSteps` (item 2). |
| `/versions` is "the first thing that needs `test_case_version`" | True, and that was the problem: **nothing wrote `test_case_version`** — no entity, no seed, no writer. It would have answered `[]` for every case in the product while §27's detail endpoint already served `currentVersionNo` from `test_case.current_version_no` (`NOT NULL DEFAULT 1`). One case reading **"v7" on its detail page and "no versions" on its version list** — §5-defect-3's class, two screens of one case disagreeing. |
| `/history` is a `repository` read | Tagged `[repository, reporting]`, and `entries` needs `test_execution` + `test_run` + `case_status` + `project_configuration` + defect links + evidence counts — **the whole `execution` module, which does not exist.** |

So §27's item 3 came first. **`PATCH /projects/{projectId}/cases/{caseId}` — the first write in the
product.** `/versions` is now a small follow-on with real data behind it.

**The lesson, and it is new:** a "next" list written at the end of a slice is written from what the
*contract* still owes, not from what the *code* can support. Both dead hooks and the empty table were
one grep away. **Re-derive the next slice from the code at the start of a session, not from the last
session's closing paragraph.**

| Path | Purpose |
|---|---|
| `project/GateResult` | **The write gate's tri-state.** A read's `Optional` cannot carry 403. |
| `project/ProjectAccess · ProjectService.writeGranted` | `canDo(user, project, permission)`, and the write gate beside `readVisible`. |
| `repository/CaseUpdateRequest · CaseEdit` | The wire shape, and the validated/normalized edit — one place every rule lives. |
| `repository/CaseSteps` | **The reconcile.** Updates in place; never replaces. |
| `repository/CaseVersions · TestCaseVersion · …Repository` | §6j: the snapshot, the hash, skip-when-unchanged. |
| `tenancy/AuditEvent · AuditEventRepository` | The client's own trail — first writer. |
| `repository/CaseUpdateService · ApiProblem` | Five outcomes, and the error envelope. |
| `repository/CaseUpdateEndpointTest` | **New, 58 tests.** |

### The gate a write needs is not the gate a read needs, and every role holds the read one

Every read in `repository` borrows `readVisible`, which asks **`project:read` — held by all six
built-in roles**, because that is what makes a granted project visible. A write gated the same way is
**a write a Viewer may perform**, and a Viewer is specifically the client's own stakeholder, invited
to read coverage without touching it (roles spec §A7). Nothing in a suite whose callers are all leads
would show it. `writeGranted` names its permission, so calling the wrong gate is visible in the diff.

**403 exists here, and it does not weaken the 404-not-403 rule.** That rule conceals *existence* from
a caller who cannot see the thing; 403 is reached only *after* the read gate has passed, when the
caller is looking at the case in the editor. A 404 there would tell them their case had been deleted
— false, alarming, and about the one thing a test repository must never appear to have done. An
unreachable project or a case in a sibling project still answers **404, 0 bytes**. The gate checks
visibility **first** for exactly this reason: checking the permission first makes 403-vs-404 an oracle
for what exists, and the mutation that reorders them is killed.

### `case:edit` is NOT `OWN`-qualified — a contradiction inside the authoritative spec

The permission table says plain ✓ for Test Engineer; **§A5 listed `case:edit` among the
`OWN`-qualified**; §A3's own principle one screen up — *"a Test Engineer is never blocked from
producing, only from unmaking someone else's work"* — names `case:delete` and `evidence:delete` and
deliberately not this. Two of three readings agree and the third contradicts the principle it sits
under. **§A5 is corrected, struck through with the reasoning**, per the working agreement. Editing is
producing; deleting is unmaking. The `OWN` qualifier is still owed to `case:delete`.

### Four contract divergences, all found by reading rather than by failing

1. **`If-Match` vs body `lockVersion`.** The contract always specified the header; the SPA and mock
   put it in the body, and it worked because no server read either. **The header wins** — a PATCH body
   states desired state, and a precondition as a field is a value on the input schema that must never
   be persisted. Both sides adapted in this commit.
2. **No 403 in the contract.** Added `responses.Forbidden`, with the reasoning above in its
   description.
3. **`Problem` had no `errors` field** while the client has read `body.errors` since Phase 1 and the
   mock has emitted it since. Now declared, keyed by input-schema field name.
4. **`TestCase`'s `allOf` branch had no `required` array** — handoff §6 lesson 3, again, and this time
   `tsc` found it the moment a consumer needed `lockVersion`. Fixed for all fourteen fields;
   `relatedDocuments` stays out because the server omits it (§27).

### Steps are reconciled, and the alternative is the most expensive defect in the slice

`test_step.id` is stable and `step_result` references it, so **a step that keeps its id keeps every
result and every piece of evidence** (§3 lesson 4). Delete-and-reinsert is four lines shorter and
reassigns every id on every save: nothing fails, the screen looks right, and every past result is
orphaned from the action it described. **The steps come out correct either way — only the ids
differ**, so the ids are what the tests assert.

**A limitation recorded rather than guarded:** `step_result.step_id` has **no `ON DELETE` clause**, so
deleting a step that has ever been executed will raise a foreign-key violation. Every delete succeeds
today because nothing writes `test_execution`. The moment execution ships, removing a step from a case
somebody ran becomes a 500. The decision it needs is a product one — soft-delete the step, refuse the
edit, or detach the result — and it belongs to the slice that can write a result to prove the choice.

### The hash is over the authored edit, not the rendered case — and that is not interchangeable

`snapshot` answers *what did this case say* (the full resolved case, as `GET` served it — resolved so
a shared condition edited today cannot change what a tester was told last March). `content_hash`
answers *did the author change anything*, and is taken over **`CaseEdit`**: the normalized set of
fields a save can change.

Hashing the snapshot is the plausible shortcut and it breaks the property the hash exists for. The
snapshot carries values that move on their own — the owner's display name, health signals derived
from run results, `updatedAt` — so **a colleague renaming themselves, or a nightly run turning a case
red, would make the next save look like an edit.** The mutation that does this is killed. Hashing
`CaseEdit` also makes the coupling self-maintaining: when a later slice makes conditions editable they
join `CaseEdit` to be validated at all, and join the hash in the same commit.

**The first version of every existing case is v2, and the v1 gap is deliberate.** Every case predates
this path, so its first edit writes v2 and its v1 is permanently absent. Labelling the edit v1 would
claim the edited text was the original; backfilling a synthetic v1 from the current row would assert
that the pre-edit content was the post-edit content, in the one table whose purpose is that past
claims stay true. History began when it began.

### Six contract fields are refused with a 400, not accepted and dropped

`ownerId`, `classificationValueIds`, `tags`, `automationTags`, `preconditions`, `postconditions` are
on `TestCaseInput`, none is sent by any client, and each needs a write path this slice does not build.
**Absent means unchanged (correct PATCH semantics); present means 400.** Accepting and ignoring them
is a save that reports success and discards what the user typed — the failure the mock's own handler
comment names. A later slice deletes the boundary in one place. Mirrored in the mock so mock mode
cannot pass a payload live mode rejects.

### Mutation-verified — eighteen mutations, one survivor, and chasing it found a real defect

| Mutation | Result |
|---|---|
| Write gate ignores the permission (a Viewer may write) | **Killed** |
| Permission checked before visibility (403 leaks existence to persona 3) | **Killed** |
| Steps replaced instead of updated (every id reassigned) | **Killed** |
| `If-Match` check removed (stale editor overwrites) | **Killed** |
| A version written on every save (hash ignored) | **Killed** |
| No version ever written | **Killed** |
| New version reuses the stored number instead of `+1` | **Killed** |
| Hash taken over the rendered snapshot instead of the authored edit | **Killed** |
| Audit row not written | **Killed** |
| Automation-key check no longer excludes the case being edited | **Killed** |
| The six unsupported fields accepted and dropped | **Killed** |
| Case-in-project predicate dropped | **Killed** |
| Blank optional text stored as `''` instead of null | **Killed** |
| Gherkin save keeps the structured steps | **Killed** |
| `advanceVersion` never called | **Killed** |
| A foreign step id adopted instead of refused | **Killed** |
| Step position not rewritten from payload order | **Killed** |
| `saveAndFlush` → `save` | **Survived — and it exposed a defect in my own reasoning** |

**The survivor was informative in the way §27's was not.** The class javadoc claimed the early flush
turns a write race into a 409 "rather than a 500 raised at commit" — and **nothing converted
`OptimisticLockingFailureException` at all**, so it would have been a 500 either way. Fixed with
`CaseController.onWriteRace`, which has to be an `@ExceptionHandler` rather than a `catch`: by the
time the exception exists the transaction is rollback-only, so returning a tidy outcome from inside it
fails again at commit with `UnexpectedRollbackException`. The flush now stands on the one property it
genuinely has — a constraint fails at its cause, before the version and audit rows are written on top
of it — and **that path remains untested**, because reaching it needs two interleaved transactions and
this suite has no mechanism for that. Recorded rather than implied.

### Gate evidence — run, not just built

`./gradlew build` clean with `-Werror`, **434 tests** (376 + 58). `npm run build` clean,
`npm run lint` 0 warnings.

Then **driven live** against `docker compose up`, two provisioned companies, and a seeded Viewer:

| Assertion | Result |
|---|---|
| First real edit | **v7 → v8**, lock 3 → 4 |
| Identical payload re-saved | **still v8**, lock 4 → 5 — the §6j skip, live |
| Version rows | 8 and 9 only, distinct hashes, author and comment recorded, `pinned` false |
| Audit rows | one per save incl. the no-ops, `{"versionNo":N,"newVersion":true/false}` |
| Step reconcile | reorder **preserved all three ids**, content edited in place, omitted step deleted |
| Stale `If-Match` | **409**; correct → 200; quoted `"5"` → 200; `banana` → **400**; `W/"3"` → 400; absent → 200 |
| Blank title / refused field | **400**, `errors` keyed on `title` / on `ownerId` **and** `tags` together |
| **Viewer: GET then PATCH** | **200 then 403**, case untouched, body names no permission key |
| Other client's admin | **404, 0 bytes** |
| Unauthenticated | **401** |

And **in the browser**, live mode: the editor loaded the real case showing "v7 → v8", a title edit
saved with the toast **"Saved TC-1 as v8"**, the detail page rendered the new title at v8 with §6f's
inherited markers intact, and a second edit produced v9 — both audited, both versioned, console clean.

**What the browser did *not* verify, stated plainly:** the 409 rendering in the editor's own UI. Two
attempts to create a stale editor failed because TanStack Query keeps the editor's copy fresh
(refetch on focus/mount), which is the app working correctly rather than a gap — so a stale save could
not be staged from the outside. The 409 is covered by eight backend tests and by curl above; its
**UI presentation is unproven**, and the SPA's conflict copy has only ever been exercised against the
mock.

### Deliberately not done

- **No POST (create) and no DELETE.** Create is the harder half — allocating `key`/`key_seq` from the
  company's `key_sequence` row and writing v1 in the same transaction — and DELETE needs the `OWN`
  qualifier that `case:delete` is still owed.
- **`lifecycleStatus` is not editable**, matching `TestCaseInput`: promotion through draft → review →
  approved is the review workflow's (§6g), and a status an ordinary edit can set is an approval anyone
  grants themselves by saving.
- **A case cannot move between projects** — `projectId` is `updatable = false`. Moving one would
  invalidate every suite, classification value and condition attached to it; it is not an edit.
- **`ApiProblem` duplicates `InvitationController.ProblemBody`.** The right home is the `shared`
  module the architecture lists and that does not exist yet; creating it means a package, a Modulith
  declaration, and an entry in every module's `allowedDependencies`. Visible duplication beat a quiet
  module.
- **The bulk endpoint** — same table, same validation, and a `bulk_operation_id` on `audit_event`
  waiting for it.

### Two findings that are not this slice's to fix

1. **No `created_by` column anywhere in the schema has an FK to `app_user`** — 23 columns, zero
   references, while §3 lesson 10 states this "already holds". It does not. Fixing it is its own
   migration and needs an `ON DELETE` decision under "suspend, never delete".
2. **The `/history` response's inline object has no `required` array** — lesson §6.3 again.
   `CaseHistoryResponse` is hand-written in `queries.ts` to work around it, and it should be fixed
   when `/history` is built.

### Next

1. **`GET /projects/{projectId}/cases/{caseId}/versions`** — now worth serving: it has real rows, and
   it is what makes the v2-not-v1 gap visible to a reader. Still needs a consumer in the SPA.
2. **`POST /projects/{projectId}/cases`** — create, with `key_sequence` allocation and v1 written in
   the same transaction. The other half of authoring, and it makes `/versions` gapless for new cases.
3. **The `execution` module**, which unblocks `/history` — the one screen with a real link that 404s
   live — and turns `pinned` into something that means anything.
4. Frontend **Phase 4 (Vitest + RTL)** — unchanged, still unblocked. `CaseEditorPage`'s conflict copy
   is now a first-class target, since live mode cannot reach it.
5. The evaluator's cache, the `OWN` qualifier (owed to `case:delete`), an RLS-disabled lane for §13's
   surviving mutation, and the `case_status.color` migration alongside the status editor.
