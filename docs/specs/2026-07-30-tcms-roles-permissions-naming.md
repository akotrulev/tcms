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
| **Test Engineer** | PROJECT | yes | The working contributor role, **granted explicitly — never a default**. Full CRUD over the repository's *content* — cases, their steps, plans and evidence — **including content somebody else authored** (`V51`, 2026-08-14; ~~cannot delete others' work~~). Executes, reviews, files defects, maintains the shared library. Cannot approve, delete a suite or a project-scope artifact, amend others' results, close runs, or change settings. |
| **Automation Service** | PROJECT | yes | Non-human. Attached to API tokens, **never people — and `assignable = false` since `V41` (2026-08-08), which is where that sentence stopped being a description and became a rule.** |
| **Viewer** | PROJECT | yes | Read-only, plus commenting and report export. **The default everywhere.** |

~~**These six are the complete set of *assignable* roles in v1.**~~ → **superseded 2026-08-08 by `V41`: these six are the complete *role* set, and five of them are assignable.** Automation Service is one of the six and is not one of them — it is a capability set a *credential* carries, which this table has said since M0 and nothing enforced. The two lists were the same list until today, and the sentence `V15` and `V40` were both careful to keep literally true is retired rather than reworded, because the care was spent on the wrong invariant: what mattered was never "the six are assignable" but "no role outside the catalogue is". Seeded, immutable, **not cloneable — no custom roles at all**, not even clone-and-edit. A client needing something between two tiers gets the higher one, or the built-in boundary is drawn wrong and should be fixed. Enforcement is permission-based (nothing checks a role name), so adding a role designer later is additive — phase 3, on evidence of demand.

**Two further rows exist in `role` and neither is one of the six.** Both are `assignable = false` — nobody is ever granted them — and there the resemblance ends. **Since `V41` `assignable = false` is no longer the same statement as "not one of the six"**: three rows carry it, and Automation Service is one of the six.

| Role | Scope | Built-in | Assignable | Listed by `GET /roles` | Carried by a token | Intent |
|---|---|---|---|:--:|:--:|---|
| **Company Member** | COMPANY | yes | **no** | no | no | Exactly `company:read`, granted automatically to every member on joining. Never offered, never revocable. Added 2026-08-01 by `V15` — the mechanism §A3's "`company:read` for every member" needed rather than a tier. |
| **Agent** | PROJECT | yes | **no** | **yes** | **yes** | The ceiling for an AI agent acting on behalf of the person who issued the token — Test Engineer minus three, enumerated in **§A6a**. Added 2026-08-08 by `V40`. |

**`Company Member` is not in the table above because nobody ever *chooses* it**: it is granted by the act of joining, it is filtered out of `GET /roles`, `POST /role-assignments` refuses it, and `GET /role-assignments` does not list its grants — so nothing renders a Revoke whose effect would be to take away a colleague's sight of the company with no repair short of the operator CLI.

**`Agent` is not in the table above because nobody is ever *an Agent*** — it is a property of a credential, not of a person. But it *is* listed and *is* issuable, and that asymmetry is load-bearing: the token-issue form is the only place it is ever chosen, so hiding it there would make the role inert. ~~**Three questions — may a person hold it, may a token carry it, is it listed — and `role.assignable` answers only the first.**~~ → **four, since `V41`.** The fourth is **may an existing grant be revoked**, and it is the one `V40` got wrong by fusing it with the first. Refusing a grant says *this is not a role a person holds*; refusing a revoke says *removing this does damage the endpoint cannot repair*, and only `Company Member` qualifies. For the two token ceilings the answer is the opposite: a grant made before the rule existed is exactly what an administrator must be able to clean up, and both stay on `GET /role-assignments` so the Revoke is reachable. All four coincided while `Company Member` was the only non-assignable row; `V40` §2 and `V41` §1 record which enforcement sites may read the flag — **one, the grant path** — and which must stay baseline-specific.

**The six are still the whole of what an administrator can do**, in the sense that no seventh tier exists; five of them are what an administrator can *give somebody*. `openapi.yaml`'s `Role.name` enum gains `Agent` (it is on the wire) and keeps excluding `Company Member` (it is not); a `Role` now also carries `assignable`, so the grant pickers can tell "cannot be granted to anyone" from "wrong scope for this grant".

**Why it had to be a new role rather than a reuse.** ~~The four PROJECT-scope roles cannot be granted at COMPANY scope (`RoleGrantService.scopeError` refuses the mismatch, and it should: Viewer granted company-wide would confer `project:read` and `case:read` over **every project present and future**, which is exactly the boundary §A1 draws)~~ → **superseded 2026-08-02 by §A1.1**: they can be, and that is now the inheritance feature. The conclusion here still holds for a different reason — a company-wide Viewer *does* read every project, and `Company Member` must convey nothing about any project, so the two remain different roles rather than one. Company Admin confers everything. **It is not the "company-wide read-only stakeholder"** handoff §39 anticipated as a possible seventh role — that one reads every project; this one grants nothing about any project, which is what makes it safe to hold universally. Both could exist; they are different roles.

**Viewer is the default at every entry point:** accepting a company invitation, SSO JIT provisioning, and project assignment. One rule at both levels — the cost of an unintended Viewer is nothing; the cost of an unintended contributor is edits to a client's test assets.

### A3. Permission matrix

`✓` granted · `—` not granted · ~~`OWN` only for content the caller authored~~ → **no grant in the
catalogue is `OWN`-qualified as of 2026-08-14 (`V51`, §207)**; the notation and the mechanism survive
for a future key that needs it, and the qualifier still in play is `PLAN_SCOPE` (Artifacts, `V19`)

**IA** Instance Admin · **CA** Company Admin · **TL** Test Lead · **TE** Test Engineer · **AS** Automation Service · **V** Viewer

#### Repository

| Permission | IA | CA | TL | TE | AS | V |
|---|:--:|:--:|:--:|:--:|:--:|:--:|
| `suite:manage` *(create, rename, move, reorder)* | ✓ | ✓ | ✓ | ✓ | — | — |
| `suite:delete` *(folder + subtree)* | ✓ | ✓ | ✓ | — | — | — |
| `case:read` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `case:create` | ✓ | ✓ | ✓ | ✓ | quarantine only | — |
| `case:edit` | ✓ | ✓ | ✓ | ✓ | — | — |
| `case:delete` | ✓ | ✓ | ✓ | ✓ | — | — |
| `library:manage` *(shared conditions & steps)* | ✓ | ✓ | ✓ | ✓ | — | — |

**`suite:delete` split out** — a suite is a flat named *collection* (§98), so deleting one destroys the grouping rather than the cases in it. That makes it a decision about shared structure, which is where the lead line now sits. ~~deleting a subtree is the most destructive repository action, so it sits with leads while contributors keep organising the tree freely~~ → the subtree it named stopped existing at §98; the grant did not move, but the reason had to.

~~**`case:delete` is `OWN` for Test Engineer, in any state** — authorship confers full control of that case. Soft delete everywhere keeps this recoverable.~~

**→ superseded 2026-08-14 (`V51`, handoff §207): `case:delete` is a plain ✓ for Test Engineer.**
**CRUD on test plans, cases and steps may be done by any user who is not a Viewer, no matter who first created the row.** The `OWN` qualifier is removed from this key and from `evidence:delete`, and `plan:delete` is granted to Test Engineer — after which nothing in the catalogue carries `OWN`.

The old reasoning is not being called wrong, it is being overruled for this surface, on exactly the ground §A5 already used to de-qualify `case:edit` on 2026-07-31: **this product's subject is a *shared* test repository, and a repository whose rows answer to their author is a set of private notebooks in one table.** That reversal stopped at editing because editing is producing and deleting is unmaking. The distinction does not survive contact with the workflow — the duplicate a colleague left behind last quarter is precisely the row somebody else has to remove, and routing every such cleanup through a lead is the friction §A3.5 exists to avoid. Soft delete, which every one of these tables has, is what keeps it recoverable; the qualifier was the wrong instrument for that job.

**Steps have no key of their own** and never did: a step is part of its case, written through `CaseUpdateService` and gated by `case:edit`, which Test Engineer has held unqualified since `V3`. Nothing was granted for them because there is nothing to grant.

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
| `plan:delete` | ✓ | ✓ | ✓ | ✓ | — | — |
| `run:create` | ✓ | ✓ | ✓ | ✓ | ✓ | — |
| `run:execute` | ✓ | ✓ | ✓ | ✓ | — | — |
| `run:close` | ✓ | ✓ | ✓ | — | — | — |
| `result:submit` | ✓ | ✓ | ✓ | ✓ | ✓ | — |
| `result:amend` | ✓ | ✓ | ✓ | — | — | — |
| `evidence:attach` | ✓ | ✓ | ✓ | ✓ | ✓ | — |
| `evidence:delete` | ✓ | ✓ | ✓ | ✓ | — | — |

**`plan:delete` and `evidence:delete` moved on 2026-08-14** (`V51`, §207) — see §A3's `case:delete` note for the argument. `plan:delete` was the *tier* half of it: Test Engineer never held the key at all, so there was no qualifier to remove, and a contributor who may create and edit a plan may now retire one. `evidence:delete` moves for consistency rather than because the requirement names evidence — leaving one `OWN` row behind would have kept the qualifier populated by an exception nobody decided.

**`run:close` and `result:amend` stay Lead-only**, and that is not an oversight of the same pass: they are judgements about *the work* — whether a cycle is finished, whether a recorded outcome should be overwritten — not CRUD on an artifact somebody authored.

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

1. ~~**Creative vs destructive** — a Test Engineer is never blocked from *producing*, only from unmaking **someone else's** work: `case:delete` and `evidence:delete` are `OWN`-qualified, and `suite:delete`, `plan:delete`, `run:close`, `result:amend` are Lead-only.~~
   **→ rewritten 2026-08-14 (`V51`, §207). Content vs shared structure and judgement** — a Test Engineer has full CRUD over the *content* of the repository, **including content somebody else authored**: cases, their steps, plans and evidence, created, edited and deleted without regard to who wrote them. What stays Lead-only is not "destruction" and is certainly not authorship:
   - `suite:delete` and `artifact:delete` — **shared structure.** A suite is the grouping many people file into; a project-scope artifact is inherited read-only by every plan (plan §6n). Removing either changes something other people's work hangs off.
   - `run:close` and `result:amend` — **judgements about the work**, not CRUD on an artifact.

   The old framing was a real principle and it failed in a specific way worth recording: "destructive" collapsed two different things — unmaking a *colleague* (which the qualifier policed) and unmaking *shared structure* (which the tier policed) — so removing the first read as abandoning the second. It does not. The tier line is untouched; only the authorship line is gone.
2. **Review vs approve** — anyone can comment, contributors can review, only Lead+ signs off.
3. **Content vs configuration** — project settings, members, integrations and the audit trail are Lead-only.

**Test Lead → Company Admin** — same powers, wider target, plus company configuration and project creation.

**Company Admin → Instance Admin** — company lifecycle, the global user directory, the role catalogue, instance settings. Crosses the client boundary; nothing below it does.

### A5. The ownership qualifier

~~`case:edit`,~~ `case:delete` and `evidence:delete` are `OWN`-qualified for Test Engineer: they apply to content the caller authored, in any state. Implemented as an `OWN` qualifier resolved against `createdBy` — **not** as separate `case:edit:own` permissions.

**→ superseded 2026-07-31: `case:edit` is *not* `OWN`-qualified.** This line was the only place it was, and it contradicted both the permission table above (plain ✓ for Test Engineer) and §A3's own principle one screen up — *"a Test Engineer is never blocked from producing, only from unmaking **someone else's** work"* — which names `case:delete` and `evidence:delete` and deliberately not this. The failure the old reading produces is not theoretical: a Test Engineer who spots a wrong expected result in a colleague's case could not fix it, in a product whose entire subject is a **shared** test repository. Editing is producing; deleting is unmaking. The qualifier stays exactly where the principle puts it, and is still owed to `case:delete` (unimplemented as of backend M1 slice 7, which gates `case:edit` unqualified).

**→ superseded 2026-08-14 (`V51`, handoff §207): NO grant is `OWN`-qualified. The section title is now historical.**

`case:delete` and `evidence:delete` are plain ✓ for Test Engineer, and the population of the qualifier is **zero rows**. The 2026-07-31 note above got the *direction* right and drew the line one key too early: "editing is producing; deleting is unmaking" reads well and does not survive the case a colleague duplicated and abandoned. Both keys are now decided by the role alone.

**The mechanism is deliberately kept, unpopulated.** `OWN` remains a legal value of `role_permission.qualifier`, a `PermissionQualifier` member, and a live arm of `IdentityProjectAccess.canDoOnAuthored`; `CaseDeleteService` still routes through the authorship gate and still carries the distinct "you can only delete test cases you created" refusal. Three reasons, in order of weight:

1. **The qualifier is data.** Deleting the arm is what would make a future `OWN` grant fail *open* at whichever gate forgot it. Keeping it means a grant means what the row says.
2. `PLAN_SCOPE` (`V19`) is the same mechanism and *is* populated, so the column, the enum and the resolution seam are earning their keep regardless. What removal would save is one enum member and one switch arm.
3. Rebuilding "only what you wrote" later costs a migration, a gate change and a contract change; keeping it costs a comment.

**Read the empty population as a decision, not a seeding bug** — that is exactly what `SeedDataTest.ownIsCarriedByNoGrant` asserts, in both directions, and it is the check that fails if a later migration quietly reintroduces authorship-scoped CRUD.

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

**Nobody holds it. `V41` (2026-08-08) made it `assignable = false`**, so `POST /role-assignments` refuses it and the grant pickers drop it — it is chosen in the token-issue form and nowhere else, exactly like `Agent` (§A6a). The grant above is unchanged, and so is everything about issuing it: this is the smallest role in the product and it is the one every CI credential carries. **Grants that already existed are kept and stay revocable** — see `V41` §1 for why that is a *different* rule from assignability, and §A2 for the sentence about "the six" that it retired.

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

### A6a. Agent — the exact grant

**Token-only, PROJECT scope, seeded by `V40`.** The ceiling for an AI agent — a CLI or an MCP server — acting through the REST API on behalf of the person who issued the token. Twenty-three keys, and it is ~~**Test Engineer minus four**~~ → **Test Engineer minus five since `V51`** — the key count below is unchanged because Agent gained nothing; Test Engineer gained `plan:delete`, which widened the *delta* without touching this role:

```
suite:manage      ✓                   case:comment      ✓
case:read         ✓                   case:review       ✓
case:create       ✓                   suite:review      ✓
case:edit         ✓  ALL, not OWN     plan:review       ✓
plan:create       ✓                   report:view       ✓
plan:edit         ✓                   report:export     ✓
run:create        ✓                   doc:read          ✓
run:execute       ✓                   doc:edit          ✓
result:submit     ✓                   artifact:read     ✓
evidence:attach   ✓                   artifact:edit     ✓  plan scope only
defect:create     ✓                   project:read      ✓  visibility precondition (§A6)
workitem:manage   ✓
```

**Dropped from Test Engineer** — ~~three~~ → **five since `V51` (§207)**, each for its own reason:

| Dropped | Why |
|---|---|
| `library:manage` | §A3 gates it by an **impact modal, not by permission** — editing a shared condition or step propagates to every referencing case, and the control is *informed consent* via a confirmation listing them. **An API caller never sees that modal.** Removing the permission is the only fix that also holds for raw REST; rebuilding consent at the tool layer could not. |
| `case:delete` | ~~`OWN` for Test Engineer, and `OWN` resolves against `created_by` while a token's owner is `api_token.owner_id` — so the agent would delete cases **the human authored**. No qualifier expresses "only what this credential made", so the key is withheld rather than narrowed.~~ **→ re-based 2026-08-14.** `V51` removed the qualifier, so that argument has no premise left. The exclusion stands on the plainer rule that was always underneath it: **agent access creates, reads and edits; it does not delete.** |
| `evidence:delete` | Same, and re-based the same way. |
| **`plan:delete`** | **New to this table at `V51`, and it did not move — Test Engineer moved.** It sat in the "never held, inherited" list below, which excluded it only for as long as the contributor role did not hold the key. The moment Test Engineer gained it, the inheritance stopped excluding anything and it had to become an explicit drop. Same rule as the two above: an agent does not delete. |
| `company:read` | A token is not a member: it has no membership row and therefore no `V15` baseline grant, and a PROJECT-scope role's `company:read` is unreachable from `evaluateCompanyWide` anyway. `Automation Service`, the other token-only role, does not hold it either. |

**This is why `SeedDataTest` computes the delta instead of enumerating the ceiling.** `V40` enumerates Agent's 23 keys, so no agent gained anything in the *data* when Test Engineer gained `plan:delete` — but a subset check would have stayed green while the spec's stated reason for the exclusion silently evaporated. `agentIsTestEngineerMinusFive` is the assertion that forced this row to be written.

**Never held, inherited from Test Engineer's own boundary** — and each is something an agent in particular should not do: `case:approve` · `suite:approve` · `plan:approve` (author-cannot-approve-own-work defaults on, and an agent that generates cases must not approve them) · `run:close` · `result:amend` (a judgement about the *work*, not the artifact) · `doc:publish` (the human's signal that a document is ready) · `project:settings` · `project:members` · `integration:manage` · `suite:delete` · `artifact:delete`. ~~`plan:delete`~~ — promoted to the explicit table above, see that row.

**It never exceeds the person who issued the token.** The token intersection (plan §6p, Decision C) is unchanged and is the only narrowing point:

```
effective = evaluate(ownerId, projectId) ∩ permissionsOf(Agent) ∩ [the token's project]
```

so a Viewer's agent token resolves to Viewer's set with no branching anywhere, and demoting a person shrinks every token they issued. **`Agent` does not subsume `Automation Service` and CI must not carry it**: a leaked CI token still cannot edit or delete a real case, and that property is independent of how results arrive.

**The ceiling is data, not code**, which is the reason it is a role at all: it appears in this spec, `SeedDataTest.agentIsTestEngineerMinusThree` derives the delta from the catalogue rather than transcribing it, and the boundary therefore holds on raw REST as well as through any tool list. A ceiling enforced by omitting a tool controls what an agent reaches *for*, not what it can *do* — the `tcms_` bearer authenticates against `/api/*` and can be used with `curl`.

**`expiresAt` is required for an `Agent` token** and optional for every other role — see §A7's note.

### A7. Defaults & visibility

| Situation | Result |
|---|---|
| Accepting a company invitation | **Viewer**, no project access |
| SSO JIT provisioning (where enabled) | **Viewer**, no project access — SSO is not a side door to more than the invite flow grants |
| Project assignment | **Viewer** on that project; Test Engineer and above are explicit grants |
| Company Admin's project visibility | **Every project in their company**, present and future, no per-project grant needed |
| Instance Admin's visibility | Everything, every company; access into client content audited in the client's own trail |
| An API token's expiry | **Optional for every role except `Agent`, where it is required** and the form pre-fills 90 days. Open-ended is the default because a CI credential that dies mid-release without anybody deciding to withdraw it is an outage, and the withdrawal that *is* a decision is Revoke. An agent token inverts that: it lives on somebody's machine rather than in a pipeline, so the realistic end is that nobody revokes it because nobody remembers it — and an optional field is a field nobody fills in. Enforced in `ApiTokenService`, not in the form: the bearer authenticates against `/api/*`, so a rule a client holds is a rule `curl` does not. |

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
| 2026-08-03 | **Asking somebody to review costs the subject's `*:review`** — recorded when reviewer assignment was built (`review_assignment`, `V25`). **No permission was added**, and that is the decision: a new `*:assign` key would be seeded to nobody, so on every existing instance it is a 403 no administrator can grant their way out of — §67's argument against `evidence:read` and §79's against `suite:comment`. Of the three existing candidates, `case:comment` would let a **Viewer** put work on a contributor's queue (the one thing the bottom tier is defined not to do) and `*:approve` would confine the request to the people whose sign-off it exists to *prepare*; asking is a statement about the review's progress, which is the row above's argument for resolving a thread. `V3`, `openapi.yaml` and `lib/permissions.ts` are unchanged. Separately: the **assignee** is validated on project *visibility*, not company membership, because a colleague with no grant cannot open the artifact — the bar §A3's `project:read` sets. Handoff §80. |
| 2026-08-02 | **`case:comment` is read as the commenting TIER, covering cases, suites and plans alike** — recorded when `review_comment` got its first writer. No permission was added, removed or moved between roles, so `V3`, `openapi.yaml` and `lib/permissions.ts` are unchanged; what changed is that the key finally has a consumer, and the reading is now written down beside the table rather than implied by it. The ladder it depends on is a computed check. Handoff §79. |
| 2026-08-01 | **Automation Service gets `project:read` (§A6), and the rule becomes a computed check.** The same shape as the row above, found the same day by the first test that asked as that role: `V3` seeded the five §A6 capabilities and no `project:read`, but every project-scoped path asks `ProjectAccess.canView` — which *is* `project:read` — before asking the permission. So all five were unreachable and the role was inert over HTTP, which would have blocked M3 ingestion. `V16` grants the precondition rather than weakening `canView`, matching the row above. `SeedDataTest.everyProjectScopeRoleHoldsTheVisibilityPrecondition` now derives the rule from the catalogue so a third instance cannot ship. `V16` also corrects `role`'s table comment, which still said "exactly six built-ins" after `V15` added the seventh. No permission was added to the catalogue, so `openapi.yaml` and `lib/permissions.ts` are unchanged. Handoff §42, §45. |
| 2026-08-08 | **Automation Service becomes non-assignable (§A6, §A2), and the revoke axis leaves the flag.** `V41` sets `assignable = false` on the role this spec has called "attached to API tokens, never people" since M0 — no permission moved, no role added, nothing about issuing it changed, so `lib/permissions.ts` and `Role.name`'s enum are untouched and only two description blocks in `openapi.yaml` changed. **§A2's "these six are the complete set of assignable roles" is retired**: five of the six are. The load-bearing half is that `RoleGrantService.revoke` **stops** reading `assignable` and goes back to naming the membership baseline — refusing a revoke is about a consequence of removal, not about who may hold the role, and reading the flag there would strand every pre-existing grant behind a Revoke button that 403s while the row stayed on the screen. Existing grants are kept, listed and revocable; the migration deletes nothing. No frontend code changed — the pickers have filtered on `assignable` since §133. Handoff §134. |
| 2026-08-08 | **The `Agent` role (§A6a) and the `assignable` flag (§A2).** `V40` seeds an eighth `role` row — token-only, PROJECT scope, Test Engineer minus `library:manage`, `case:delete`, `evidence:delete` and `company:read` — as the capability ceiling for agent access to the repository (CLI and MCP server, `docs/plans/2026-08-08-mcp-server-wip-plan.md` §2). **No permission was added, removed or moved between the existing roles**, so `lib/permissions.ts` is unchanged; `openapi.yaml` gains `Agent` in `Role.name`'s enum and an `assignable` field, because the role is on the wire where `Company Member` is not. `PermissionEvaluator` is unchanged — the token intersection that already caps a credential is the only narrowing point, which is the reason the ceiling is a role rather than validation code. `role.assignable` replaces the two hardcoded `COMPANY_MEMBER_ID` comparisons in `RoleGrantService`; the three other comparisons **must not** be converted, because assignability, issuability and listedness are three questions that coincided while there was one non-assignable role (`V40` §2). Handoff §133. |
| 2026-08-14 | **CRUD on plans, cases and steps stops being authorship-scoped (§A3, §A3.5, §A5, §A6a).** Stated as a product requirement, not derived from a defect: *any user who is not a Viewer may create, read, update and delete test plans, cases and steps, no matter who first created them.* `V51` drops the `OWN` qualifier from Test Engineer's `case:delete` and `evidence:delete` and grants it `plan:delete` — three grants, **no new permission**, so the catalogue is still 55 keys and `openapi.yaml` and `lib/permissions.ts` are unchanged. **After it, `OWN` is carried by zero rows.** The mechanism is kept unpopulated on purpose (§A5): the qualifier is *data*, so deleting the arm is what would make a future `OWN` grant fail **open**, and `PLAN_SCOPE` keeps the same column and seam populated anyway. `SeedDataTest.ownIsCarriedByNoGrant` pins the emptiness in both directions so it reads as a decision rather than a seeding bug. Two things did **not** move and each has a reason on the row: `suite:delete` / `artifact:delete` are shared *structure* rather than authored content, and `run:close` / `result:amend` are judgements about the work — §A3.5's "creative vs destructive" principle is rewritten as "content vs shared structure and judgement", because the old framing collapsed *unmaking a colleague* (the qualifier's job) with *unmaking shared structure* (the tier's job) and removing the first read as abandoning the second. **Author-cannot-approve-own-work is untouched** — approving is not CRUD. The `Agent` ceiling gains nothing and becomes Test Engineer **minus five**: `V40` justified dropping the two deletes by `OWN` colliding with `api_token.owner_id`, an argument this migration deletes the premise of, and `plan:delete` had to be promoted from "never held, inherited from Test Engineer's boundary" into an explicit drop the moment the contributor role held it. That promotion was **forced by a test rather than noticed** — `agentIsTestEngineerMinusFive` computes the delta instead of checking a subset, exactly so a permission added to Test Engineer cannot land silently in a credential's ceiling. `V12`, `V40` and `V3` still argue the old position in their headers and are **not edited**: an applied migration is immutable here, so they are history the way `V1`'s `suite_condition` comment is after `V49`. Handoff §207. |
