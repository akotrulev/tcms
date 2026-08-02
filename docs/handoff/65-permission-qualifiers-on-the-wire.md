# §65 — Permission qualifiers on the wire, and the two verification queues

**Date:** 2026-08-02. Backend + frontend + contract, one slice. **No migration — `V20` is still the
next free number.** The rung §61 named on its way out and deferred.

---

## What this closes

§61 shipped `artifact:edit` with a `PLAN_SCOPE` qualifier and recorded, in three separate files,
that the SPA **could not honour §31's disable-don't-hide** for it:

> A Test Engineer *holds* `artifact:edit`, `/me/permissions` is **keys-only**, so the button
> enables and the server answers that one refusal **with a body** naming `scope`.

That is a real defect and it was shipped knowingly, with the interim mitigation written into three
javadoc blocks. The user fills in a form, presses Register, and is told no — on the one control they
were one choice away from getting right. **Putting qualifiers on the wire is the real fix**, and
§61 called it its own slice. This is that slice.

`GET /me/permissions` now answers `[{ key, qualifier }]` instead of `[key]`.

---

## Decision 1 — the two computations become one, and that is the slice's real content

`PermissionEvaluator` had **two** ways to resolve a caller's grants:

| | |
|---|---|
| `evaluate(user, project)` | union of SYSTEM ∪ COMPANY ∪ (PROJECT **or** inherited) → a set of keys |
| `qualifierOf(user, project, key)` | the **same three terms**, the **same** replace-don't-union boundary, merged with `strongest` → one qualifier |

Both were correct. The problem is that they had to **stay** correct together: the rule that a
project grant *replaces* an inherited one — rather than merging to the stronger — was written out
twice, and **nothing failed if one copy was edited**. `qualifierOf`'s own comment said so, in the
imperative: *"it has to be the same or the two disagree about which grant is in force."* A rule
maintained by a comment saying it must be maintained is the shape this project keeps paying for.

So the qualifier moved into `PermissionSet`, which is now `key → qualifier` rather than a set of
keys, and `qualifierOf` is a **delegation**: `evaluate(user, project).qualifierOf(key)`. One
traversal, one merge rule, one boundary.

**The gates' signature did not change.** `ProjectAccess.canDoOnAuthored` / `canDoOnScope` still ask
about one key and still get one qualifier back — the row's fact crosses the module boundary and a
verdict comes back, exactly as before. What changed is where the answer comes from.

### What this deleted

Four repository queries — `findSystemQualifiers`, `findProjectScopedQualifiers`,
`findCompanyRoleQualifiers`, `findInheritedProjectRoleQualifiers`. The three key projections widened
to `SELECT DISTINCT rp.permission_key, rp.qualifier` (`HeldPermissionRow`) and the per-key ones
became dead. **None of the four had a direct test** — they were covered only behaviourally through
the gates, which is why deleting them broke nothing and is also why the duplication survived.

`DISTINCT` is over **both** columns deliberately. One key can legitimately come back twice — two
grants conferring it with different qualifiers — which is precisely what `strongest` resolves.
`DISTINCT ON (permission_key)` would have Postgres pick one row arbitrarily and make **an
authorization decision by row order**.

### The consequence that is worth stating rather than discovering

`PermissionQualifier.strongest` **throws** on `OWN` + `PLAN_SCOPE` (V19 — they restrict different
axes, so their union is inexpressible). That throw now fires during **any** permission resolution
rather than only at the gate for that one key. That is a widening of blast radius and it is correct:
the response carries a qualifier per key, so a key whose qualifier is inexpressible has **nothing
honest to serialize**. `SeedDataTest` keeps it unreachable against seeded data, as before.

---

## Decision 2 — the wire shape is pairs, not a parallel map

The tempting alternative is to leave `permissions: PermissionKey[]` alone and add
`qualifiers: { [key]: Qualifier }` beside it. Rejected: a key in one and not the other is
unspecified, and the two can disagree — the "mock agrees with itself" class one level up, on the
wire, where both halves are generated and neither is checked against the other.

Replacing the array makes `held.includes(permission)` a **compile error at every consumer**, which
is this project's own idiom (§61: *"adding the value made the switch non-exhaustive under `-Werror`,
so the compiler made every consumer decide"*). It found five: one real (`useCan`) and four test
helpers.

`NONE` is deliberately **not** in the enum on the wire, and `PermissionSet.of` **throws** if asked
to hold a key qualified to `NONE`. A permission the caller does not hold is an *absent* entry, never
a present one covering nothing — the same rule the database follows, where a grant of nothing is an
absent row. Every consumer reads presence as capability, so "held, covering nothing" must be
unrepresentable rather than merely unproduced.

---

## Frontend

`useCan` gains `canOnScope(permission, rowIsPlanScoped)` and `reasonOnScope`, **mirroring the
server's `canDoOnScope` arm for arm** rather than reinterpreting the qualifier. What `PLAN_SCOPE`
*means* is decided once, server-side; the client asks a question. A second reading of a qualifier is
the bug class plan §6b names, and it is no less one for living on the client.

The refusal sentence is **different from the one for a permission nobody holds**, and that is the
point:

> "Your manage artifacts permission covers a single plan, not the whole project."

*"You don't have permission to manage artifacts"* would be **false** for this caller — they manage
them every cycle — and would send them to ask an administrator for a grant they already hold.

**The MenuItem is disabled on the item, not wrapped in a `PermissionGate`** — §62 cost a whole live
pass to learn that `Select` clones its *direct* children to inject `role="option"`, so a wrapped
item renders as an inert `role="menuitem"` carrying no `aria-disabled` either. The new tests assert
through `role="option"` for the same reason.

### The defect the existing tests caught

The dialog moves the form off `PROJECT` scope when the caller may not use it — otherwise it opens on
a disabled choice. The first implementation did that whenever `canOnScope` was false, and
**`canOnScope` is fail-closed while the permission query is in flight**. So it silently demoted
*every* caller's default from PROJECT to PLAN during the load, including a Test Lead's.

Two **unrelated, pre-existing** tests went red — a payload assertion and the 403-lands-on-the-field
test — because the form submitted at the wrong scope rather than showing anything. Hence
`Gate.pending` on the interface: *"not yet" is not "no"*. Use it only to **defer** a decision, never
to grant one, or the enabled-then-disabled flash the hook's header rules out comes back.

### The stale mocks, and why the compiler could not see them

Five test files mock `/me/permissions` through `vi.spyOn(api, 'get')`, which needs an `as never`
cast — so the shape change broke them **with zero type errors**, surfacing as
`pointer-events: none` on a button three assertions later. That reads as a UI defect, not a stale
fixture.

Fixed by `src/test/permissions.ts` — **one** `heldPermissions(keys, qualified?)` helper, now used by
all six sites. The next change to this response is a compile error in one file, which is the only
place it could be fixed anyway.

---

## The two verification queues — a standing policy change

**Set this session, at the user's direction.** Two things were being paid *during* feature work and
are now **deferred and batched**:

| Queue | File |
|---|---|
| Browser / live passes | `docs/verification/frontend-browser-checks.md` |
| Backend mutation checks | `docs/verification/backend-mutation-checks.md` |

The reasoning is delivery order: functionality first while requirements are still moving, then one
concentrated verification pass. **The bar is not lowered, it is moved** — a slice still has to state
the mutation it would have run and the flow it would have driven, in a file built to be executed.

**The risk is real and is recorded rather than glossed.** `CLAUDE.md` says the gates prove it
compiles, not that it works, and the evidence is overwhelming: the live pass has found a defect no
gate could see in most slices since §42. Deferring does not make those defects less likely — it
makes them **arrive later, in a batch**. Until the queues are run, a slice listed in them is *built
and unverified*, and should be described that way.

**§65 is the first slice to land under this policy**, and it is therefore **not browser-verified**.
Six checks are queued, and the highest-risk one is not the new feature: the response shape changed
under **eleven** gated affordances at once, and `useCan` falls **open** on failure — so a consumer
left on the old shape looks *normal* rather than broken.

One mutation was already run before the policy existed and is kept as the worked example:
hard-coding `ALL` in the merge reddens **exactly 8 tests** across both the `OWN` and `PLAN_SCOPE`
families and nothing else. Two more mutations are queued.

---

## Gates

- `backend` — `./gradlew build` clean, **1202 tests, 0 failures, 0 skipped** (+3).
- `frontend` — `npm run build` clean, `npm run lint` 0 warnings, **`npm run test:run` 470/470** (+9).
- **Not driven in a browser** — see the policy above and the queued checks.

## Left for later, deliberately

- **`case:delete`'s `OWN` qualifier is on the wire and nothing reads it.** The same defect class as
  the artifact one: a Test Engineer sees Delete enabled on a colleague's case and gets a 403. It
  needs `canOnAuthored` and the row's author at each call site, which is a sweep across the case
  grid, the case detail and the bulk dialog — a slice, not a footnote to this one. **The wire half
  is done**, so it is now buildable without touching the contract.
- **`ConditionRepository`'s `ORDER BY usageCount` still ranks by a constant** (§64's carry-over).
- **The artifact register's plan filter is `useState`, not the URL** (§61's carry-over), so a plan's
  register cannot be linked to. Queued in the browser-checks file.
