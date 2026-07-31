# §30. Backend M1, slice 9 — suite writes, and the first slice that shipped both halves (2026-07-31)

Run through the **`be-java`** skill (Mode B — extend) and then the **`fe-react`** skill (Mode B,
re-entering Phase 2). Plan: `docs/plans/2026-07-31-suite-writes.md`.

### The orientation pass changed the slice's SHAPE, not its subject

§29's list named suite writes first and that survived the re-derivation — a provisioned company had
no folders, and a case requires one, so §29's own live pass had to insert a suite with hand-written
SQL. What the pass *added* was the half the list omitted: **`useCreateSuite` had zero consumers**,
exactly like `useCaseVersions` before it. A backend-only slice would have left it dead for a fourth
consecutive slice. So this is the first slice to ship the endpoint and its screen together, and the
"which of these has a consumer" question is what produced that.

`useCaseVersions` is **still** dead. Fourth slice running.

| Path | Purpose |
|---|---|
| `repository/SuiteWriteService` | Create / rename / delete, sealed `Outcome`, two permissions. |
| `repository/SuiteEdit` | Validation, with `validateCreate` and `validateRename` genuinely different. |
| `repository/SuiteVersions` · `SuiteVersion` · `SuiteVersionRepository` | v1 composition version at create. |
| `repository/Suite` | Factory + `applyEdit` + `softDelete`; `key_seq` and the audit columns now mapped. |
| `repository/SuiteWriteEndpointTest` | **New, 56 tests.** |
| `features/cases/SuiteDialogs.tsx` | Create / rename / delete dialogs — `useCreateSuite`'s first consumer. |

### The `ltree` path label is the key sequence, and that is the load-bearing decision

`V2` ships `tcms_ltree_label()`, which slugs a *name*, and it is the wrong tool for a suite.
Classification values are seeded once and never renamed; **suites are renamed constantly, and a
name-derived path makes every rename a subtree rewrite** — contradicting `Suite`'s own account,
where reparenting is what rewrites paths. A slug is also not unique (`"UI / E2E"` and `"ui e2e"`
collide under one parent) and can be empty (`"…"` → `''`, not a valid label, failing at insert with
a message about nothing). The label is `s` + `key_seq`: unique by construction, stable across
renames, never empty. **Proven over the wire** — a live rename left the path at `s2`.

### Two permissions, because collapsing them reverts a boundary the spec fixed

`suite:manage` (create, rename) is Test Engineer and above; `suite:delete` is leads only. The roles
spec lists that split as a boundary that was *previously wrong and got corrected*, so gating both on
`suite:manage` is not a simplification, it is the reversion. One test carries it, and it is the only
one that goes red for that mutation.

### What this slice REFUSES, and why the refusal is the feature

1. **Moving.** A `PATCH` whose `parentId` differs from the stored one is a **400 naming the reason**,
   not a silently dropped field. Reparenting rewrites every descendant's path and needs cycle
   prevention; until that exists the only honest answers are "done" and "refused", and dropping the
   field is the third one — where a client shows the folder in its new place, reloads, and finds it
   back, with the server having reported success.
2. **Deleting anything non-empty.** `suite:delete` is specced "folder + subtree" and the cascade is
   deliberately absent: a folder delete that soft-deletes the cases beneath it unmakes *other
   people's* work through a permission whose sibling, `case:delete`, carries an `OWN` qualifier
   precisely to stop that — and with no preview. **409 naming the counts.** The cascade belongs to a
   slice that can show its work; `BulkResult` already has the shape.

### A rename writes NO version, and that follows from the schema rather than from taste

`V1`'s comment on `suite_version.snapshot` defines a suite version as **composition** (member cases,
order, nesting) and explicitly *not* content, "otherwise every case edit bumps every ancestor suite".
A name is content. So create writes v1 (§29's precedent: a row created after the write path exists
must not be born with the same gap as the rows predating it) and **rename writes nothing** — a
bump there is the start of the spiral the comment exists to refuse, and it looks like diligence.
Case-move, reorder and reparent owe the later bumps; each is named in `SuiteVersions`' header.

### Mutation-verified — fourteen mutations, and the one survivor was a missing FIXTURE

| Mutation | Result |
|---|---|
| Delete gated on `suite:manage` · create gated on `project:read` | **Killed** |
| Subtree count drops its project predicate | **Killed** |
| Path slugged from the name · move silently ignored on PATCH | **Killed** |
| Key allocated before validation · v1 written as v2 | **Killed** |
| `findLiveInProject` drops `deletedAt` · drops `projectId` | **Killed** |
| Blank description stored as-is · `If-Match` ignored · parent unchecked · audit detail unescaped | **Killed** |
| **Emptiness check ignores child suites** | **Survived — no fixture could see it** |

The survivor is the instructive one. Every non-empty folder in the fixture also held **cases**
somewhere beneath, so the subtree case count refused them anyway — and the counts still flowed into
the message, so even the wording assertion passed. Fixed by adding a parent whose only occupant is
an **empty** child, where the case count is zero and only the child check stands in the way. §29's
lesson in a new costume: a test that exercises the right code path can still be structurally unable
to observe the defect, and only the mutation says which kind you wrote.

### Gate evidence — run, not just built

`./gradlew build` clean with `-Werror`, **547 tests** (491 + 56). `npm run build` clean,
`npm run lint` 0 warnings.

**Live**, against `docker compose up` and a real session:

| Assertion | Result |
|---|---|
| Create from the tree | **TS-2**, path **`s2`** (not a slug of the name), `DRAFT`, creator stamped |
| Rows written | `suite_version` v1 with `{"caseIds": [], "parentId": null}` + a 64-char hash; `audit_event` `suite.created` |
| Rename | name changed, `lock_version` 0→1 (so `If-Match` was read), **path still `s2`**, still exactly one version row, `suite.renamed` |
| Delete a folder holding 4 cases | **409 in the dialog**: "This suite still holds 4 cases. Move or delete them first." Folder kept |
| Delete the empty folder | **204**, row survives with `deleted_at` set, `suite.deleted` |
| Create inside a collapsed folder | child at path **`payments.s3`**, parent auto-expanded so it is visible |

### Three defects the browser found and the gates could not

1. **An empty-name submit displayed nothing at all.** The dialog's `useEffect` called
   `reset(defaults)` with `defaults` in its dependency array — and `defaults` is an object literal
   built by the caller on every render. A failed submit set the field errors, React re-rendered, the
   identity changed, the effect re-ran, and `reset()` wiped the errors before anyone saw them. The
   reset was not needed at all: each dialog is conditionally rendered, so it mounts fresh and
   `defaultValues` already carries the right values. **Handoff lesson 6 exactly** — correct types,
   wrong runtime graph, both gates green.
2. **The server's actionable 409 never reached the user.** `describeError` collapsed *every* 409 to
   the lost-update copy ("Reload to see their version"), throwing away "This suite still holds 4
   cases. Move or delete them first." — and telling the user to reload, which changes nothing. The
   two 409s want opposite advice, so `ApiError` now carries RFC-7807 `title` and `describeError`
   reads it to tell them apart. **`ApiProblem` had already made this distinction server-side**
   (`stale()` vs `conflict(title, detail)`); the client threw it away one layer later.
3. **A MUI X Tree View warning my own slice made routine.** `defaultExpandedItems` is an
   *uncontrolled* default, and every write now invalidates the suite query, so it changed on every
   create/rename/delete. Made controlled — which also fixed the behaviour underneath: creating a
   suite inside a collapsed folder used to leave it invisible.

### The 500 the live pass hit, which was NOT this slice's code

The first live create answered **500 — `duplicate key value violates unique constraint
uq_suite_key`**. The allocator was right and the *data* was wrong: **every earlier slice's live pass
inserted its suite with hand-written SQL, bypassing `key_sequence`**, so all six companies had a
`TS-1` row and a counter still reading 1. Repaired with
`UPDATE key_sequence SET next_value = MAX(key_seq)+1`, which is the documented repair `V5`'s header
describes. Recorded rather than absorbed, because anyone following the handoff's own live-pass
instructions will hit it, and because it is the argument for `provision-company` being the only path
that creates rows.

### The rule this slice could not follow, and why it was not half-followed

`ui-rules` requires **disable-don't-hide**: an action the backend will refuse should render disabled
with a why-tooltip, never render-then-reject. **The SPA cannot do that for any of these actions** —
`CurrentUser` carries `isSystemAdmin` and teams and *no permission list*, so nothing tells the client
whether this user holds `suite:manage` on this project. A Viewer therefore sees Delete, clicks it,
and gets a 403 rendered in the dialog.

This is a **pre-existing class, not this feature's** — "New case" has behaved this way since Phase 2.
The fix is one contract change (the caller's effective permissions on a project) that closes it
everywhere at once. Gating suites alone would leave the rest of the app inconsistent and make the
eventual fix touch two patterns instead of one, which is precisely the "second implementation" the
working agreement warns about. **It is the highest-value frontend item outstanding.**

### The mock was aligned, because it was proving properties live mode lacked

Three divergences, all the `If-Match`-in-the-body family: the mock read `lockVersion` from the
**body**, slugged `path` from the **name**, accepted a parent from outside the project, applied
`lifecycleStatus`, and had **no DELETE at all**. All corrected.

### Deliberately not done

- **Moving/reparenting and reordering** — the endpoint *and* its UI belong to one slice, since a move
  endpoint with no drag-and-drop would be the dead-hook trap from the other direction.
- **Cascading delete** — owed to a slice that can preview what it will touch.
- **Composition version bumps** on case membership changes — owed to the case-move and case-delete
  slices, named in `SuiteVersions`.
- `ClassificationValue.path` has the same `ltree` mapping and **not** the `@ColumnTransformer` that
  `Suite.path` needed; it is unbroken only because nothing in Java inserts one yet, and it will fail
  identically on the day something does.

### Next

1. **The caller's effective permissions on a project, in the contract** — the disable-don't-hide gap
   above. One change, closes the class everywhere, and every future affordance needs it.
2. **`POST/PATCH/DELETE` on cases' remaining verb — `DELETE /cases/{caseId}`** — still needs the `OWN`
   qualifier `case:delete` is owed, and `useDeleteCase` **does** have a consumer, unlike `/versions`.
3. **`GET …/cases/{caseId}/versions`** — real rows and a v1 to show, and **still no SPA consumer after
   four slices**. Only worth doing in the same slice as the detail page's history panel.
4. **The `execution` module** — unblocks `/history`, the one screen with a live link that 404s.
5. Frontend **Phase 4 (Vitest + RTL)** — unchanged, still unblocked, and `SuiteEdit`-style pure logic
   plus `describeError`'s now-branching 409 are good first targets.
