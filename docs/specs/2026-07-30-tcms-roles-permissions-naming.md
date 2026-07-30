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
| `PROJECT` | One project | Company Admin, or Test Lead of that project | The finest boundary. No per-suite or per-case ACLs. |

Two gates, both required before any row is visible:

```
1. company filter   →  is this row's company one I'm a member of?   (§6s)
2. project grant    →  do I hold any role on this specific project?  (§6b)
```

**Company membership grants authentication into a company and zero project visibility.** A regular user sees only the projects they hold a grant on — not the company's project list, not names in a picker, not counts on a dashboard. With projects being whole client engagements, this is a confidentiality boundary, not just tidiness.

Effective permissions are a **union of allows with no explicit deny**, gated on company membership.

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

**These six are the complete role set in v1.** Seeded, immutable, **not cloneable — no custom roles at all**, not even clone-and-edit. A client needing something between two tiers gets the higher one, or the built-in boundary is drawn wrong and should be fixed. Enforcement is permission-based (nothing checks a role name), so adding a role designer later is additive — phase 3, on evidence of demand.

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

1. **`case:comment`** — a Viewer may leave comments. Built for the case where the *client* reviews test coverage themselves without gaining any write access.
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

`case:edit`, `case:delete` and `evidence:delete` are `OWN`-qualified for Test Engineer: they apply to content the caller authored, in any state. Implemented as an `OWN` qualifier resolved against `createdBy` — **not** as separate `case:edit:own` permissions.

### A6. Automation Service — the exact grant

Deliberately the smallest role in the product, because a leaked CI token is the realistic breach:

```
case:read         ✓
run:create        ✓
result:submit     ✓
evidence:attach   ✓
case:create       ✓  restricted to the quarantine folder
```

Everything else denied. A leaked token can post results and add quarantine stubs; it cannot edit or delete a real case, close a run, or read reports.

### A7. Defaults & visibility

| Situation | Result |
|---|---|
| Accepting a company invitation | **Viewer**, no project access |
| SSO JIT provisioning (where enabled) | **Viewer**, no project access — SSO is not a side door to more than the invite flow grants |
| Project assignment | **Viewer** on that project; Test Engineer and above are explicit grants |
| Company Admin's project visibility | **Every project in their company**, present and future, no per-project grant needed |
| Instance Admin's visibility | Everything, every company; access into client content audited in the client's own trail |

**Nothing is open.** All roles, permissions and naming decisions are settled as of 2026-07-30.

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

**Not yet updated:** `docs/specs/2026-07-30-tcms-erd.md` — still uses `tenant_id`, lacks `company`/`company_membership` as entities, the display-key counter table, and the granted-projects rule.

---

## Change log

| Date | Change |
|---|---|
| 2026-07-30 | Initial snapshot from the high-level plan |
| 2026-07-30 | All 17 decisions taken; display keys and `automationKey` moved to company scope; Project clarified as a full client project, not a feature area |
| 2026-07-30 | Final four: Viewer is the default at every entry point (was Test Engineer on project assignment) · Company Admin sees all projects in their company · client-facing isolation evidence deferred · **no custom roles in v1, clone-and-edit removed** |
