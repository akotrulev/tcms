# TCMS — Roles, Permissions & Naming Conventions

**Status:** Decided 2026-07-30 · authoritative for the M0/M0.5 seed migration
**Source:** `docs/plans/2026-07-30-tcms-high-level-plan.md` §6b (roles), §6s (tenancy), §6t (display keys), §6u (branding & names), §6d (vocabularies)

All decisions in this document were taken on 2026-07-30. Where a decision reverses or corrects the plan, the plan sections listed above need the corresponding edit — tracked in *Plan edits required* at the end.

---

## Part 0 — The hierarchy, stated precisely

Getting this wrong propagates into every example, so it is first:

| Level | What it is | Example |
|---|---|---|
| **Instance** | One deployment, operated by you | — |
| **Company** | **A client organisation.** The isolation boundary. | Acme Corporation |
| **Project** | **A full client project** — an entire product or engagement under test. Not a feature, not a module. | Acme Online Banking · Acme Mobile App · Acme Partner Portal |
| **Suite / folder tree** | Structure *within* one project | `Onboarding` › `KYC checks` |
| **Feature dimension** | The classification axis carrying feature-level meaning (§6d) — hierarchical, project-owned | `Payments` › `Refunds` › `Partial refund` |

So "Payments" is a **Feature** (or a suite folder) inside a project — never a project of its own. A company typically holds a handful of projects, not dozens, which is part of why company-scoped display keys work comfortably.

---

## Part A — Roles & Permissions

### A1. The scope model

Three scopes. A `RoleAssignment` grants one role to a **user or a team** on one target.

| Scope | Target | Who assigns | Notes |
|---|---|---|---|
| `SYSTEM` | The instance | — (bootstrapped) | Instance operator only. |
| `COMPANY` | One company | Instance Admin | Applies to **every project in that company, present and future**. |
| `PROJECT` | One project **or the whole company** | Company Admin, or Test Lead of that project | The finest boundary. No per-suite or per-case ACLs. Granted with **no project** it is *inherited* — see §A1.1. |

Two gates, both required before any row is visible:

```
1. company filter   →  is this row's company one I'm a member of?   (§6s)
2. project grant    →  do I hold any role on this specific project?  (§6b)
```

**Company membership grants authentication into a company and zero project visibility.** A regular user sees only the projects they hold a grant on — not the company's project list, not names in a picker, not counts on a dashboard. With projects being whole client engagements, this is a confidentiality boundary, not just tidiness.

Effective permissions are a **union of allows with no explicit deny**, gated on company membership —
with exactly one exception, §A1.1.

### A1.1. Inheritance — a PROJECT-scope role held company-wide

**Added 2026-08-02.** A PROJECT-scope role may be granted with **no project**, and then means *this
role, on every project in the company, unless that project says otherwise*. One grant staffs a person
who works across the whole portfolio, instead of one grant per project and another for every project
created afterwards.

~~The four PROJECT-scope roles cannot be granted at COMPANY scope (`RoleGrantService.scopeError`
refuses the mismatch, and it should: Viewer granted company-wide would confer `project:read` and
`case:read` over **every project present and future**, which is exactly the boundary §A1 draws).~~
→ **Superseded.** The consequence described is real and is now the stated intent rather than an
accident. What makes it governable is the override below; the old reasoning assumed a company-wide
grant would be irrevocable across the portfolio, and it is not.

**The rule, and it is the one place in this model where adding a grant REMOVES a permission:**

| The caller has | On a project with its own grant | On a project without one |
|---|---|---|
| Company-wide Test Lead only | — | **Test Lead** (inherited) |
| Company-wide Test Lead **+ project Viewer** | **Viewer** — the project grant *replaces* it, downward | **Test Lead** |
| Company Admin **+ project Viewer** | **Company Admin**, unchanged | Company Admin |

Three properties this depends on, each with a test:

1. **Replace, not union.** A project grant wins outright, including when it confers less. Union is
   still the rule *within* a level — two project grants on one project, or two company-wide ones,
   accumulate as before. Only the boundary between the levels is an override.
2. **COMPANY-scope roles and SYSTEM are never overridden.** Company Admin is authority over the
   company, not a default for its projects. Without this, anyone holding `project:members` could
   neutralise the company's administrator by granting them Viewer on a project — a privilege
   inversion of the §A3 shape.
3. **Inheriting every project is not company-level authority.** `evaluateCompanyWide` reads the
   SYSTEM and COMPANY terms only, so a company-wide Test Lead holds `project:settings` on every
   project and still cannot create one or change company settings. This extends the rule that method
   already stated, rather than adding an exception to it.

The switch is *whether a grant naming the project exists*, never *what it confers* — a check written
as "are there project permissions" would union the two and silently undo every restriction, and a
role granting nothing would fail to override at all.

**Company membership still grants zero project visibility.** Inheritance is something an
administrator does deliberately; joining a company confers only the `Company Member` baseline
(§A2).

### A2. Roles

**Naming decided: `Company Admin` (COMPANY) + `Test Lead` (PROJECT).** "Admin" and "Lead" signal different kinds of authority, and Test Lead is the standard TCMS term. No audit line ever needs a scope qualifier.

| Role | Scope | Built-in | Intent |
|---|---|---|---|
| **Instance Admin** | SYSTEM | yes | The operator. Everything, every company. Provisions companies, appoints their admins. Reach into client content is audited in *the client's* trail. |
| **Company Admin** | COMPANY | yes | Everything in the companies they're assigned to; nothing outside. Creates projects, manages teams/members/vocabularies/IdP/tokens. Appoints Test Leads. |
| **Test Lead** | PROJECT | yes | Full control of their project(s). Company Admin scoped to one project. |
| **Test Engineer** | PROJECT | yes | The working contributor role, **granted explicitly — never a default**. Creates and edits everything, executes, reviews, files defects, maintains the shared library. Cannot approve, delete others' work, amend others' results, close runs, or change settings. |
| **Automation Service** | PROJECT | yes | Non-human. Attached to API tokens, never people. |
| **Viewer** | PROJECT | yes | Read-only, plus commenting and report export. **The default everywhere.** |

**These six are the complete set of *assignable* roles in v1.** Seeded, immutable, **not cloneable — no custom roles at all**, not even clone-and-edit. A client needing something between two tiers gets the higher one, or the built-in boundary is drawn wrong and should be fixed. Enforcement is permission-based (nothing checks a role name), so adding a role designer later is additive — phase 3, on evidence of demand.

**A seventh row exists in `role` and is not one of the six** — added 2026-08-01 by `V15`, and it is the mechanism §A3's "`company:read` for every member" needed rather than a tier:

| Role | Scope | Built-in | Assignable | Intent |
|---|---|---|---|---|
| **Company Member** | COMPANY | yes | **no** | Exactly `company:read`, granted automatically to every member on joining. Never offered, never revocable. |

It is not in the table above because nobody ever *chooses* it: it is granted by the act of joining, it is filtered out of `GET /roles`, `POST /role-assignments` refuses it, and `GET /role-assignments` does not list its grants — so nothing renders a Revoke whose effect would be to take away a colleague's sight of the company with no repair short of the operator CLI. `openapi.yaml`'s `Role.name` enum therefore stays at six names, and the six are still the whole of what an administrator can do.

**Why it had to be a new role rather than a reuse.** ~~The four PROJECT-scope roles cannot be granted at COMPANY scope (`RoleGrantService.scopeError` refuses the mismatch, and it should: Viewer granted company-wide would confer `project:read` and `case:read` over **every project present and future**, which is exactly the boundary §A1 draws)~~ → **superseded 2026-08-02 by §A1.1**: they can be, and that is now the inheritance feature. The conclusion here still holds for a different reason — a company-wide Viewer *does* read every project, and `Company Member` must convey nothing about any project, so the two remain different roles rather than one. Company Admin confers everything. **It is not the "company-wide read-only stakeholder"** handoff §39 anticipated as a possible seventh role — that one reads every project; this one grants nothing about any project, which is what makes it safe to hold universally. Both could exist; they are different roles.

**Viewer is the default at every entry point:** accepting a company invitation, SSO JIT provisioning, and project assignment. One rule at both levels — the cost of an unintended Viewer is nothing; the cost of an unintended contributor is edits to a client's test assets.

### A3. Permission matrix

`✓` granted · `—` not granted · `OWN` only for content the caller authored

**IA** Instance Admin · **CA** Company Admin · **TL** Test Lead · **TE** Test Engineer · **AS** Automation Service · **V** Viewer

#### Repository

| Permission | IA | CA | TL | TE | AS | V |
|---|:--:|:--:|:--:|:--:|:--:|:--:|
| `suite:manage` *(create, rename, move, reorder)* | ✓ | ✓ | ✓ | ✓ | — | — |
| `suite:delete` *(folder + subtree)* | ✓ | ✓ | ✓ | — | — | — |
| `case:read` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `case:create` | ✓ | ✓ | ✓ | ✓ | quarantine only | — |
| `case:edit` | ✓ | ✓ | ✓ | ✓ | — | — |
| `case:delete` | ✓ | ✓ | ✓ | OWN | — | — |
| `library:manage` *(shared conditions & steps)* | ✓ | ✓ | ✓ | ✓ | — | — |

**`suite:delete` split out** — deleting a subtree is the most destructive repository action, so it sits with leads while contributors keep organising the tree freely.

**`case:delete` is `OWN` for Test Engineer, in any state** — authorship confers full control of that case. Soft delete everywhere keeps this recoverable.

**`library:manage` is Test Engineer+, gated by an impact modal, not by permission.** Editing a shared condition or step propagates to every case referencing it, so the control is *informed consent*: the editor sees a confirmation listing the affected cases before the change commits. A permission gate here would just route routine maintenance through a lead.

#### Review

| Permission | IA | CA | TL | TE | AS | V |
|---|:--:|:--:|:--:|:--:|:--:|:--:|
| `case:comment` | ✓ | ✓ | ✓ | ✓ | — | ✓ |
| `case:review` · `suite:review` · `plan:review` | ✓ | ✓ | ✓ | ✓ | — | — |
| `case:approve` · `suite:approve` · `plan:approve` | ✓ | ✓ | ✓ | — | — | — |

Three tiers now, not two:

1. **`case:comment`** — a Viewer may leave comments. Built for the case where the *client* reviews test coverage themselves without gaining any write access. **It is ONE key covering all three reviewable artifacts** — a case, a suite *and* a plan — decided 2026-08-02 when `review_comment` got its first writer (handoff §79). The row above is a tier, not a per-artifact judgement: it is spelled *"read **an item** in full and leave threaded comments"* while `*:review` and `*:approve` are spelled per type, and adding `suite:comment`/`plan:comment` would seed two keys granted to **nobody**, making a comment on a folder a 403 no administrator can grant their way out of. What must hold for this to be sound is that the tiers are a **ladder** — every role holding any `*:review` also holds `case:comment` — which `SeedDataTest.everyReviewerIsAlsoACommenter` derives from the catalogue rather than assuming. **Resolving a thread is not commenting**: it costs the subject's `*:review`, because declaring a point dealt with is a statement about the review's progress.
2. **`*:review`** — Test Engineer and up: LGTM, request changes, formal review participation. Advisory and recorded.
3. **`*:approve`** — Lead and up: the single gate that moves an item to APPROVED.

Author-cannot-approve-own-work defaults on.

#### Execution

| Permission | IA | CA | TL | TE | AS | V |
|---|:--:|:--:|:--:|:--:|:--:|:--:|
| `plan:create` | ✓ | ✓ | ✓ | ✓ | — | — |
| `plan:edit` | ✓ | ✓ | ✓ | ✓ | — | — |
| `plan:delete` | ✓ | ✓ | ✓ | — | — | — |
| `run:create` | ✓ | ✓ | ✓ | ✓ | ✓ | — |
| `run:execute` | ✓ | ✓ | ✓ | ✓ | — | — |
| `run:close` | ✓ | ✓ | ✓ | — | — | — |
| `result:submit` | ✓ | ✓ | ✓ | ✓ | ✓ | — |
| `result:amend` | ✓ | ✓ | ✓ | — | — | — |
| `evidence:attach` | ✓ | ✓ | ✓ | ✓ | ✓ | — |
| `evidence:delete` | ✓ | ✓ | ✓ | OWN | — | — |

#### Defects & work items

| Permission | IA | CA | TL | TE | AS | V |
|---|:--:|:--:|:--:|:--:|:--:|:--:|
| `defect:create` | ✓ | ✓ | ✓ | ✓ | — | — |
| `workitem:manage` | ✓ | ✓ | ✓ | ✓ | — | — |

Both new — the plan described the features (defect filing from a failed step; the kanban board, §6l) without permissions to govern them.

#### Reporting

| Permission | IA | CA | TL | TE | AS | V |
|---|:--:|:--:|:--:|:--:|:--:|:--:|
| `report:view` | ✓ | ✓ | ✓ | ✓ | — | ✓ |
| `report:export` | ✓ | ✓ | ✓ | ✓ | — | ✓ |

**Decided: if you can view it, you can export it.** The two permissions stay separate in the catalogue so a company can clone a role that drops export, but the built-ins grant them together. §6e's note about export leaving the access-control boundary stands as documentation, not as a default restriction.

#### Documentation

| Permission | IA | CA | TL | TE | AS | V |
|---|:--:|:--:|:--:|:--:|:--:|:--:|
| `doc:read` | ✓ | ✓ | ✓ | ✓ | — | ✓ |
| `doc:edit` | ✓ | ✓ | ✓ | ✓ | — | — |
| `doc:publish` | ✓ | ✓ | ✓ | — | — | — |

#### Artifacts

| Permission | IA | CA | TL | TE | AS | V |
|---|:--:|:--:|:--:|:--:|:--:|:--:|
| `artifact:read` | ✓ | ✓ | ✓ | ✓ | — | ✓ |
| `artifact:edit` | ✓ | ✓ | ✓ | plan scope only | — | — |
| `artifact:delete` | ✓ | ✓ | ✓ | — | — | — |

Project-scope artifact edit is Lead-only; sign-off artifacts are write-once.

#### Project

| Permission | IA | CA | TL | TE | AS | V |
|---|:--:|:--:|:--:|:--:|:--:|:--:|
| `project:read` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `project:audit` | ✓ | ✓ | ✓ | — | — | — |
| `project:settings` | ✓ | ✓ | ✓ | — | — | — |
| `project:members` | ✓ | ✓ | ✓ | — | — | — |
| `integration:manage` | ✓ | ✓ | ✓ | — | — | — |

**`project:audit` is new.** Without it a Test Lead could not read their own project's history — `audit:view` exists only at company scope, which was an oversight.

#### Company — `COMPANY` scope

| Permission | IA | CA | Every member | Covers |
|---|:--:|:--:|:--:|---|
| `company:read` | ✓ | ✓ | ✓ | Company profile, name, slug, branding, plus the settings view |
| `company:settings` | ✓ | ✓ | — | Branding, policies, defaults |
| `company:members` | ✓ | ✓ | — | Invite, remove, set membership display names |
| `company:teams` | ✓ | ✓ | — | Teams, nesting, leads, org-chart structure |
| `company:vocabulary` | ✓ | ✓ | — | Test Type / Level / Phase values |
| `company:idp` | ✓ | ✓ | — | Identity providers, claim→role mappings |
| `project:create` | ✓ | ✓ | — | Create projects in the company |
| `token:issue` | ✓ | ✓ | — | API / CI tokens |
| `audit:view` | ✓ | ✓ | — | The company-wide audit log |

**`company:read` is granted to every company member explicitly**, rather than being implicit in membership — one uniform mechanism, and every check is a real permission check.

**How "every member" is actually delivered — corrected 2026-08-01.** ~~It is seeded onto five of the six built-in roles, so holding any of them holds it.~~ → **superseded.** That is what `V3` did, and it did not work: four of those five roles are **PROJECT-scope**, while a company-level question is answered from the SYSTEM and COMPANY terms only (`PermissionEvaluator.evaluateCompanyWide`, deliberately — see below). So the permission this table says every member holds was **unreachable at company scope for everyone below Company Admin**, which is the defect handoff §39 found and §43 repaired. The per-role grants above are unchanged and still correct; what carries the permission company-wide is the **Company Member** baseline of §A2, granted on joining.

**The repair that was rejected, so it is not re-proposed:** letting the evaluator answer "held anywhere in this company". It fixes `company:read` and breaks the scope model — `case:delete` held on one project would then read true for the whole company, and every company-scope gate in the product would be answering a question about one project. Scope is what makes a Test Lead the lead of *one* project.

#### System — `SYSTEM` scope

| Permission | IA | Covers |
|---|:--:|---|
| `company:create` | ✓ | Provision a company (script-driven, §6s) |
| `company:delete` | ✓ | Purge — policy is *suspend, never delete* |
| `company:suspend` | ✓ | Suspend / resume |
| `user:manage` | ✓ | Global user directory across all companies |
| `role:manage` | ✓ | The role catalogue |
| `system:settings` | ✓ | Instance settings |
| `instance:audit` | ✓ | Instance-wide audit log |

**`user:manage` reads are audited as operator access**, same as content access — a cross-client directory listing is client-adjacent data even though it isn't test content.

### A4. What separates the tiers

**Test Engineer → Test Lead**, three lines:

1. **Creative vs destructive** — a Test Engineer is never blocked from *producing*, only from unmaking **someone else's** work: `case:delete` and `evidence:delete` are `OWN`-qualified, and `suite:delete`, `plan:delete`, `run:close`, `result:amend` are Lead-only.
2. **Review vs approve** — anyone can comment, contributors can review, only Lead+ signs off.
3. **Content vs configuration** — project settings, members, integrations and the audit trail are Lead-only.

**Test Lead → Company Admin** — same powers, wider target, plus company configuration and project creation.

**Company Admin → Instance Admin** — company lifecycle, the global user directory, the role catalogue, instance settings. Crosses the client boundary; nothing below it does.

### A5. The ownership qualifier

~~`case:edit`,~~ `case:delete` and `evidence:delete` are `OWN`-qualified for Test Engineer: they apply to content the caller authored, in any state. Implemented as an `OWN` qualifier resolved against `createdBy` — **not** as separate `case:edit:own` permissions.

**→ superseded 2026-07-31: `case:edit` is *not* `OWN`-qualified.** This line was the only place it was, and it contradicted both the permission table above (plain ✓ for Test Engineer) and §A3's own principle one screen up — *"a Test Engineer is never blocked from producing, only from unmaking **someone else's** work"* — which names `case:delete` and `evidence:delete` and deliberately not this. The failure the old reading produces is not theoretical: a Test Engineer who spots a wrong expected result in a colleague's case could not fix it, in a product whose entire subject is a **shared** test repository. Editing is producing; deleting is unmaking. The qualifier stays exactly where the principle puts it, and is still owed to `case:delete` (unimplemented as of backend M1 slice 7, which gates `case:edit` unqualified).

### A6. Automation Service — the exact grant

Deliberately the smallest role in the product, because a leaked CI token is the realistic breach:

```
case:read         ✓
run:create        ✓
result:submit     ✓
evidence:attach   ✓
case:create       ✓  restricted to the quarantine folder
project:read      ✓  visibility precondition, not a capability — see below
```

Everything else denied. A leaked token can post results and add quarantine stubs; it cannot edit or delete a real case, close a run, or read reports.

**`project:read` was missing until `V16`, and its absence made the other five unreachable.** It is
not a sixth capability: it is the permission `ProjectAccess.canView` asks, and every project-scoped
endpoint asks `canView` first so that a project in another company and a project you hold no grant
on are the same 404 (§6t). A PROJECT-scope role without it therefore does not lose the project list
— it 404s on **every** project-scoped path, so a role that looks correct in this spec, in the seed
and on the roles screen is inert over HTTP. Automation Service was in that state from `V3` until
`V16`, which means the role M3's ingestion depends on could not post a result.

The repair grants the precondition rather than weakening what `canView` asks — the same choice made
for `company:read` (handoff §43), and for the same reason: loosening a gate to reach a permission is
how a gate stops meaning anything. This does not widen the paragraph above, which is about what a
stolen token can *do*; a token that may read the project's cases can hardly be prevented from
knowing the project exists.

**The rule is now machine-checked**, not restated here and hoped for:
`SeedDataTest.everyProjectScopeRoleHoldsTheVisibilityPrecondition` derives from the catalogue that
no PROJECT-scope role holds permissions without `project:read`.

### A7. Defaults & visibility

| Situation | Result |
|---|---|
| Accepting a company invitation | **Viewer**, no project access |
| SSO JIT provisioning (where enabled) | **Viewer**, no project access — SSO is not a side door to more than the invite flow grants |
| Project assignment | **Viewer** on that project; Test Engineer and above are explicit grants |
| Company Admin's project visibility | **Every project in their company**, present and future, no per-project grant needed |
| Instance Admin's visibility | Everything, every company; access into client content audited in the client's own trail |

**Nothing is open.** All roles, permissions and naming decisions are settled as of 2026-07-30.

**Corrected 2026-08-01.** The sentence above is about *decisions*, and it held. What did not hold is
the assumption that a settled decision is a delivered one: on 2026-08-01 two grants this document
states as facts turned out to be **unreachable in the running system** — `company:read` at company
scope (§A3, repaired by `V15`) and the whole of Automation Service's grant (§A6, repaired by `V16`).
Both were correct in the seed and correct here; both were defeated by the scope or ordering of the
gate that asks them, and neither was visible until a test asked as that role. See the change log.
The lesson is recorded where it can act: the two rules are now **derived from the catalogue by
`SeedDataTest`** rather than asserted in prose here.

---

## Part B — Naming Conventions

### B1. The identity model

| | **`id`** | **`displayKey`** |
|---|---|---|
| Form | UUID | `TC-1` |
| Used by | Every FK, every API path, every join | Humans only — UI, commit messages, Jira tickets, spreadsheets |
| Unique | Globally | **Per company** |
| Mutable | Never | Never |
| Guessable | No | Trivially — which is why it never appears in an API path |

### B2. Display keys are company-scoped

**Decided: numbers run from 1 per company, unique across all of that company's projects.** Acme has exactly one `TC-1`, and it lives in whichever project created it.

| Entity | Prefix | Example |
|---|---|---|
| Test case | `TC` | `TC-1` |
| Test plan | `TP` | `TP-1` |
| Test suite | `TS` | `TS-1` |
| Test run | `TR` | `TR-1` |
| Documentation page | `DOC` | `DOC-1` |

**No key** for conditions, shared steps, defects (Jira owns those), review requests, artifacts, milestones or configurations — each is reached from its parent or chosen from a typeahead, and every extra prefix is another counter row plus another ambiguous token in search.

**No automation prefix.** `automationStatus` (§6c) carries that, and it is designed to change over a case's life; an immutable key that encodes automation state starts lying the day the case gets automated.

#### What company scoping buys

`TC-1` is unambiguous everywhere inside a company, which removes an entire class of design problem:

- **One form, everywhere.** No short-vs-qualified distinction — reports, search results, kanban cards, notifications and Jira defect bodies all render `TC-1`. (Showing the owning project alongside it is a display nicety, not a disambiguation requirement.)
- **Search is trivial.** A key pattern in the query short-circuits straight to the item. No project context needed, no ambiguous-match list.
- **Moving a case between projects is a non-event.** The key travels with it and cannot collide, because the namespace is the company. Cross-company moves don't exist (§6s).

#### What it costs

- **The counter is a company-wide row** per `(company, entityType)`, incremented with `UPDATE … RETURNING`. All case creation in a company serialises on one row — microseconds, but **bulk import and CI quarantine auto-create must reserve N numbers in one batch**, not one round trip per item.
- **Numbers get large** for a busy client. Expected and harmless.
- **A minor inference channel:** because the sequence is company-wide, a user granted one project can infer the company's total case volume from key numbers, and could try `TC-N` for a case in a project they can't see. This is precisely why unauthorised or unresolvable keys must return **not found** rather than forbidden, and why display keys never appear in API paths (§6b, §6t).

### B3. Other identifiers & their uniqueness scope

| Identifier | Format | Unique within | Mutable | Notes |
|---|---|---|---|---|
| Company slug | lowercase kebab | Instance | **no** | In every URL: `/c/{slug}`, `/{slug}/login` |
| Company display name | free text | — | yes | Branding (§6u) |
| Project key | **2–10 uppercase alphanumerics** | Company | **no** | Suggested from the project name. Used in URLs and project labels — no longer inside case keys. |
| Project name | free text | Company | yes | A full client project: "Acme Online Banking" |
| Team name | free text | Company | yes | |
| Membership display name | free text | — | yes | Per company, per user (§6u) |
| `automationKey` | free text (FQCN#method, scenario URI) | **Company** | yes | The CI join key (§6c) — company-scoped, matching display keys |
| `automationTag` | free text, multi-valued | — | yes | Selection, not matching (`@smoke`) |
| Release version | free text + parsed sort key | Project | yes | Sort-key scheme still open (plan Q35) |
| Condition title | free text | **Project** | yes | Duplicate warning at creation (§6h) |
| Shared step | free text | **Project** | yes | |
| Execution status name | free text | **Project** | yes | Seeded, extensible per project |
| Test Type / Level / Phase value | controlled | **Company** | rename propagates | §6d — consistent across the client's projects |
| Feature value | path in a tree | **Project** | rename propagates | `Payments › Refunds › Partial refund` |
| Free tag | free text | Project | yes | The escape hatch |

**`automationKey` is company-scoped**, so a CI job posts results without needing to know which project owns the case, and a case moving project doesn't change where its key resolves.

**Conditions, shared steps and statuses are project-scoped**; Test Type / Level / Phase are company-scoped. This resolves the plan's internal contradiction (§6f said project, §6s implied company). It reads correctly now that a project is a whole product: two different client products legitimately have different login flows and different status vocabularies, while "what counts as a Performance test" should be consistent across the client.

---

## Plan edits — applied 2026-07-30

All of these are now reflected in `docs/plans/2026-07-30-tcms-high-level-plan.md`:

| § | Edit | Done |
|---|---|:--:|
| §6t | Rewritten for **company-scoped** keys — short-vs-qualified form section and ambiguity handling deleted; counter is `(company, entityType)` with batch reservation; moved-case collision discussion collapsed; key-inference channel documented. | ✓ |
| §6b | Added `suite:delete`, `project:audit`, `case:comment`, `defect:create`, `workitem:manage`, `library:manage`; `case:delete` is `OWN` for Test Engineer; `report:export` granted to Viewer+; `company:read` to all members; review table now three tiers; Automation Service grant spelled out; project examples corrected. | ✓ |
| §6c | `automationKey` unique per **company**. | ✓ |
| §6f | Conditions confirmed project-scoped; impact modal on library edits documented. | ✓ |
| §6m | `displayKey` unique per company; `automationKey` per company. | ✓ |
| §6q | Search simplified — no qualified-key resolution, no ambiguous-match list; query-syntax example de-feature-ised. | ✓ |
| §6s | Uniqueness list corrected (project-scoped vs company-scoped identifiers separated); display-key counter and `automationKey` added. | ✓ |
| §7 | M0 line: per-company display-key allocation with batch reservation. | ✓ |
| §8 | 13 decisions recorded in the resolved table; Q12, Q20 struck through as resolved; obsolete questions removed and renumbered. | ✓ |
| §9 | Next-steps list now points here as authoritative for the seed migration; ERD pass includes the counter table. | ✓ |

**Outstanding in the plan:** none in this document's scope. The four remaining questions were answered 2026-07-30 — Viewer as the universal default, Company Admin sees all projects in their company, client-facing isolation evidence deferred to a nice-to-have, and no custom roles in v1. Plan §8 questions 1–42 remain, of which 1, 4, 5, 7, 8 and 9 affect the M1/M2 design.

**Downstream artifacts — all aligned as of 2026-07-30:**

| Artifact | State |
|---|---|
| `docs/specs/2026-07-30-tcms-erd.md` | `company_id` throughout, `company`/`company_membership` modelled, display-key counter per company, granted-projects rule, RLS |
| `V1__initial_schema.sql` | Regenerated — 63 tables, statically validated, **never executed** |
| `docs/specs/openapi.yaml` | `PermissionKey` as a 55-member enum, six roles, `COMPANY` scope, `TC-1` keys |
| `frontend/` | `lib/permissions.ts` derives the catalogue from the contract union; six roles in fixtures; Viewer default |

~~**Still to do:** the permission catalogue as a seed migration~~ → **done 2026-07-30** as `V3__seed_permissions_and_roles.sql` (the seed split out of `V2`, which holds triggers and the per-company seed functions — schema and data are separate migrations). Part A is its source: 55 permissions, the six roles, and the grants exactly as tabulated above.

**This document is now transcribed in three places, and that is the risk to manage.** The spec (here, authoritative) → `docs/specs/openapi.yaml` `PermissionKey` (what the frontend generates types from) → `V3` (what the running system actually enforces). Prose cannot keep three copies aligned, so `SeedDataTest` compares the seed to the contract mechanically in both directions and asserts the boundary properties Part A states as sentences — the Test Engineer → Test Lead lines, Company Admin holding no SYSTEM permission, Automation Service being exactly five, Viewer holding no write. The frontend holds the third corner with `satisfies Record<PermissionKey, Area>` in `lib/permissions.ts`, which is a compile error on drift.

**Instance Admin's grant is derived** (`SELECT role_id, key FROM permission`), never enumerated and certainly never a `'*'` row — a sentinel cannot distinguish complete coverage from a value that happens to be present, so a later permission it did not hold would still have read as full coverage.

**Adding a permission after this point touches four places in one commit:** this table, `openapi.yaml` (regenerate `schema.d.ts`), `lib/permissions.ts`, and a *new corrective migration* — never an edit to `V3`, which has shipped. `SeedDataTest` fails until all four agree.

---

## Change log

| Date | Change |
|---|---|
| 2026-07-30 | Initial snapshot from the high-level plan |
| 2026-07-30 | All 17 decisions taken; display keys and `automationKey` moved to company scope; Project clarified as a full client project, not a feature area |
| 2026-07-30 | Final four: Viewer is the default at every entry point (was Test Engineer on project assignment) · Company Admin sees all projects in their company · client-facing isolation evidence deferred · **no custom roles in v1, clone-and-edit removed** |
| 2026-08-01 | **`company:read` reaches company scope.** A7's promise that every member holds it was true of the seed and false of the evaluator, because four of the five roles holding it are PROJECT-scope. Added the non-assignable **Company Member** baseline (§A2), granted on joining by `V15` and backfilled for existing members; recorded the rejected "held anywhere in this company" evaluator change (§A3). No permission was added, removed or moved between roles, so `openapi.yaml` and `lib/permissions.ts` are unchanged. Handoff §43. |
| 2026-08-02 | **`case:comment` is read as the commenting TIER, covering cases, suites and plans alike** — recorded when `review_comment` got its first writer. No permission was added, removed or moved between roles, so `V3`, `openapi.yaml` and `lib/permissions.ts` are unchanged; what changed is that the key finally has a consumer, and the reading is now written down beside the table rather than implied by it. The ladder it depends on is a computed check. Handoff §79. |
| 2026-08-01 | **Automation Service gets `project:read` (§A6), and the rule becomes a computed check.** The same shape as the row above, found the same day by the first test that asked as that role: `V3` seeded the five §A6 capabilities and no `project:read`, but every project-scoped path asks `ProjectAccess.canView` — which *is* `project:read` — before asking the permission. So all five were unreachable and the role was inert over HTTP, which would have blocked M3 ingestion. `V16` grants the precondition rather than weakening `canView`, matching the row above. `SeedDataTest.everyProjectScopeRoleHoldsTheVisibilityPrecondition` now derives the rule from the catalogue so a third instance cannot ship. `V16` also corrects `role`'s table comment, which still said "exactly six built-ins" after `V15` added the seventh. No permission was added to the catalogue, so `openapi.yaml` and `lib/permissions.ts` are unchanged. Handoff §42, §45. |
