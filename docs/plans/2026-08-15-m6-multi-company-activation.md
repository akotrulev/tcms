# M6 — Multi-Company Activation: Master Plan

**Date:** 2026-08-15 · **Written by:** `ep-writing-plans` · **Branch:** `worktree-m6-multi-company`

> **This is the master plan, not an executable one.** It owns the *ordering*, the four decisions
> taken on 2026-08-15, the cross-lane resource assignments (Flyway versions, fixture UUID prefixes,
> the contract holder) and the scope boundaries. The bite-sized task lists live in the five child
> plans named in §3. **Do not implement from this file** — implement from a child plan, with
> `ep-executing-plans`.

**Goal:** Turn a schema that has been multi-company-correct since M0 into a product a second company
can actually be onboarded into and log in to — switcher, `/c/{slug}` routing, invitations,
per-company identity, branding, honoured suspension — plus the Instance-Admin console that
provisions and administers companies from a browser.

**Architecture:** M6 is almost entirely *additive surface* over machinery that already exists. The
`company_id` discriminator, the Hibernate filter, RLS, `CompanyContext`, `company_membership`, the
invitation table, the `COMPANY` role scope and the seven SYSTEM permissions all shipped in M0–M3.
What M6 adds is (a) the company as a thing the *session* and the *URL* can name and change, (b) an
`identity` federation seam pulled forward from M4, (c) `company` columns that have never had a
reader, and (d) two new route trees — `/c/{slug}/…` and `/instance/…`.

**Tech Stack:** Spring Boot 3.5.6 · Java 21 · Flyway · Spring Security (OAuth2 Client + Resource
Server) · React 19 · TanStack Query · MUI v7 · React Router 7 · Vitest + RTL

---

## 1. What is already built — read this before believing any milestone row

Three documents describe M6 as larger than it is, because they were written before the work that
overtook them. Verified by grep on 2026-08-15 at `8c2bacb`:

| Claim in the docs | Actual state |
|---|---|
| §6s lifecycle table: *"`COMPANY`-scope assignment UI — M6"* | **Already built.** `GrantRoleDialog.tsx:52,79,85,197` offers *"The whole company"* as a scope; `RoleAssignmentTable.tsx:146-152` renders and gates it; `POST /role-assignments` with a null `projectId` is a COMPANY grant gated on `company:members` (`openapi.yaml:5466`). Landed with §A1.1's inheritance work on 2026-08-02. **This row is stale and M6 owes it nothing.** |
| M6: *"invitation & membership flow"* | **Built, company-scoped.** `membership_invitation` (`V6:59`), `MembershipInviteService`, `GET/POST /api/v1/auth/invitations/{token}`, `AcceptInvitationPage.tsx`, `InviteUserDialog.tsx`. What is missing is only *inviting from outside a company* (operator console Task 10) and the instance-level variant, which `membership_invitation.company_id NOT NULL` (`V6:65`) structurally forbids. |
| `company.slug` needs adding | **Exists since V1** (`V1:31`, unique + kebab CHECK at `:46-47`), and its own column comment already names `/c/{slug}` and `/{slug}/login`. `Company.java:35` maps it `updatable = false`. |
| Branding needs columns | **Columns exist since V1** (`V1:35-38`). They are **unmapped on the entity** (`Company.java:30-50`) and have no writer — `ReportHeader.java:55-56` says so in as many words. |

Genuinely not built, at all: the company switcher (endpoint, token re-mint, UI), any company
identity on `CurrentUser`, `/c/{slug}` routing, suspension enforcement, branding beyond the columns,
per-company metric tags, every byte of federation, and every byte of `/instance`.

**One latent defect to keep in view all milestone long.** `Company.suspend()` has had a writer and
**no reader anywhere in `src/main`** since V1 — confirmed again on 2026-08-15; every other hit is a
comment, including `PermissionEvaluator.java:384-388`, which records that the permission gate
deliberately does *not* honour it. Nothing is broken today because nothing exposes suspension. It
becomes a defect the instant a suspend control ships. **Plan B Task 1 must land before Plan E's
suspend button, never after it.**

---

## 2. The four decisions taken on 2026-08-15

Asked and answered before any code was written. Recorded here because three of them enlarge the
milestone and one of them changes the permission catalogue, which is the most-guarded artifact in
this repository.

### D1 — M4's federation is pulled forward into M6

**Chosen: build it.** §6r is explicit that external identity is M4 and equally explicit that none of
it exists (zero `oidc`, zero `saml`, zero `ldap` in `backend/src/main` — re-verified 2026-08-15;
`identity_provider` appears in no migration). M6's *"per-company IdP routing"* is a routing layer
over a provider registry that does not exist, so the alternative was to ship routing that routes to
nothing.

**Scope boundary, and it is a real one.** §6r itself divides its own list:

| §6r element | In M6 | Why |
|---|:--:|---|
| `identity_provider`, `user_identity`, `claim_role_mapping` tables, keyed `(company_id, issuer, subject)` | **yes** | The seam. Deferring the key shape is the account-takeover retrofit §6r names. |
| One generic OIDC client — Authorization Code + PKCE, server-held refresh, TCMS mints its own session | **yes** | §6r: *"one generic OIDC client driven by per-provider configuration, not seven integrations."* Cognito / Entra / Google / Okta / Keycloak are **configuration and claim-mapping rows**, not adapters. |
| Provider-specific traps: Cognito `token_use=access` and no `aud`; Google `hd`; Entra group-claim overage | **yes** | Each is a config field or a claim-mapping branch, not a code path. |
| Claim → role mapping, IdP-derived grants read-only, unmapped claims grant nothing | **yes** | This is the seam where SSO can destroy §6b. It cannot land later than the login it gates. |
| JIT provisioning, default **invitation-required**, per company | **yes** | One boolean and a branch. Defaulting it wrong is a side door wider than the invite flow. |
| **LDAP / Active Directory** | **NO — deferred, flagged** | §6r: *"not an OIDC provider with different settings — it is a directory you bind against."* Different protocol, different key (`objectGUID`), different group resolution (a search, with nested-group recursion and AD referrals/paging), different deprovisioning (a scheduled poll). It reuses `user_identity` and `claim_role_mapping` **unchanged**, so it is additive after M6 at no retrofit cost. |
| **SAML 2.0** | **NO — deferred, flagged** | §6r already says *"a second code path — build only on demand, and say so rather than implying parity."* Nothing has demanded it. |
| Outbound integration credentials (Jira/Slack/GitHub OAuth, `integration_credential`) | **NO** | A different concern that shares the word OAuth (§6r's own first table). It belongs with the integrations it authorizes, in M4. |

> **Flagged for the user:** deferring LDAP/AD and SAML is my call, not yours — you asked for
> "M4's federation", and §6r lists LDAP as in-scope for M4. I scoped them out because both are
> separate protocols rather than configuration, and because `user_identity` lands in M6 with the key
> shape they need, so adding them later costs nothing that adding them now would save. **Say so and
> I will fold either or both into Plan C** — LDAP is roughly the size of Plan C again; SAML is
> larger.

### D2 — `instance:read` is added to the catalogue

**Chosen: add it.** This overrules the operator-console plan's own recommendation (`:84`), which
proposed gating the console's reads on `company:create` and called that *"the smaller lie"*. You
took the cleaner gate instead.

The catalogue goes **55 keys → 56**, the first key added since `V3` seeded the catalogue at M0. That
sentence appears verbatim in several places and every one of them becomes false, so the ripple is
not optional and it is not small. Complete site list, all of which land in **one commit** (Plan A):

| Site | Change |
|---|---|
| `backend/src/main/resources/db/migration/V53__instance_read.sql` | `INSERT` the key at `'system'` scope. Instance Admin's grants are derived from the catalogue (`V3:167-172`), so **no grant row is needed** — it is held the moment it exists. Assert that in the migration header. |
| `docs/specs/2026-07-30-tcms-roles-permissions-naming.md` §A3 SYSTEM table (`:277-285`) | New row. **This is the authority; edit it first.** |
| §A7 / the change log table at `:592` | A dated row, in the established form. |
| `CLAUDE.md` — the `SYSTEM` scope sentence | It is prose, not a table, and `matrixCopies.test.ts` compares it anyway (§215). Seven keys become eight. |
| `README.md` — the same copy | Same edit. |
| `docs/specs/openapi.yaml` — `Permission` enum (`:10833` region) | Add the member, then `npm run gen:api` in **both** `frontend/` and `packages/client/` — the two `schema.d.ts` are byte-identical and `schemaFreshness.test.ts` compares them. |
| `frontend/src/lib/permissions.ts` | `satisfies Record<PermissionKey, …>` makes this a **compile error** until the key is placed in the `'system'` area and described. That is the file working as designed. |
| `backend/.../SeedDataTest` | `theSeededMatrixIsTheSpecsMatrix` (§214) compares 385 cells and will compare 392. `areasMatchTheSpa` compares `permission.area` against `permissions.ts`. Both go green by the edits above; **neither needs a new exemption**, and if one seems to, stop — that is the finding. |
| `packages/client/src/test/roleMatrix.ts` + `matrixCopies.test.ts` | Parse the spec; no source change expected. **Run them and confirm** rather than assuming. |

**The gate wiring itself:** `InstanceOperatorGate` demands `instance:read` for every read and keeps
`company:create` / `company:suspend` / `user:manage` for the writes they actually name. That is the
whole point of paying for the key.

### D3 — the whole milestone is planned before any of it is executed

This file plus the five child plans of §3. Nothing is implemented until they exist.

### D4 — checkpoint after every task

`ep-executing-plans`' default. Not per phase.

### D5 — carried over from the operator-console plan's own sign-off list, accepted

`instance_bootstrap_invitation` goes on the **ERD §0.1 RLS exception list**, for the same reason
`membership_invitation` is on it: the accept path runs *before* authentication, so no company
context can exist to filter by. One table serves both the first-boot bootstrap and one operator
inviting another; they differ by one nullable `issued_by` column, not by a table.
`SchemaConventionTest` derives from §0.1, so **§0.1 is edited first and the test follows**.

---

## 3. The five child plans, and why the seams fall there

Each produces working, testable software on its own and can be committed, gated and pushed
independently. They are ordered by dependency, not by size.

| # | Plan | File | Depends on | Rough size |
|---|---|---|---|---|
| **A** | **The catalogue and the session learn about companies** | `docs/plans/2026-08-15-m6a-company-in-the-session.md` | — | 3–4 days |
| **B** | **Suspension, branding, and the company's own settings** | `docs/plans/2026-08-15-m6b-company-lifecycle-and-branding.md` | A | 4–5 days |
| **C** | **Federation — providers, identities, claim mapping, login routing** | `docs/plans/2026-08-15-m6c-federation.md` | A | 8–10 days |
| **D** | **`/c/{slug}` and the switcher in the SPA** | `docs/plans/2026-08-15-m6d-slug-routing-and-switcher.md` | A, B | 4–5 days |
| **E** | **The instance operator console** | `docs/plans/2026-08-07-instance-operator-console.md` (**existing — revalidated and amended, see §6**) | A, B | 8–10 days |

### Plan A — the catalogue and the session learn about companies

The foundation every other plan reads. Deliberately small and deliberately first.

- `instance:read` and its nine-site ripple (D2), as one commit.
- `CurrentUser` grows the **active company** — id, slug, display name, status — and the
  **membership list**, which `CompanyMembershipRepository.findCompanyIdsForUser` (`:70,85`, over
  `tcms_companies_for_user`, `V8:57`) already answers and which today has exactly one caller:
  `AuthenticationService.defaultCompanyOf` (`:99,111-126`). *The query exists and is exposed to
  nobody.*
- `POST /api/v1/auth/session/company` — the switch. Re-mints the token with a new `co` claim
  (`SessionTokens.java:95` is *"the only place a claim set is assembled"* and stays so), **refuses a
  company the caller is not an ACTIVE member of with 404, never 403** (§6s: a 403 confirms the
  company exists), and clears the permission cache, which is keyed including the company id.
- `company_membership.display_name` becomes the **resolution rule for every person-rendering site**
  (§6u). Scoped honestly in Plan A: the *resolver* and its test, plus an inventory of the sites, and
  a rule that fails the build when a new site reads `app_user.canonical_name` directly.

### Plan B — suspension, branding, and the company's own settings

- `SuspendedCompanyFilter` (operator-console Task 1, **pulled into B** so the reader exists before
  either console or switcher can expose the state). Instance Admins exempt, or suspension is
  unrecoverable through the console that exists to reverse it.
- The four branding columns get mapped, a writer, and `GET/PUT /api/v1/company/branding` gated on
  `company:read` / `company:settings`.
- **Contrast validation at save time** (§6u) — derive the MUI palette from two colours, compute
  contrast-safe foregrounds, reject or auto-adjust a colour that cannot meet WCAG AA and *tell the
  admin what was adjusted*. Semantic Pass/Fail/Blocked colours are **not brandable** and a test says
  so.
- Logo + favicon through the existing `storage` module and its `company/{companyId}/` prefix.
- Per-company Micrometer tags on request, ingestion and report timers. Note: `spring-boot-starter-actuator`
  is a dependency (`build.gradle.kts:23`) and there is **no custom instrumentation at all** today —
  so this is a first meter, not an extra tag on an existing one.

### Plan C — federation

The largest, and the only one with a genuinely new external protocol. Sequenced so the *seam* lands
before the *client*: tables and key shape → provider CRUD under `company:idp` → the OIDC client →
claim→role mapping → JIT provisioning → login routing (`email_domain → company`, `/{slug}/login`,
multi-match disambiguation *after* authentication, and an identical response when the domain maps to
none, so login is never an oracle for the client roster).

### Plan D — `/c/{slug}` and the switcher

`router.tsx:25-30` already records that flattening was deferred *"so the whole URL shape — including
the `/c/{companySlug}` segment that arrives with backend M6 — gets designed once rather than
twice."* This is that once. The slug is **validated against the session, never trusted as its
source** (`CompanyContextFilter.java:29-31,62-67`, ERD §0.1 point 2); a mismatch switches if the
caller is a member and 404s if not.

### Plan E — the instance operator console

The existing plan, revalidated. See §6.

---

## 4. Cross-lane resource assignments — pre-assigned, per CLAUDE.md

Two lanes each adding a `V53__*.sql` produce two *filenames* and no merge conflict at all, and
`out-of-order` is unset so the loser is refused on any already-migrated database. Testcontainers
cannot see this. Therefore:

| Version | Owner | Subject |
|---|---|---|
| `V53` | **Plan A** | `instance:read` |
| `V54` | **Plan B** | branding writer support (see the plan for whether any DDL is owed — the columns exist; a `company_setting` touch may not be) |
| `V55` | **Plan C** | `identity_provider`, `user_identity`, `claim_role_mapping`, `company_email_domain`, `role_assignment.source` |
| `V56` | **Plan E** | `instance_bootstrap_invitation` |
| `V57` | reserve | unassigned; take it rather than reusing one above |

**Renumber at integration in landing order** if a lane lands out of sequence.

**Contract holder:** `docs/specs/openapi.yaml` and `npm run gen:api` belong to **one lane at a
time**. Resolve the *contract* first, then regenerate; never hand-merge `schema.d.ts` — it is
generated and two regenerations do not merge.

**Fixture UUID prefix space** (hand-managed, no check behind it, collided five times — §19…§62):

| Prefix | Plan |
|---|---|
| `d0000000-…` | A |
| `d1000000-…` | B |
| `d2000000-…` | C |
| `d3000000-…` | D |
| `d4000000-…` | E |

**Orientation caches:** `docs/agent-context/be-java.md` and `fe-react.md` have **one writer**. Update
them in the slice's own commit, and if two lanes are ever live at once, only the backend lane touches
`be-java.md`.

---

## 5. Gates, and where they run

Unchanged from CLAUDE.md, restated because this milestone touches all three stacks:

```bash
cd backend  && ./gradlew build                      # -Werror + every test; CI does NOT run these
cd frontend && npm run build && npm run lint && npm run test:run
cd packages && npm run build && npm run lint --workspaces && npm run test:run --workspaces
cd frontend && npm run gen:api                      # after ANY openapi.yaml edit
cd packages/client && npm run gen:api               # the second copy — both, always
```

Backend tests need `docker compose up` (Testcontainers, real Postgres 17). `~/bin/tcms-remote
backend ./gradlew build` runs the same gate on `shanks` — a **Linux** observer, which is the shape
that catches the microsecond-vs-nanosecond class this repo lost seven CI runs to on 2026-08-01.
Worth using at least once per plan.

**This milestone runs in a worktree** (`.claude/worktrees/m6-multi-company`, branch
`worktree-m6-multi-company`) because other agents are live in the primary checkout. Consequences
that bite: one `backend/build/` and one Gradle daemon cannot serve two lanes, `docker compose` from
here would derive the project name `m6-multi-company` (fine — it will not collide with `tcms`), and a
pushed worktree branch is **backed up, not observed**: `ci.yml` triggers on push to `master` and PRs
targeting it, `tests.yml` runs nightly on the default branch only.

---

## 6. What changes in the existing operator-console plan

`docs/plans/2026-08-07-instance-operator-console.md` is sound and stays the executable source for
Plan E. Six amendments, to be written into it as a revalidation block before Task 1 runs:

1. **Flyway `V39` → `V56`.** The plan predicted this (`:30`: *"certainly higher by M6"*). Highest on
   disk is `V52`.
2. **Task 1 moves to Plan B.** Suspension enforcement is M6 work the console pulled forward; it now
   sits with the suspend/reinstate service it protects, and Plan E's Task 6 depends on it.
3. **D2 overturns the plan's own `instance:read` sign-off** (`:84`). `InstanceOperatorGate` (Task 2)
   demands `instance:read` for reads. The plan's argument — *"gating reads on an existing write
   permission is the smaller lie"* — is recorded as **superseded rather than wrong**, per the working
   agreement.
4. **Its Orientation finding 2 needs re-reading, not re-deriving, but one clause has moved:**
   `isSystemAdmin` is still consumed by nothing in `frontend/src` outside test fixtures — but it *is*
   consumed for real in `packages/mcp/src/session.ts:101,217`. The claim as written is about the SPA
   and is still true there.
5. **Known gap #8 — *"the company switcher is still not built"* — is now Plan D** and no longer a gap.
6. **Task 10's invite path is unchanged and stays unchanged.** `MembershipInviteService` is called
   inside an opened `CompanyContext`, because `PermissionEvaluator.evaluateCompanyWide` starts from
   the SYSTEM term and the company gate only *adds* to it (finding 6). Re-verify that sentence before
   relying on it; do not write a second invite path.

---

## 7. What this milestone deliberately does not do

Named so they are decisions rather than omissions:

- **No self-service signup.** §6s's other half stands: companies are provisioned only by an Instance
  Admin, and the company roster is never exposed pre-authentication.
- **No cross-company view for anyone below Instance Admin** — no unified inbox, no combined board,
  no "my assignments everywhere". §6s calls this the fastest way to leak one company's data into
  another's UI.
- **No company rename, delete, quota or archive UI.** Suspend covers offboarding; deletion is a
  scripted export plus a scripted purge, not a button.
- **No LDAP/AD, no SAML, no outbound integration credentials** — D1, flagged there for overturning.
- **No support impersonation.** §6s: if ever built, explicit and time-boxed, never a silent session
  swap. Not v1.
- **Per-company concurrency caps and storage quotas stay deferred** to real contention (§6s's
  watch-it-then-fix-it list). Only the metric tags ship, and they ship in Plan B precisely so the
  question *"which company is making the instance slow"* is answerable the first time it is asked.

---

## 8. Self-review

**Spec coverage** — every M6 element in the milestone table (`:2065-2066`) maps to a plan:
switcher → D · `/c/{slug}` → D · session switching → A · invitation & membership flow → **already
built**, plus E Task 10 · `COMPANY`-scope assignment UI → **already built, row is stale** · per-company
IdP + email-domain routing → C · suspend honoured → B · per-company branding (§6u) → B ·
per-company metric tags → B · operator console → E. §6u's per-membership display names → A.

**One gap I am recording rather than closing:** §6s requires that *"instance-operator reads and
writes inside a company land in that company's audit log, flagged as operator access."*
`instance_audit_event` has an entity and a repository and **no reader surface**, and the console plan's
own gap #5 leaves `instance:audit` unreachable. Plan E writes the events; **nothing in M6 as scoped
renders them.** That is a disclosed capability with no disclosure. It belongs in Plan E and I will
size it there.

**Placeholders:** none — this file assigns and decides; it contains no implementation steps by
design, and the implementation steps live in the child plans.

**Type consistency:** deferred to the child plans, where the signatures are actually declared. The
one cross-plan contract is `CurrentUser`'s new shape (Plan A), which B, D and E all read; it is
defined once, in A, and every other plan cites it rather than restating it.
