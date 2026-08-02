# §63 — The project's own access screen, and telling the password managers

**Date:** 2026-08-02. Frontend only — **no contract change, no backend change**, which is what let it
run alongside the concurrent `cases/bulk` lane (§62) without a holder handover.

Two things: a per-project **Access & roles** screen, and making the browser's password manager
actually offer to save a credential.

---

## 1. Access & roles, per project

`/projects/:projectId/access`, in the rail under **Project**. `/admin/teams` stays the **company**
view. The split is real rather than cosmetic — two genuinely different questions:

- *who is in this company and what do they hold* → `Teams & roles`;
- *who can act on this project, and what did this project decide versus inherit* → this page.

`4e2a71c` had just moved `Teams & roles` from the Project heading to Company, on the grounds that
listing it under Project told the reader roles are granted per project. That reasoning stands and
this page does not undo it: a role is still *held* across the company, and this screen's whole job
is showing which part of that a project has overridden.

### The commit that landed the same morning is why the page is not a filtered list

`796c7df` made a PROJECT-scope role grantable with **no project**, meaning *this role on every
project unless that project says otherwise* (roles spec §A1.1). So **`projectId IS NULL` stopped
meaning "company scope"**: it now covers two populations with **opposite override semantics** —
COMPANY-scope grants (Company Admin: authority over the company, never overridden) and inherited
PROJECT-scope grants (a default for every project, overridden by any project grant). The backend
split its queries on `role.scope` for exactly this reason; the client has to make the same split or
it renders a Company Admin as somebody's project role.

Three regions:

1. **Granted on this project** — revocable here, gated `project:members`.
2. **Inherited from the company** — **read-only here.** Revoking one removes the role from *every*
   project, and that blast radius belongs on the screen that is about the company. Not a waiver of
   §31's disable-don't-hide: nothing is refused, the action belongs to another scope and the row
   says so.
3. **What each role can do here** — PROJECT-scope roles only, because a COMPANY-scope role cannot be
   granted on a project at all.

### The override is arithmetic, so it lives in `roleAccess.ts` with its own tests

§A1.1 is **replace, not union**, and *the switch is whether a grant naming the project exists, never
what it confers*. Every way of getting that wrong renders perfectly and says something false:

- a **union** shows both roles and reads as generosity;
- a check over **what a grant confers** undoes every restriction, and a role granting nothing fails
  to override at all;
- forgetting **team grants** answers with a plausible list that omits everybody staffed through the
  org chart — **§54's defect exactly**, so the overridden set is seeded from the project's team
  grants expanded through `Team.members` before any user grant is read.

**A test moved the COMPANY-scope exemption into the helper, and that is the finding of this half.**
§A1.1 property 2 says a Company Admin is never overridden. But an admin who is *also* covered by one
of the project's grants is legitimately **in the overridden set** — so `isOverriddenHere`, reading
the set alone, answered "replaced" for them. That is precisely the **privilege inversion** the
exemption exists to stop (§43/§45's shape): anyone holding `project:members` could grant the
company's administrator Viewer on a project and have the screen agree the demotion took. The guard
now lives *inside* the helper. **A rule enforced by whoever remembers to ask `isInherited` first is
a rule the second caller gets wrong** — the page had it right and the helper did not, which is the
only reason it was invisible.

### Why the unfiltered list, when `?projectId=` exists

The parameter answers region 1 exactly and **cannot** answer region 2 — those rows name no project.
So the page would need both calls, and the override is computed **across** them. Two queries is two
snapshots: a revoke landing between them renders "replaced here" against a grant that no longer
exists. One list, partitioned client-side, cannot disagree with itself — and it is the same cache
key the company screen already fills, so arriving from there costs no request.

`RoleAssignmentTable` and `RoleReference` were **extracted** from `TeamsPage` rather than copied.
The interesting part is not the markup: it is the scope-dependent gate and the one refusal
`PermissionGate` cannot predict (you may not revoke the grant carrying your own ability to revoke).
Two implementations of those are two chances to offer a Revoke the server refuses.

### The grant dialog had stopped agreeing with the server

`grantableRoles` still filtered company scope to COMPANY-scope roles after `RoleGrantService`
stopped refusing the other case. **This is §50's shape in the client's direction**: the server
accepts an input no screen can produce. The MSW handler carried the same stale refusal, which would
have made the inherited region **unreachable in development** — a mock stricter than the server is
the same defect class as one more generous, and this slice found both in one file (see below).

The test that pinned the old rule is **rewritten to the new rule, not deleted**, as the backend did
with its two.

### Two mock defects, opposite directions

- `POST /role-assignments` refused a PROJECT-scope role at company scope — **stricter** than the
  server.
- `GET /role-assignments` returned **every** grant to **every** persona — **more generous** than the
  server, which filters by `AdminReach.mayStaff`. Harmless until a screen began reading the
  company-wide rows, at which point mock mode showed a Test Lead rows live mode never would.

The fixtures also carried a **Company Admin granted ON a project**, a row no path in the product can
create, and two rows with **both** `user` and `team` set, which `ck_role_assignment_subject` forbids
and `RoleAssignmentView` never emits. Both taught the "granted via" column a shape live mode does
not produce.

### The live pass found the one no gate could

The first version told a Test Engineer **"Nobody is staffed here directly"** — to a reader who was
demonstrably staffed there and merely could not administer it. **§47's tile and §49's chip a third
time**: copy accurate about its query and false about the thing it labels. The server returns only
what the caller may staff, so *empty* has two meanings and the screen was rendering one of them.
Both regions now distinguish them, and the page says it once at the top rather than leaving the
reader to infer it from two empty tables.

---

## 2. The password managers

`autocomplete` alone was never going to work here, and this is the part that looks done and is not.
A manager offers to save on a **navigating form submission**. This app never does one:
`react-hook-form` calls `preventDefault`, the request goes out through `fetch`, and the result is a
client-side route change — so from the browser's point of view the form never submitted and never
navigated. Chrome's SPA heuristics fire *sometimes*, **which is the worst case: it appears to work
on the machine it was written on.**

So both mechanisms, because they are not alternatives:

- **`lib/credentials.ts`** calls the Credential Management API after a successful sign-in and after
  an invitee sets a password — Chromium, and therefore Google Password Manager. Called *after* the
  await on purpose: the API's contract is "these worked", and storing before it teaches the manager
  a password the server had not accepted, which then autofills forever. Not awaited, so the browser's
  prompt does not hold the user on the form.
- **The markup**, for Safari — which implements **no** Credential Management API for passwords, so
  for iCloud Keychain the markup *is* the mechanism. A declared `method`/`action` that is never
  followed, and the real fix: **the accept-invitation form had no username field at all.** That is
  the classic reason a manager declines to save, or files the entry under no account so it never
  autofills. It is now a read-only email field, which is also better than the caption it replaced.

It never throws. The user is already signed in by the time it runs, so a rejected prompt escaping
would turn a completed sign-in into an error toast. Silence is the correct handling here, not a
swallowed bug.

---

## Gates

`npm run build` clean, `npm run lint` 0 warnings, **`npm run test:run` 453/453** on the merged tree
(this slice added 14; §62 added the rest).

**Two tests failed on one run and it was not a regression** — the run overlapped a
`docker compose build`, and suite test time went 72s → 276s under the contention. §54's suite-wide
timeout flake, reproduced by CPU load rather than by code. Green twice idle afterwards. Worth
knowing before blaming a change: **this suite fails under load, and the tell is the duration.**

## The live pass, in two parts — and the reason it took two

Driven in a real browser in **LIVE mode**. The first pass proved the read-only branch, the populated
branch, a project grant and a Revoke — but **could not create a company-wide PROJECT-scope grant**,
because the running container had been built at 04:52 UTC and `796c7df` landed at 06:33. The server
refused it with the *pre-`796c7df`* message. The container was left alone rather than rebuilt: the
backend lane was mid-flight and that was its to rebuild. The override chip was proven against a row
**seeded directly in the local dev database**, and the commit said so.

~~The dialog change cannot be exercised through the product until the backend redeploys.~~ →
**superseded the same day.** Once the backend lane finished, `docker compose up --build backend` put
the container on current source, the seeded row was **deleted**, and the whole rule was re-created
**through the product**: a company-wide Test Lead granted from `Teams & roles` (which the old
container refused), then a Viewer granted on the project from the new screen, and the inherited row
went struck-through and **"replaced here"** — the case §A1.1 singles out, where the project grant
confers *less*. Olive's Company Admin stayed untouched beside it. Revoking the project grant brought
the inherited role back to life. `psql` confirms four rows matching the screen exactly; `0` client
errors.

**The company screen's Project column now distinguishes "Every project" from "Company-wide"**, driven
by `role.scope` — a distinction that did not exist before §A1.1 and that a single "—" would have
collapsed.

## Left for later, deliberately

- ~~**`useRoleAssignments(projectId)` still has no caller with an argument.** Decide it rather than
  inherit it (§28's rule).~~ → **decided the same day, see below.** It is gone from the hook.
- **The inherited region cannot be seen in mock mode with the default persona**, who holds no
  `company:members` — correctly, now that the mock mirrors `mayStaff`. The documented toggle is to
  add `'company:members'` to `companyScopePermissions` in `fixtures/core.ts`, the same pattern that
  file already carries for `project:create`.
- A **team** row's "replaced here" is answered only by that team's own project grant. "Some of this
  team is overridden and some is not" is true and the row has no honest way to render it, so it is
  not claimed — the panel copy carries the caveat instead.

---

## Addendum — the dead parameter, decided

`useRoleAssignments` no longer takes a `projectId`, and `keys.roleAssignments` is a constant rather
than a function. The contract still declares the filter and `AdminController` still binds it; only
the client stopped asking, so §50's check — which is one-directional, *SPA sends ⊆ contract
declares* — is unaffected.

**It was not an unfinished feature, and that is the point of writing it down.** §35 and §61 each
found a hook with no call sites and the reflex both times was *find it a consumer*. Here the
consumer cannot exist: the server's filter returns only the grants **naming** the project, and
**there is no filter for the ones naming none.** Since §A1.1 those are two populations with opposite
override semantics, so `ProjectAccessPage` needs both — and `?projectId=` cannot express the second.
Pairing the two calls is strictly worse than one: the unfiltered list is a **superset**, so it
fetches everything anyway plus a redundant round trip, and an override computed across two snapshots
can render "replaced here" against a grant a revoke has already removed.

**The payload argument is real and this was the wrong lever.** Measured against the live endpoint:
**~1.1 KB per grant row**, dominated by the whole `Role` — `description` and `permissions` — repeated
on every row for the same six roles. Extrapolated, a Company Admin over 40 projects would pull
roughly a megabyte onto a screen about *one* project. The lever that actually moves that is
**dropping `RoleView` from `RoleAssignmentView`** in favour of `roleId`, letting the client join
against `/roles`, which both consumers already fetch and cache for five minutes. That cuts *both*
screens and raises no scoping question. It is a contract change, so it belongs to whoever holds
`openapi.yaml` next. **`TeamsPage` has paid this cost since §39** — the access page did not introduce
it and would not have been the place to fix it.

### It broke a check, and the check was right to break

`contractParameters.test.ts` asserts a **floor** on how many `qs()` call sites its traversal finds,
so the file cannot pass vacuously if the compiler stops resolving them. Removing one call site took
it 10 → 9 and went red. That is the guard doing its job: *"the analyser broke"* and *"there is
genuinely one fewer call site"* are indistinguishable to it by design. Resolved by **counting `qs(`
in `api/queries/` before and after** — exactly 10 → 9, matching the single removal — and only then
moving the floor, with that verification recorded beside it. **Lowering such a floor without
measuring first is how a check gets quietly accommodated into uselessness.**

Gates after the change: build clean, lint 0 warnings, **452/452** — one lower than the 453 above,
which is the removed call site's own parameterised case.
