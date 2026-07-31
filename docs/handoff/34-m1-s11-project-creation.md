# §34. Backend M1, slice 11 + frontend — project creation, and the gate one level above the project (2026-07-31)

Run through the **`be-java`** skill (Mode B — extend) and then the **`fe-react`** skill (Mode B,
re-entering Phase 2). Lane A of a three-lane parallel run, and the **contract holder** for it.

Closes the item §31, §32 and §33 each named first: **"a provisioned company" no longer means "a
provisioned company PLUS hand-written SQL"**. Two consecutive slices skipped their live browser pass
for exactly this reason.

| Path | Purpose |
|---|---|
| `docs/specs/openapi.yaml` | `POST /projects` + the `ProjectInput` schema. **The only contract change in this run.** |
| `project/ProjectCreateService.java` | Create orchestration; sealed `Outcome`; the seed call; the audit row. |
| `project/ProjectEdit.java` | Validation and normalization, with the key rules restated from V1. |
| `project/ProjectWriteRequest.java` | `ProjectInput` on the wire. |
| `project/ProjectService#createGranted` · `#viewOfCreated` | The company-scope gate, and the one view assembler reused. |
| `project/ProjectAccess#canDoInCompany` · `identity/IdentityProjectAccess` | The port's new question, answered through `evaluateCompanyWide`. |
| `project/ProjectController#create` | 201 / 400 / 403, and the `uq_project_key` race handler. |
| `project/ProjectCreateEndpointTest.java` | **New, 28 tests** over HTTP against real Postgres with RLS. |
| `features/projects/ProjectCreateDialog.tsx` · `ProjectsPage` | The form and the gated affordance. |
| `auth/useCan#useCanInCompany` · `components/PermissionGate` | The company-scope gate, client side. |
| `mocks/db/store.ts` · `handlers/projects.ts` · `handlers/auth.ts` | The project list becomes mutable; the mock honours the same three refusals. |

### The orientation pass re-derived the scope, and it removed two things the plan assumed

Both were "obviously needed" and neither is:

1. **No Flyway migration. `V13` was pre-assigned to this lane and is not used** — it is free for
   whoever needs it next. `project` exists with `uq_project_key` and `ck_project_key_format` since
   V1; `key_sequence` is per **company**, seeded by `tcms_seed_company_defaults`, so a new project
   needs no counter row; and `tcms_seed_project_defaults` already exists. Nothing in this slice
   changes a table.
2. **No `Idempotency-Key`, deliberately, one slice after §29 built one.** The case endpoint needed
   it because a case has no natural key and a retry would make a second row. A project's key is
   supplied by the caller and unique per company, so **the retry of a create that in fact succeeded
   cannot make a second project** — it is refused on the field. Reading a header nobody sends, or
   sending one nobody reads, is the divergence §29 exists to warn about; here the honest answer is
   that the endpoint does not need one.

### The interesting part: the only write in the application whose gate is not on its target

Every other write asks "may this caller do X **to that project**" and `ProjectService.writeGranted`
folds the project term in. There is no project here yet, so the question moves one level up:
`project:create` is a **COMPANY-scope** permission held by Company Admin and Instance Admin only
(roles spec §A3 — *"Test Lead → Company Admin: same powers, wider target, plus company configuration
and project creation"*).

- **The port grew `canDoInCompany`, not a nullable project id.** `PermissionEvaluator.evaluate`
  refuses a null project on purpose, because a null there silently answers a different question.
  `evaluateCompanyWide` — SYSTEM + COMPANY terms, no project's grants folded in — is the method
  whose own javadoc already named `project:create` as its reason to exist.
- **It is emphatically not "the union of what they hold on some project".** A Test Lead holds
  `project:settings`, `project:members` and `suite:delete` on the project they run. Every heuristic
  that asks "does this caller look powerful" says yes, and the answer is no.
- **`createGranted` has two outcomes where every other gate has three.** No target means nothing
  whose existence a 404 could conceal, so `Unreachable` is unreachable — and the arm is still
  written, throwing, so that a fourth `GateResult` state is a compile error rather than a silently
  mishandled outcome.

### No grant is minted for the creator, and the alternative is worse than unnecessary

The tempting move is to give the creator a project grant so they can see what they made. Both halves
of the argument matter:

- **Unnecessary.** Every route to `project:create` already carries `project:read` over the whole
  company — a Company Admin's COMPANY-scope grant covers "every project, present and future", an
  Instance Admin's SYSTEM term covers everything.
- **Wrong.** A PROJECT-scope grant is what `myRole` reports, and §22 settled that a Company Admin's
  `myRole` is **null**. Minting one here would make exactly one of their projects — the one they
  happened to create — claim a per-project role they do not hold.

That the two facts coincide is not enforced by any type, so it is enforced by a query:
`everyRoleThatMayCreateMayAlsoRead` derives from the seeded catalogue that no role holds
`project:create` without `project:read`, and fails the build the day a migration seeds one that
does — with an assertion message naming both repairs. **§6 lesson 9: a catalogue-wide invariant
needs a computed check, not a careful reading.** The observable half is asserted separately, over
the wire: the created project is in the creator's `GET /projects` on the very next request.

### The key is typed, not allocated — and a duplicate is a 400, not a 409

Every other identifier in the product is allocated from `key_sequence` (`TC-1`, `TS-2`). A project
key is not, and V1's own column comment says why: *"suggested from the name at creation"*, which only
makes sense for a value a human then edits. Allocating `P-3` would replace the one identifier in the
system meant to be memorable, to solve contention that does not exist at a handful of rows per
company.

The collision is therefore checked, and answered as a **field error on `key`**: the form has an input
to put it on and the caller's next action is to type a different key, not to reload. A lower-case key
is refused rather than upper-cased — a save that stores something other than what was typed
contradicts the screen that shows the key back a second later. The SPA upper-cases **as the user
types**, where they can see it happen.

### The permission is checked before the payload is read, and one test is the only thing that says so

The duplicate-key check reports whether a key is in use. Run it before the gate and the endpoint
answers "that key is taken" to a caller who may not create anything — a probe for the company's key
space handed to exactly the person just refused. `thePermissionIsCheckedFirst` pins it: a Test Lead
posting a key that *is* taken gets **403, not 400**. The ordering is also structural (validation runs
inside the callback `createGranted` invokes only when allowed), which is why no mutation of it exists
below.

### `tcms_seed_project_defaults` now has its second caller, and that is the rule being kept

CLAUDE.md and ERD §10 say *"provision-company calls them; nothing else should"*. **V2's own
`COMMENT ON FUNCTION` says the opposite in more detail** — *"called once per project, **by project
creation** and by provision-company for the first project"* — and the second is the reading this
slice takes. The rule's purpose is that there be **one definition of what a seeded project
contains**: inlining those inserts here would break it, and would leave a CLI-created project and an
API-created one subtly different the first time either list changed. Calling the one function keeps
it. A project created here and one created by `provision-company` are the same thing, and two tests
assert it through the API — six statuses in order, and the `feature` dimension beside the three
company vocabularies.

### The error envelope is `ProblemDetail`, because a third copy was the alternative

`repository.ApiProblem` is package-private and `InvitationController.ProblemBody` is a second copy;
`ApiProblem`'s own header records that the right home is a `shared` module that does not exist. That
module is a real decision — it needs a package, a Modulith declaration, an entry in
`ArchitectureRules.FOUNDATION_MODULES` and an edit to `ModuleStructureTest`'s pinned module list —
and it is not this slice's to make quietly. Spring's own `ProblemDetail` serializes to exactly the
`Problem` shape the contract declares, so `project` needs no envelope of its own and **the eventual
extraction has one fewer copy to reconcile, not one more.**

### Mutation-verified — five mutations, and the survivor proves a handler no test can reach

| Mutation | Result |
|---|---|
| Gate asks `company:read` instead of `project:create` | **Survived at first — no fixture could see it** (below) |
| …after adding the company-wide Viewer persona | **Killed**, 1 test |
| `tcms_seed_project_defaults` not called | **Killed**, 2 tests |
| Audit `changes` concatenated without `AuditEvent.jsonString` | **Killed**, 1 test |
| Duplicate-key pre-check skipped entirely | **Survived, benignly and usefully** (below) |

**The first survivor is the instructive one.** `LEAD` and `NO_GRANT` hold *nothing* company-wide, so
they answer 403 for **any** company-scope permission and cannot tell `project:create` from
`company:read`. The fixture needed a persona holding a COMPANY-scope grant of a role that is not
Company Admin — Viewer over the whole company, which is how a client's stakeholder is given read
access to everything. They hold `company:read` and `project:read` company-wide and not
`project:create`, and they are now the only fixture that fails when the gate names the wrong key.
§29's and §30's lesson a third time: a test can exercise the right code path and be structurally
unable to observe the defect, and only the mutation says which kind you wrote.

**The second survivor is defence in depth working.** Delete the pre-check and the create still
answers 400 with the identical message on the identical field — because `uq_project_key` fires and
`ProjectController.onKeyRace` maps it back. So the mutation is genuinely benign, and in surviving it
demonstrates that the race handler is live rather than decorative, which is the one thing the suite
otherwise could not show (reproducing the race needs two interleaved transactions — the same
limitation §28's unprovable 409 and §29's unprovable UI retry record).

### The frontend half — the second scope `useCan` said it would need

`useCan`'s header has said since §31 that *"there is no company-wide variant here because no screen
needs one yet"*. This is that screen. `useCanInCompany` reads `GET /me/permissions` with no
`projectId`, and **the three states, the fall-open on failure and the asymmetry behind it are shared
in one `useGate`** — so the policy cannot be changed in one scope and not the other. `PermissionGate`
takes `projectId="company"`, a literal rather than a second component, because the tooltip wrapper is
the part that is easy to get wrong once per call site.

Both hooks run on every render — a hook cannot be called conditionally — so the company query carries
an `enabled` flag. Without it, every gated button in the app would fire the company question, which
is a request per screen to answer something no screen there is asking.

### The mock renders the *disabled* branch, and that is stated rather than papered over

The seeded persona is an ordinary member: Test Lead on one project, Viewer on another, no
company-level powers. So `companyScopePermissions` is `['company:read']` and **"New project" renders
disabled with its reason in mock mode** — an honest answer for this persona, and the branch that
proves the gate. The enabled branch is unreachable there; adding `'project:create'` to that one
array drives it, and the fixture says so in its own comment. Granting it unconditionally would be
§31's defect in reverse: a fixture that can only ever render one branch of a two-branch affordance.

The mock's create handler mirrors the three refusals from the same constant the permissions handler
answers with, so the disabled button and the refused write cannot disagree — the divergence class the
`If-Match`-in-the-body and unread-`Idempotency-Key` findings both belong to. `db.projects` is now
mutable, because a create that does not land in the list is exactly the lie the store exists to
prevent.

### Gate evidence

**Backend** — `./gradlew build` clean with `-Werror`, **607 tests** (579 + 28), against Testcontainers
Postgres 17 with the non-superuser owner and RLS in force. The 28 cover: both 403s and the sentence
that names no permission key; the Instance Admin path with no membership row anywhere; the
company-wide Viewer refusal; permission-before-payload; the creator's immediate visibility in list
and detail; `myRole` null; a Test Lead's continued 404 on the new project; **isolation** — the other
company sees neither the row nor the id, and both companies may key a project `PAY`; a `companyId` in
the body changing nothing; the six statuses and the Feature dimension; the audit row with a name
carrying a quote and a backslash; and every validation branch including that a refusal writes nothing.

**Frontend** — `npm run build` clean, `npx oxlint` **0 warnings**, entry chunk unchanged
(`index` 253.35 kB / 79.80 kB gzip).

**Not done: a live browser pass.** Stated rather than implied, and it is the honest gap in this
slice — the irony being that this slice is precisely what makes the next one cheap, because a
company can now be provisioned and given a project without psql. Two things want eyes on them
specifically:

1. **The key field's live upper-casing.** `register('key', { onChange })` + `setValue` is a runtime
   graph, not a type, and §30's dialog defect and §6 lesson 6 are both exactly that. Its failure
   mode is benign — the user sees the format message on blur — but it is unverified.
2. **The disabled "New project" and its tooltip**, which MUI makes silently wrong when the wrapper
   is missed (`PermissionGate` owns the wrapper, and this is its first company-scope call site).

### Deliberately not done

- **No rename, describe, archive or delete of a project.** `Project` has `rename`/`describe`/
  `archive` and no endpoint calls them, unchanged from §22. Creation first, because it is what
  blocks; archival needs a decision about what an archived project's screens do.
- **`project.created_by` is still unmapped.** The column exists in V1 and the entity ignores it.
  Mapping it would change the `Project.create` factory that `CompanyProvisioningService` also calls —
  outside this lane — and nothing reads it: the actor is on the `audit_event` row. Same open gap as
  §28 finding 1 for cases, and it should be closed for both at once or neither.
- **No pagination on `/projects`**, unchanged from §22, and now slightly less theoretical since the
  list can grow from the UI. The contract declares a bare array; it would have to change first.
- **The `shared` module.** Named above; it is a module-list decision, not an import.
- **`mocks/handlers/workItems.ts` falls back to `projects[0]`** for an unknown project id, and a
  newly-created project is now the first id that can reach it — so its board would render another
  project's work items in mock mode. Mock-only, pre-existing shape, and in the `work` domain rather
  than this lane's; recorded rather than absorbed.

### Next

1. **The live browser pass this slice unblocks** — provision a company, create a project from the
   picker, create a suite in it (§30), create a case in it (§29), delete it (§32). That is the first
   end-to-end path through the product that needs no hand-written SQL at all, and it is now one
   session's work rather than a blocked one.
2. **`GET …/cases/{caseId}/versions` with the detail page's history panel** — `useCaseVersions` is
   seven slices old and still the oldest dead hook.
3. **The `execution` module** — still the one screen with a live link that 404s.
4. Frontend **Phase 4 (Vitest + RTL)** — `useGate` is now a better first target than `useCan` was:
   two scopes, one policy, and the fall-open rule is a pure function of the query state.
