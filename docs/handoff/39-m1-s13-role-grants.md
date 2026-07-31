# §39. Backend M1, slice 13 + frontend — role grants, and the gate the payload chooses (2026-07-31)

Run through the **`be-java`** skill (Mode B — extend) and then the **`fe-react`** skill (Mode B,
re-entering Phase 2). **Lane B** of the second three-lane parallel run; **not** the contract holder
(lane A held `openapi.yaml`, and the endpoints below landed in `a8e4b50` before this lane started).

Closes the blocker §34 left standing. §34 made "a provisioned company" stop meaning "a provisioned
company plus hand-written SQL" **for projects**. It still meant exactly that for **people**:
`provision-company` creates one usable account, a colleague could be invited and could sign in, and
*nothing over HTTP could give them sight of a project* — because company membership grants
authentication and **zero project visibility** (roles spec §A1) and a grant could only be written in
psql.

| Path | Purpose |
|---|---|
| `identity/AdminController.java` | Six endpoints, one contract tag. 200/201/204/400/403/404 and the race recovery. |
| `identity/AdminDirectoryService.java` | The four reads — a **company** directory, never an instance one. |
| `identity/AdminReach.java` | **The one place the scope-dependent gate is decided.** |
| `identity/RoleGrantService.java` | Grant, revoke, the idempotent re-grant, the self-lockout refusal. |
| `identity/{User,UserRef,Team,Role,RoleAssignment}View.java` · `RoleGrantRequest.java` | The wire shapes. |
| `V13__role_assignment_uniqueness.sql` | The uniqueness `role_assignment` has never had. |
| `identity/RoleGrantEndpointTest.java` | **New, 58 tests** over HTTP against real Postgres with RLS. |
| `features/admin/GrantRoleDialog.tsx` · `TeamsPage.tsx` | The form, and per-row Revoke. |
| `api/queries/admin.ts` · `mocks/{db/store,handlers/admin}.ts` | The two mutations; the mock becomes stateful and gated. |

### The gate is chosen by the payload, which no other write in the application does

Every other write names one permission — `case:edit` on this case's project. §34 added the second
shape: a permission at **company** scope, because the target did not exist yet. This is the third
and the awkward one: **the same endpoint asks a different question depending on what is in the
body.**

- `projectId` present → PROJECT scope, gated on `project:members` **on that project**. A Test Lead
  staffs the project they run and not another one.
- `projectId` null → COMPANY scope, covering every project present and future, gated on
  `company:members`. Company Admin and Instance Admin only, and the argument is §34's verbatim: a
  grant that spans every project cannot be authorised by a permission held on one.

Both verdicts come from `AdminReach.mayStaff`, which the read filter, the grant and the revoke all
call. That is the point of the type: three implementations of "may they?" is three chances for the
screen to offer a Revoke the server refuses, and plan §6b's rule is that the quiet one wins.

**`mayStaff`'s project branch deliberately does not fold in "or company-wide"**, even though a
Company Admin passes both (their `project:members` comes from the COMPANY term). The contract states
one rule; two would quietly cover a future role holding `company:members` without `project:members`,
which the model permits and nobody has decided may staff a project.

### The orientation pass found one thing already true and one thing not true at all

1. **`role_assignment` needed no DDL for the grant itself** — the table, the subject check and both
   indexes are V1's. **It needed DDL for the promise.** The contract says an identical re-grant
   "returns the existing one with 200, not a duplicate and not a 409", and the table has carried
   **no uniqueness at all** since V1. Harmless while the only writer was `provision-company`, which
   guards itself; reachable the moment an endpoint exists. So `V13` is used, and for a reason the
   plan did not anticipate.

   The cost of the duplicate is not an untidy list: **the same person holds two grants to revoke, so
   revoking the one on the screen leaves them holding the role**, and the admin who did it has every
   reason to believe access was removed.

   Two *partial* indexes, and `NULLS NOT DISTINCT` on both. `ck_role_assignment_subject` leaves a
   NULL in `user_id` or `team_id` on every row, and in a plain unique index NULLs are distinct — so
   one index over both columns would dedupe nothing for team grants. And `project_id IS NULL` is how
   COMPANY scope is spelled, so without `NULLS NOT DISTINCT` the **company-wide grants — the Company
   Admin appointments, the most consequential rows in the table — would be the only ones not
   protected.** A `UNIQUE` constraint can express neither (no `WHERE`, and no `NULLS NOT DISTINCT`
   before PG15); this deployment is 17.

2. **`GET /roles` had to expand `role_permission`, which has no entity** — the same call
   `RoleAssignmentRepository` makes for the evaluator, for the same reason, so it is a second native
   projection on `RoleRepository` rather than a `@ManyToMany` nobody navigates.

### The defect the first test run found: `company:read` is unreachable at company scope

The read surface was gated on `evaluateCompanyWide(user).has("company:read")` — the obvious reading
of roles spec §A3, which grants `company:read` to *"Every member"*, and of `V3`, which seeds it onto
five of the six roles with a comment saying so: *"Keeps company:read (they are a member and every
check is a real permission check)"*.

**Four tests went red, all of them the Test Lead persona reading an empty list.** Four of those five
roles are **PROJECT-scope**, and `evaluateCompanyWide` is SYSTEM + COMPANY only — by design, since
§34. So a permission the spec says every member holds is **invisible to the only question that asks
about the company**, for everyone below Company Admin.

This is not a bug in this slice; it is a gap between the roles spec and the scope model, and it was
invisible until something asked. Two repairs exist and neither is this lane's to make:

- seed `company:read` through a COMPANY-scope grant every member receives on joining, or
- let the evaluator answer "held anywhere in this company".

Until then `AdminDirectoryService.reachOf` asks the honest version — held company-wide **or** on any
project this caller holds — which matches the spec's intent and costs nothing, because the
per-project sets are computed on the same pass. **Anything else that ever gates on a company-scope
permission will hit this**, so it is written here rather than in one class's comment.

### Two refusals that are more interesting than a permission check

**The self-lockout is computed by doing the write and asking again.** The revoke deletes the row,
flushes, and asks `PermissionEvaluator` the gate's own question a second time; a "no" throws out of
the `@Transactional` method and Spring unwinds the delete *and* its audit row. The alternative is to
work out beforehand whether the caller holds the gate through some **other** grant — which means
re-deriving the union: direct grants, team grants, the COMPANY term, the SYSTEM term, the membership
gate. That is a second implementation of the permission algebra, and it would have to be kept in
step forever. Doing it and asking is correct for a **team** grant the caller belongs to, for a
caller holding two grants, and for an Instance Admin (whose SYSTEM term survives anything done
here) without any of those being enumerated. Two tests pin the refusal *and* the rollback, because
if the rollback ever stops working the response still says 400 and the grant is gone.

**A SYSTEM-scope role is a 400 on `roleId`, not a row.** Instance Admin's grants live in
`instance_role_assignment` because a nullable `company_id` under RLS is permanently unreachable
(measured — `docs/audits/2026-07-30-v1-schema-first-execution.md`). Writing one into
`role_assignment` would not merely be the wrong table: it would confer instance-wide powers **scoped
to one company**, a tier the model does not have, and it would be done by a Company Admin, who may
not appoint operators. There is no HTTP path to a SYSTEM grant in this slice and there should not be
one until an instance-admin surface exists to hold it — the operator CLI is the only way. Two tests,
including one that an **Instance Admin** cannot smuggle one in either.

### The assignment list is exactly the set the caller could revoke

Not "what you may see, gated on what you may change". A Test Lead gets the grants on the project
they lead and nothing else — not the company's other projects' staffing, not the company-wide grants
that made someone a Company Admin. Every row rendered carries a Revoke the server will accept, and
every grant the server would accept a revoke for is on the screen.

The alternative reads the company's whole org structure off a page a Test Lead was given for one
project. The same rule filters a team's project chips: **a team can hold a grant on a project the
reader has none on, and the org chart must not be the way around §A1.**

### The frontend's one deliberate departure from §31

**The button that opens the grant dialog is ungated** — the only ungated write affordance in the
app. The permission the server will check is chosen by a field *inside* the form, so the gate lives
on the dialog's **Grant** button, where `PermissionGate`'s `projectId` is a piece of form state and
the verdict re-renders as the scope select changes. Gating the opener would need the answer before
the question had been asked, and would disable the whole feature for a Test Lead who may staff
exactly one project. §31's rule is about the *action*; the action is Grant, and Grant is disabled
with a reason. Stated here because it will read like an oversight otherwise.

`useCan.ts` and `PermissionGate.tsx` were **not modified** — §37's near-miss (lane A changing the
files lane C was asserting on) was the one thing this lane was told to avoid, and the new call sites
needed no widening.

The role select is filtered by the chosen scope, and changing the scope **clears the role**: a role
picked under the old scope is one the server refuses with a 400 on a select the user did not touch.

### Contract divergences found and NOT fixed — for the integrator

1. **`RoleAssignment.user` is declared `required` and non-nullable, and cannot be.**
   `ck_role_assignment_subject` enforces user **xor** team, and `POST /role-assignments`'s own
   description says "exactly one of `userId` and `teamId`" — so a team grant has no user to name.
   The server sends `"user": null`; the SPA already read it with `?.` before this slice. Same shape
   as the `EntityVersion.createdBy` correction §37 records, and the same idiom fixes it: `allOf` +
   `nullable: true`. `RoleGrantEndpointTest.aTeamGrantHasNoUser` asserts the current behaviour so
   the correction is visible when it lands; the mock handler carries one cast that dies with it.
2. **`GET /users`, `/teams`, `/roles`, `/role-assignments` declare only `200`.** The read gate is
   therefore expressed as an **empty array**, not a 403 — a status a generated client does not know
   about is the divergence that costs most (§37 finding 2 is the same omission from the other side).
   Defensible as it stands; if a 403 is wanted there, the contract has to say so first.
3. **`RoleAssignmentInput.projectId` is `required` and nullable**, which JSON cannot distinguish
   from omitted. `RoleGrantService` treats an omitted `projectId` as the company-wide grant it asks
   for, and the SPA always sends it explicitly. Noted rather than changed.

### Gate evidence

**Backend** — `./gradlew build` clean with `-Werror`, **688 tests** (630 + 58), against Testcontainers
Postgres 17 with the non-superuser owner and RLS in force.

**Mutation-verified.** Four mutations, one narrow edit each; all four killed exactly what they
should:

| Mutation | Tests failed |
|---|---|
| The company-scope gate never shuts (`mayStaff(null) → true`) | **4** |
| The self-lockout check disabled | **4** — including both rollback assertions |
| The assignment list filtered by visibility instead of reach | **2** |
| A SYSTEM-scope role accepted | **3** |

**Frontend** — `npm run build` clean, entry chunk **unchanged** (`index` 253.29 kB / 79.82 kB gzip),
`npm run lint` 0 warnings, `npm run test:run` **208/208** (203 + 5).

`find . -name "* 2.*"` — nothing outside `node_modules`.

**Not done: a live browser pass.** Stated rather than implied, and it is the honest gap. Two things
want eyes specifically:

1. **The Grant button's gate changing as the scope select changes.** `PermissionGate` has never had
   a `projectId` that moves; a stale verdict would be invisible to both gates.
2. **The disabled Revoke's tooltip in a table cell.** MUI's `pointer-events: none` is what
   `PermissionGate`'s wrapper exists for, and §31's browser pass found its cost inside a `MenuList`
   on the first try. A `TableCell` is a new container for it.

In mock mode the seeded persona is a Test Lead on OLB with `company:read` company-wide, so the
company-wide branch renders **disabled with its reason** and the OLB branch renders enabled — both
halves of the affordance are reachable without editing a fixture, which is the first time that has
been true for a gated write.

### Deliberately not done

- **No company-wide grant of a PROJECT-scope role**, and the consequence is worth knowing: the
  "stakeholder Viewer over the whole company" arrangement — how `ProjectCreateEndpointTest` seeds a
  client's read-everything stakeholder, noting in its own comment that `role_assignment` does not
  enforce scope — **is not expressible over HTTP.** That fixture is *seeded*, not granted. The roles
  spec gives each of the six roles exactly one scope, so refusing the mismatch follows the spec; if
  the product wants a company-wide read-only stakeholder, that is a **seventh role in the
  catalogue**, not a relaxation of `scopeError`.
- **No session revocation.** `AppUser.revokeSessions()`'s javadoc names "a grant change" as a call
  site that must bump the `pv` epoch and says those call sites do not exist yet. This is the first
  grant mutation and it deliberately does not bump it: the session token carries no grant claims
  (§18), so the evaluator reads the database on every request and a revoke is effective on the very
  next one — asserted over the wire. Bumping would sign the target out of a session whose
  permissions are correct. It becomes necessary the day plan §10's JWT grant claims land, and
  `RoleGrantService`'s header says so at the call site.
- **No team management** — creating a team, adding or removing members, setting a lead. `Team` has
  `create`/`rename`/`reparent` and `TeamMembership.join`, and no endpoint calls any of them, so a
  team grant can only be made to a team `provision-company` or psql created. `company:teams` is the
  permission it would hang on. The grant path is what blocked; this is the next thing that does.
- **No membership management** — inviting a person into the company from the UI, removing one,
  renaming their per-company display name (`company:members` covers all three, and
  `InvitationService` already exists for the first). `GET /users` is read-only.
- **No pagination on any of the four reads.** The contract declares bare arrays and the ceiling is
  `ProjectService`'s: a company holds a handful of products and tens of people. It becomes a real
  question at hundreds of grants, and the contract has to change first.
- **The evaluator's cache.** `reachOf` runs N evaluations for N projects, on every admin read. Same
  trade `ProjectService` states and accepts, and it improves for free when the cache
  `role_assignment`'s table comment specifies lands.
- **The `shared` module** — `AdminController` is the **third** copy of the RFC-7807 envelope shape
  (after `repository.ApiProblem` and `InvitationController.ProblemBody`), and like §34 it uses
  Spring's own `ProblemDetail` rather than adding a fourth. The extraction is still a module-list
  decision nobody has made.

### Next

1. **The live browser pass this slice unblocks, end to end with no SQL at all** — provision a
   company, create a project (§34), invite a colleague (§18), **grant them a role**, and watch the
   project appear in *their* picker. That is the first complete path through the product's access
   model, and it is now one session's work.
2. **Teams and memberships** — the two write surfaces named above. `company:teams` and
   `company:members` are the gates, both already answered by `AdminReach`.
3. **The `company:read` gap** — one of the two repairs above, decided rather than worked around
   again by the next thing that needs a company-scope permission.
4. **`GET /role-assignments` for one *user*** — the admin question "what does this person hold" has
   no endpoint; the directory answers "who is here" and the assignment list answers "who holds
   what", and joining them is the screen's job today.
