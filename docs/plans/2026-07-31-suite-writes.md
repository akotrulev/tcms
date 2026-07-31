# Suite writes — create, rename, delete (M1, slice 9)

**Date:** 2026-07-31 · **Branch:** `master` · **Mode:** be-java Mode B (extend) + fe-react Mode B
**Predecessor:** handoff §29 (case create, the key allocator, v1)

## Why this slice, derived from the code rather than inherited

§29's closing list named suite writes first. The orientation pass confirms it rather than trusting
it, and adds the half the list omitted:

| Question | Answer from the code |
|---|---|
| Does the backend serve any suite write? | No — `SuiteController` has one `GET`. |
| Does the SPA have a consumer waiting? | **`useCreateSuite` exists in `api/queries.ts` and has zero consumers.** |
| Is anything else dead? | `useCaseVersions`, still — §29's item 2 was right to doubt itself. |
| Can a provisioned company be used? | No. `tcms_seed_project_defaults` seeds a configuration and the status vocabulary, deliberately **not** a folder, and a case needs a `suiteId`. The §29 live pass had to insert a suite by hand. |

So the slice is the thing standing between `provision-company` and a usable product — and it must
ship **both halves**. A backend-only slice would leave `useCreateSuite` dead for a fourth
consecutive slice, which is the exact trap §27 → §28 → §29 caught three times.

## Scope

### In

1. **`POST /projects/{projectId}/suites`** — create. `suite:manage`.
2. **`PATCH /projects/{projectId}/suites/{suiteId}`** — rename + description. `suite:manage`,
   `If-Match` on `lockVersion`.
3. **`DELETE /projects/{projectId}/suites/{suiteId}`** — soft-delete an **empty** folder.
   `suite:delete`. Not in the contract yet; added there first.
4. **SPA affordance on `SuiteTree`** — New / Rename / Delete, so all three endpoints have a consumer.

### Out, and why — each is refused explicitly, never silently ignored

| Left out | Why | Owed to |
|---|---|---|
| **Move / reparent** | `Suite`'s own javadoc calls this "a slice of its own": reparenting rewrites every descendant's `path` and needs cycle prevention. The SQL is one statement; the *UI* is drag-and-drop in a TreeView, which is a feature, not an affordance. Shipping the endpoint without it repeats the dead-hook trap from the other direction. **A `PATCH` whose `parentId` differs from the stored one answers 400 naming the reason** — silently dropping it would let a client believe a move happened. | its own slice |
| **Deleting a non-empty subtree** | `suite:delete` is specced "folder + subtree", but a cascade would soft-delete other people's **cases** through a folder action, with no preview. The `OWN` qualifier on `case:delete` exists precisely to stop one person unmaking another's work; a cascading folder delete routes around it invisibly. A non-empty delete answers **409 naming the count**. | the slice that can show a preview-then-commit, whose shape `BulkResult` already defines |
| **Reordering (`position`)** | Same family as move, and the flat list's order is `path`, not `position` (`SuiteRepository.findLiveForProject`). | the move slice |
| **Composition versions** | `suite_version.snapshot` is specced as **composition** (member cases, order, nesting) and explicitly *not* content — so a rename does not bump it, by definition. This slice writes **v1 at create** (the §29 precedent: a created row must not be born with a history gap) and nothing else. Case membership, reorder and reparent owe their bumps to the slices that introduce them. | move / case-move slices |

## Design decisions

### The `ltree` path label is the key sequence, not a slug of the name

`tcms_ltree_label()` (V2) slugs a name for classification values, which are seeded and never
renamed. Suites are renamed constantly, and a slug-derived path would make **every rename a subtree
rewrite** — contradicting `Suite`'s javadoc, which says reparenting is what rewrites paths. A
slug is also not unique (`"UI / E2E"` and `"ui e2e"` collide) and can be empty (`"…"` → `''`, an
invalid `ltree` label).

**Label = `s` + `key_seq`.** Unique by construction, stable across renames, never empty, and
valid without escaping. The path is already documented as not being a display string.

### The key is allocated after validation

`DisplayKeys.allocate(EntityType.SUITE, 1)` — already built and tested in §29. Called **after**
`SuiteEdit.validate`, for §29's reason: numbers are never recycled, so a rejected payload must not
burn one.

### The gate is borrowed, not restated

`ProjectService.writeGranted(userId, projectId, permission, …)` for all three, exactly as
`CaseCreateService` does. 404 for an unreachable project, 403 only after the read gate passed.

### Two permissions, not one

`suite:manage` (create, rename) is held by Test Engineer and above; `suite:delete` by leads only
(roles spec §6b: "deleting a subtree is the most destructive repository action, so it sits with
leads while contributors keep organising the tree freely"). Gating delete on `suite:manage` would
erase that boundary — and it is a boundary the spec called out as previously wrong.

## Contract changes (`openapi.yaml` first, then regenerate)

- `DELETE /projects/{projectId}/suites/{suiteId}` — new: `204`, `403`, `404`, `409`.
- `POST /suites` — add `400`, `403`, `404`, `Location`.
- `PATCH /suites/{suiteId}` — add `400`, `403`, `404`, `409`, and the `If-Match` header.

## Files

| Path | New? | Purpose |
|---|---|---|
| `repository/SuiteEdit.java` | new | Validation + the normalized edit. One answer to "is this name acceptable". |
| `repository/SuiteInput.java` | new | The wire request record. |
| `repository/SuiteWriteService.java` | new | Create / rename / delete, sealed `Outcome`. |
| `repository/SuiteVersions.java` | new | v1 at create, and the record of what does *not* bump. |
| `repository/SuiteVersion.java` | new | Entity for `suite_version`. |
| `repository/Suite.java` | edit | `create` factory + `rename` / `softDelete` mutators. |
| `repository/SuiteRepository.java` | edit | Subtree + live-case-count queries. |
| `repository/SuiteController.java` | edit | The three methods. |
| `frontend/.../SuiteTree.tsx` + a dialog | edit/new | The affordance. |
| `frontend/src/mocks/handlers.ts` | edit | Parity for PATCH/DELETE where the mock lacks it. |

## Gates

`./gradlew build` (‑Werror, full suite) · `npm run build` · `npm run lint` 0 warnings ·
mutation pass on the new logic · **live browser pass in `VITE_API_MODE=live`**: provision a company,
create a suite from the UI, create a case in it, rename the suite, fail to delete it (409), delete an
empty one.
