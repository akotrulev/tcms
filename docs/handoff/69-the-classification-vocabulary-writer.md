# §69. The classification vocabulary editor — the gate the dimension decides, and the label that stopped spelling the name

**Date:** 2026-08-02 · **Migration:** `V20` · **Backend:** +25 tests · **Frontend:** +8 tests
**Browser pass:** deferred (§65). **Queue rows added:** both.

## What this closes

`classification_value` has been in `V1` since the beginning with exactly one writer —
`tcms_seed_company_defaults`, a SQL function — and `tcms_seed_project_defaults` creates the Feature
dimension **with no values in it on purpose**:

> Seeded empty — inventing a client's feature taxonomy for them is how a controlled vocabulary gets
> worked around.

§68 built the case editor's Feature tree picker against that dimension and said on its way out what
was left: *"This slice makes the tree **usable** and not **fillable**"*. A freshly provisioned
project offered an empty picker and there was no path to a non-empty one short of an operator with a
psql prompt — which is §34's shape ("provisioned company *plus hand-written SQL*") one vocabulary
over, three years of slices later.

`POST` / `PUT` / `DELETE /projects/{id}/classification/values` land, plus the settings screen that
drives them.

## The orientation pass changed the slice twice, both times before any Java

### 1. `locked` meant three different things, and one of them made a permission cover nothing

| Document | What it says |
|---|---|
| `V2`'s seed comment | *"All three are locked = true: **a client editing them is fine**"* |
| `ClassificationDimensionView`'s javadoc | *"**the value list is not editable by the client.** True for the three company-level vocabularies"* |
| Roles spec, Company table | `company:vocabulary` — Instance Admin ✓, Company Admin ✓ — *"Test Type / Level / Phase values"* |

Two of the three say editable. The third makes `company:vocabulary` a permission that is seeded,
correctly granted since M0, and **covers nothing anybody can do** — §62's `case:approve` and §64's
`library:manage` a third time, and the reason to check rather than to pick the flag that reads like
a permission.

Settled: **`locked` is not read-only. It says whose the dimension is** — the company's, so it cannot
be overridden per project — and the operational reading, the one that now has a consumer, is *which
permission at which scope may edit its values*. The view's javadoc records the reversal struck
through rather than being quietly rewritten.

### 2. `path` had no reader, and `@ColumnTransformer` was missing exactly as `Suite` predicted

`Suite.path`'s javadoc, written when the suite tree got its writer:

> `ClassificationValue.path` has the same mapping and not this annotation. It is not broken today
> because nothing in Java inserts one — V2's seed function writes them with its own cast — and **it
> will fail exactly like this on the day something does.**

Today is that day. The annotation is added; the prediction was correct and cost nothing to act on
because it had been written down.

## The gate depends on the row, in KEY and in SCOPE

A dimension whose `project_id` is set is the project's own → `project:settings` **on this project**
(Test Lead and above). A dimension whose `project_id` is null is the company's → `company:vocabulary`
**company-wide** (Company Admin and above).

These are not two constants — they are two *questions*. `ProjectAccess.canDo` asks about one
project; `canDoInCompany` resolves from the SYSTEM and COMPANY terms only, and asking a company key
at project scope is the "held anywhere in this company" shortcut roles spec §A3 records **rejecting**
(it would make `case:delete` on one project read true for the whole company). So
`ProjectService.RequiredPermission` carries the key *and* the scope, and the new
`writeGrantedOnTarget` asks whichever the target names.

**The consequence is the point of the split, not an artefact of it.** A Test Lead builds out their
own product's Feature tree all day and cannot add a Test Type value, because "Performance" has to
mean the same thing in every one of that client's projects or the type × level coverage matrix stops
comparing anything (plan §6d).

`BulkOperations.permission` (§62) is the same shape one module over. There the deciding fact comes
from the payload; **here it comes from the stored row**, which is the stricter of the two and is why
the rule that a permission is a constant at the call site is kept rather than bent.

### 404 before 403, and here it is forced rather than chosen

`writeGrantedOnTarget` resolves the target **before** asking any permission, because the permission
to ask *is a property of the target*. A caller whose dimension does not resolve cannot be told 403
without the server inventing which permission they failed. The usual §6t argument arrives at the same
answer from the other direction; this endpoint could not have been written the other way round.

The dimension is resolved through `findForProject`, never `findById` — and that is the security
property rather than a style choice. `findById` is scoped by RLS to the **company** and not to the
project, so it accepts a *sibling project's* Feature dimension: the caller would extend a taxonomy
belonging to a project they may hold no grant on, gated on `project:settings` for the project in the
path, and **every cross-company test in the suite would still pass** because the row is in the
caller's own company. Persona 3's shape again. Queued as a mutation.

## The ltree label is derived from the id, and the second reason is the interesting one

`V2` ships `tcms_ltree_label()`, the seed uses it, and the obvious implementation here is the same
call. `Suite.create` refused it and wrote down why — a name-derived path makes **every rename a
subtree rewrite**, which is a lot of machinery for a column nothing displays. That transfers
unchanged. The second reason does not exist for suites:

**`tcms_ltree_label` has no answer for a name with no ASCII alphanumerics in it.** `lower()` +
`regexp_replace` + `trim` of `支付` is the empty string, and an empty `ltree` label is not a label —
the insert fails with a message about the *path* rather than about the name. A suite is named by a
colleague typing a folder name; a **Feature** value is the client's own product taxonomy, in the
client's own language, which is precisely where a non-ASCII name arrives first.

So the label is `v` + the value's uuid: unique by construction, immune to renaming, indifferent to
what characters a client uses. The cost is stated rather than hidden — the seeded values keep their
name-derived labels and everything the writer creates gets a `v…` one, so the column has two
spellings. Nothing in Java reads `path` today (`ClassificationValueView` passes it through, and
§6q's descendant matching is unbuilt and needs only that prefixes nest, which they do), so making
the seed agree would be a migration rewriting rows to change nothing observable.

**Mutation-verified**: switching to the name-derived label reddens exactly the one test, on `支付`.

## What it refuses, and why refusing is the feature

**Moving a value.** `parentId` must equal the stored one or it is a field error.
`SuiteWriteService` refuses a move because reparenting rewrites every descendant's path and needs
cycle prevention; this has that reason and one more — the values *under* this one are what authored
cases are classified by, so moving a subtree silently re-states what every case beneath it was
asserting. Silently dropping the field is the third answer, the one where the client shows the value
in its new place, reloads, and finds it back.

**A change of dimension**, for a reason that is not about difficulty at all: a value that changes
axis is a different value. "Regression" moved from Execution Phase to Test Type does not relabel
anything — it makes every case carrying it claim something it never claimed.

**Retiring a value with live children** — 409, count on the problem. The cascade is not built:
retiring a subtree unclassifies every case beneath it in one request with no preview of what it is
about to touch, and **every one of those assignments is unrestorable through the API**, because a
retired value is served to no client and therefore cannot be resubmitted (§68's bound, from the
other end). `suite:delete`'s non-empty refusal is the same decision about the same kind of tree.

What it does **not** refuse is a value that is *in use by cases*. Being in use is precisely what the
soft delete exists for. Refusing there would make a vocabulary that can only ever grow.

## Three references that satisfy the foreign key and store cleanly

`ClassificationValueEdit` takes the project's live vocabulary — the same list
`ProjectConfigurationService` serves — and three refusals fall out of having it:

1. a `parentId` naming a value of **another dimension** (a Feature parented under "Functional");
2. a `parentId` naming **another project's** Feature value — the third column in the family
   `sharedStepId` (§64) and `classificationValueIds` (§68) each closed;
3. a **duplicate name among live siblings**.

Both parent failures carry the **same sentence**, because telling them apart tells a caller that the
id they guessed names a real value in some other project.

Only *live* siblings, deliberately — the same bound §68's reconcile has. Blocking a new "Payments"
because a retired one exists refuses a name with an explanation the client can neither verify nor
clear.

## `V20`

A partial, per-parent, case-insensitive unique index. Each adjective is load-bearing:

- **per-parent**, because §68 turned on two leaves under different parents legitimately sharing a
  name — "Refunds" under Payments and "Refunds" under Billing are two different things, which is
  *why* the case detail had to start serving classification value ids. A per-dimension index would
  make the tree unable to express a taxonomy the product already renders;
- **`COALESCE(parent_id, nil)`** rather than a bare column, for the reason `V1` gives on
  `classification_dimension_key_idx`: NULL ≠ NULL, so roots — every value of the three flat
  vocabularies, and a Feature tree's top level — would be exempt from the constraint entirely. The
  uniqueness would hold everywhere except the level where a duplicate is most visible;
- **partial on `deleted_at IS NULL`**, or a retired "Payments" permanently blocks a new one and the
  only way back is resurrecting the retired row — which silently re-attaches it to every case that
  carried it before;
- **`lower(name)`** rather than `citext` on the column, because `name` is display text elsewhere and
  changing its type changes every comparison anyone ever writes against it.

It is a **backstop, not the check**: `ClassificationValueEdit` answers a duplicate as the field error
it is, and the index closes the window that check cannot — two administrators submitting the same
name at the same instant. The loser gets the same 400 on the same field through
`ProjectController#onKeyRace`, which already existed for the identical race on `uq_project_key`.

## The frontend

`/projects/:projectId/settings/classification`, **Classification** in the rail's Project group.

Under `/settings/` rather than beside `/access` because it is the first of several — statuses and
configurations are the same kind of screen with no writer yet — and a flat `/classification` would
have to be renamed the day the second lands, breaking every saved link to the first.

**All four dimensions, not just Feature.** A page showing only Feature quietly disagrees with the
picker beside it: the case editor offers four axes, and a settings screen listing one implies the
other three are not configurable rather than not *yours*. Every control reads `locked` to choose
which question to ask, so a Test Lead sees the company's axes **greyed with a tooltip** — ordinary
disable-don't-hide (§31), which is only followable because §65 put qualifiers on the wire and
`useCanInCompany` already existed for the project picker's one company-scope button.

The tree comes from §68's `buildValueTree`, which surfaces orphans **and cycle members** at the
root. That property was written for the picker, where a value with no control on screen is silently
deselected by the next save; here it matters in mirror image — **a value nothing renders is a value
nobody can retire**, and retiring is the only way back from the state that produced the orphan.

**No optimistic update on the retire**, deliberately: it is the one action whose failure the user
most needs to read, and an optimistic removal would take the row off screen and put it back with an
error beside it.

## The mock enforces the rules rather than storing what it is given

`dimensions` moved from the fixture into the store, and `handlers/cases.ts` was switched to
`db.dimensions` with it — otherwise a Feature value added through the editor would be refused by the
case editor as "not in the vocabulary", which is the mock disagreeing with itself inside one
session.

The mock persona sits exactly on the line this slice draws: a Test Lead on OLB holds
`project:settings` and the company set is `['company:read']`. So **both branches of the affordance
are reachable in mock mode without editing a fixture** — the state §31's own note about
`project:create` says to aim for and could not reach there.

## Findings from the tests

**All 25 backend tests passed on the first run**, which in this repo means running the mutations —
and the first one produced a **4** where the honest answer is **3**. A broken gate let the Test
Lead's create *succeed*, so the Company Admin's create of the same name then failed the *duplicate*
check: one of the four reds measured the fixture, not the code. Renamed, re-run, exactly 3. A
mutation count that measures the fixture is worse than no mutation, because it reads as extra
confidence.

**The frontend test failed for §66's reason before it failed for a real one.** Rendered bare rather
than through a `<Route>`, the page reads `projectId` as `''`, `useCan` is disabled, permanently
pending and fail-closed — so **every "disabled" assertion would have passed with no gate in the code
at all**. The tests render through a `Route` and hold a `permissionsResolved()` barrier on a control
that is enabled for both personas.

Mutation: one constant gate for both kinds of dimension reddens **exactly 1** frontend test — the
headline — with the Company-Admin guard test still green, which is what says the suite measures the
*difference* rather than a blanket disable.

## Left for later, deliberately

- **Moving a value**, with the descendant path rewrite and cycle prevention it needs. Both trees in
  this product now refuse a move for the same reason; whichever slice builds one should build both.
- **Reordering siblings.** `position` is allocated on create (append) and never edited. The read
  orders by position then name, so insertion order is the only order a client can express.
- **`classification_dimension` still has no writer** — a company cannot add a fifth axis, and
  `ClassificationDimension`'s "no factory method, add one with its endpoint" note still stands. A
  much smaller gap than this one was: four axes cover the model, and a fifth is a design question
  (does it apply company-wide or per project, and who may create it) rather than a missing verb.
- **`usage_count` on `classification_value`** is still untouched — nothing orders by it, so there is
  no defect to close, and §64's derived-count treatment is not needed here.
- **`ConditionRepository`'s `ORDER BY usageCount`** still ranks by a constant (§64's carry-over).
- **The artifact register's plan filter is `useState`, not the URL** (§61's carry-over).
