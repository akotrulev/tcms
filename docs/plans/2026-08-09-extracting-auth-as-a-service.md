# Extracting auth, roles and permissions into a standalone service — impact evaluation

**Date:** 2026-08-09 · **Status:** **evaluation of a possibility — nothing decided, nothing
scheduled, not on any roadmap** · **Scope:** TCMS + "Mission Control" (a *prospective* second
application) as the hypothetical consumers.

> **Read this as "what would be involved if", not as a proposal.** The second application may never
> exist and no commitment has been made. The verdict in §0 and the recommendation in §7 are the
> evaluation's *conclusions if the work were pursued* — they are not adopted policy, and the
> milestones, estimates and designs in the high-level plan are unchanged by this document. Filed as
> **§8 #61** so the analysis is not re-derived later. The current default is that M4 is built
> in-process exactly as §6r designs it.

> **Terminology warning, and it is load-bearing.** In TCMS, **project** means *a client product
> inside a company* (`Instance → Company → Project → suites`). In this document the user's sense —
> TCMS vs Mission Control — is called an **application**. Every time the two are conflated below,
> the design breaks; that is one of the findings, not an editorial nicety.

> **⚠ Correction, same day.** §§1–7 below were written from the **code** before §8 of the high-level
> plan was read, and the plan had already decided a third of it. The reconciliation is §0.5, and it
> is not cosmetic: it moves this from *"should we extract auth"* to *"M4 already builds most of
> this — in whose process would it get built"*, which is narrower and cheaper to consider early.
> The superseded claims are struck where they appear rather than deleted, per the working agreement.

---

## 0. Verdict, up front

**Extracting the permission *engine* as a shared service is possible but is the wrong cut.** The
thing that is genuinely good here — qualifiers, the replace-don't-union boundary, token∩owner
intersection, "permissions never role names" — is ~1,300 LOC of *logic*, and it is the part that
does **not** need a network hop. What does need to be shared across applications is
**authentication and identity federation**, which TCMS **does not have at all today**.

So the recommended cut is not "extract identity", it is:

| Layer | Where it goes | Why |
|---|---|---|
| **Authentication + federation** (Entra ID, GitHub, GitLab SSO; session issuance; the user directory) | **A shared service** — and probably an off-the-shelf one | Genuinely cross-application, genuinely stateful, and entirely greenfield in TCMS |
| **Outbound integration credentials** (Jira, GitHub/GitLab API tokens) | **The same shared service**, as a token vault | Cross-application, and *not* an authentication concern at all |
| **Authorization / permission evaluation** | **A shared library, in-process, per application** | Its correctness depends on being in the same transaction as the data it protects |

Lane A and Lane B as posed are evaluated in §4 and §5 against that.

---

## 0.5. Reconciliation — what the plan had already decided

§8 of `docs/plans/2026-07-30-tcms-high-level-plan.md` and §6r of the same document answer a
substantial part of §§1–7 below, and answered some of it **a week ago**.

| Entry | Already decided | Effect on this document |
|---|---|---|
| **§6r** "Three concerns, deliberately not conflated" | Authentication vs outbound integration authorization vs inbound machine access are separated, with a table of mechanism and lifetime for each | **§3 below is a rediscovery.** §6r already states *"Jira is not an identity provider for this product"* and gives the same reasons (licence coupling, lockout, different scope/consent/lifetime). Independent agreement, not a finding. |
| **#37** (2026-08-02) | **The IdP is Keycloak.** Named so M4 tests against a real issuer and Keycloak goes into compose as a test target | **§7's "broker to an off-the-shelf IdP, probably Keycloak" is already project policy.** Not a recommendation — a restatement. |
| **#40** (2026-08-02) | **No SAML.** OIDC + LDAP/AD only; a second protocol, build on demand | Bounds any shared service's scope too. |
| **#38 / #39** | Invitation required, **no JIT provisioning**; auto-link only on a verified email, treated as a gate | Constrains the shared-directory design in §5 — membership stays a deliberate act per company. |
| **#4 / #41** | Jira **Cloud only**, OAuth 2.0 3LO; service account by default with per-user OAuth opt-in | The credential vault's shape is decided; only its **home** is open. |
| **M4** (§7 milestones, 8–10 wk) | Builds **generic OIDC + LDAP/AD, account linking, claim→role mapping, and four `IssueTracker` adapters with encrypted OAuth/PAT credentials** | **This is the finding.** M4 already builds ~80% of what a shared auth service would provide — in-process. |
| **#54** | Per-company IdP routing deferred to **M6** | The multi-company surface lands after M4. |
| **#43** | *"Nothing beyond result parsing becomes an extension point."* | A standing bias against new seams, which a shared service is a large one of. |

### What this changes

**The question is not whether to extract what exists.** It would be: **if a second application ever
shares this, is M4 built inside TCMS as planned, or as a service that application also consumes?**

That is sharper, and it is also **cheaper to consider early than late** — which is an observation
about cost curves, not an obligation. Building M4 in-process is correct for TCMS alone and is what
every current estimate assumes; it remains the default. The note is only that once it ships, a
second application would either duplicate the OIDC client, the LDAP lane, the claim mapper and the
credential vault, or pay a retrofit — the same shape as #43 (*"nearly free if designed in and
expensive to retrofit"*). **If the second application never exists, this costs nothing and is
simply not pursued.**

Two structural facts make the shared-service version harder than it looks, both already in the plan:

- **`user_identity` is keyed `(company_id, issuer, subject)`** — deliberately company-scoped, and
  §6r calls keying on email the mistake to avoid. A cross-application directory has to decide
  whether that key gains an application dimension or whether `company_id` becomes global (§5's
  unanswered question, now with a schema attached).
- **`claim_role_mapping` maps claims to roles or teams, never permissions**, and IdP-derived grants
  are read-only in the UI. That is the seam where a shared service would attach — and it is
  authorization vocabulary, which §2.4 argues is per-application.

**Neither fact changes the §0 verdict.** Authorization stays in the library; authentication and the
credential vault are the candidates. What changes is the *timing*: this stops being an open-ended
architectural musing and becomes a question to answer **before M4 starts**.

---

## 1. What "the auth logic I like" actually is, measured

| Component | LOC (main) | Portable as-is? |
|---|---:|---|
| `identity/PermissionQualifier.java` | 164 | **Yes** — zero Spring, zero JPA |
| `identity/PermissionSet.java` | 162 | **Yes** — zero Spring, zero JPA |
| `identity/PermissionCatalogue.java` | 99 | Nearly — one Spring import |
| `identity/PermissionEvaluator.java` | 395 | **No** — see §2 |
| `identity/` (whole module) | 8,839 | No |
| `tenancy/` (whole module) | 2,584 | No — this *is* TCMS's isolation model |
| `auth/` (whole module) | 1,881 | Partly |
| Tests over `identity` + `auth` + `tenancy` | **11,617** (of 78,026 total) | Mostly not — see §2.4 |

The ~425 LOC of `PermissionQualifier` + `PermissionSet` are the crown jewels and they are already
pure functions over `Map<String, PermissionQualifier>`. **They could be copied into Mission Control
this afternoon with no service anywhere.** That is worth saying plainly before any architecture is
proposed: a large fraction of the reuse value is available at the cost of a shared jar.

---

## 2. What blocks extracting the evaluator — four hard couplings

### 2.1 The evaluator queries TCMS's own domain tables

`PermissionEvaluator.evaluate` (`backend/.../identity/PermissionEvaluator.java:148`):

```java
if (projects.findScopedById(projectId).isEmpty()) {
    return PermissionSet.empty(catalogue);
}
```

This is the foreign-project check, and its own comment explains it is **not redundant** with the
query predicates: without it, a COMPANY-scope grant would answer "yes" about a project in another
company. A standalone auth service has no `project` table. Two options, both bad:

- **Replicate TCMS projects into the auth service** → a second source of truth about which projects
  exist, in the service whose entire job is to be the single source of truth about access. The
  class's own header names this bug class: *"a permission resolved inconsistently between two code
  paths is the bug class the design exists to prevent."*
- **Move the check to the caller** → every one of TCMS's call sites now has to remember it. That is
  precisely the design the `ProjectAccess` inversion interface exists to prevent.

### 2.2 It is inside the RLS transaction, on purpose

`evaluate` opens with `CompanyContext.require()`. That context is what
`CompanyAwareTransactionManager` turns into the `app.current_company` GUC in `doBegin`, which is what
Postgres RLS reads on all 56 forced policies. So today the evaluator's six repository reads are
**backstopped by RLS**: if the app logic were wrong about the company, the database would still
return zero rows.

Move the evaluator out of process and **that backstop is gone for the permission tables**, because
they no longer live in a database that TCMS's tenancy machinery governs. The auth service would have
to re-implement isolation from scratch — and `CLAUDE.md` already records the two silent RLS traps
(`FORCE` vs `ENABLE`; `nullif(…, '')` for the two spellings of "unset") that this project paid for
once. You would be paying for them again in a new codebase.

`RepositoryTransactionTest`, `RlsIsolationTest`, `PrivilegeSplitTest` and `SchemaConventionTest` are
the mechanical checks that keep that honest. **None of them survives the extraction** — they assert
properties of co-location.

### 2.3 The extraction forces the cache you deliberately deferred

`PermissionEvaluator`'s header, "Not yet done here":

> **Caching.** The table comment specifies a cache keyed on `(company_id, user_id, project_id)` …
> Correctness first: an uncached evaluator is slow, a wrongly-keyed cache is a cross-company leak.

In-process, uncached is *viable* — it is a handful of indexed reads on a warm pool connection.
Across a network it is not: every gated read in TCMS becomes an RPC, and there are hundreds of gated
call sites. **So remote evaluation is not optional-cache, it is mandatory-cache**, and the thing you
are forced to build is the thing whose failure mode you documented as a cross-company leak.

Mitigating factor, and it is real: `auth/PermissionVersionFilter.java` already implements a
**permission epoch** per user (`V6__…_permission_version.sql`) — a monotonic integer bumped when a
grant changes, compared on every request. That is exactly the invalidation primitive a remote cache
needs, and it already exists. It makes remote caching *feasible*; it does not make it *free*, and
its own header notes even the epoch read is currently uncached.

### 2.4 The catalogue is TCMS vocabulary, not a reusable asset

The 55 permissions are `case:approve`, `suite:review`, `run:close`, `evidence:attach`,
`plan:approve`. **Mission Control will share approximately none of them.** What is shared is the
*shape*: keys, scopes (SYSTEM/COMPANY/PROJECT), qualifiers (`ALL`/`OWN`/`PLAN_SCOPE`), the six-role
immutability rule.

And that rule is where a shared service gets expensive. Today: **six roles, seeded, immutable, no
custom roles, not even clone-and-edit** — plus three non-assignable seeded rows (`Company Member`,
`Agent`, `Automation Service`). That decision buys a great deal of simplicity that a lot of code
leans on: `SeedDataTest.agentIsTestEngineerMinusThree` derives the Agent ceiling from the catalogue,
and the frontend's `lib/labels.ts` uses `satisfies Record<Union, string>` so a new enum member is a
*compile error*. A shared service serving two applications with two role sets **cannot keep any of
that** — it needs runtime-configurable catalogues and role sets per application, which is the
custom-role designer that `PermissionEvaluator`'s header says the current design was shaped to make
"additive rather than a rewrite". Additive at the *call sites*. Not additive in the seed data, the
tests that derive from it, or the frontend's compile-time exhaustiveness.

### 2.5 The good news: the consumption seam is already narrow

Counted across `backend/src/main/java/com/tcms`:

- **87 files** outside `identity/` import from it.
- But `PermissionEvaluator` itself is imported by **exactly one** (`IdentityProjectAccess`, the
  inversion impl). `PermissionSet` by one, `PermissionQualifier` by one.
- The bulk of the 87 are `AppUser` / `AppUserRepository` (8 each), `CompanyMembership*` (12),
  `ApiToken*` (5) — i.e. **the identity *data*, joined to everywhere**, not the evaluator.

So the *decision* boundary is already clean — `ProjectAccess.canView` / `canDo` and
`GET /me/permissions` are the whole surface. The *data* boundary is not: authorship, assignees,
reviewers, comment authors and audit rows all FK to `app_user`. Moving the user table out of the
database turns dozens of joins into fan-out lookups. **This is usually what actually kills identity
extractions, and it would here.**

---

## 3. The IdP part — Jira is not an authentication provider

> **→ Superseded as a finding by §6r, which says this already and in more detail.** Kept because
> the *measurement* below is new and load-bearing: the design exists, **none of it is built**.

Grep across `backend/src/main`: zero `oidc`, zero `saml`. The only `oauth2` hits are Spring
Security's resource-server config validating TCMS's **own** self-issued JWTs. The `company:idp`
permission is seeded in `V3` and has **no implementation anywhere**.

So §6r is a *design* with no implementation, and M4 is where it gets one. That gap is the window in
which the shared-vs-in-process decision is free.

**Nothing about the four integrations comes along with an extraction — all four are new work either
way.** And they are not one thing:

| System | What it actually is here | Where it belongs |
|---|---|---|
| **Azure AD / Entra ID** | A real enterprise IdP — OIDC or SAML SSO for your users | Shared auth service. The genuine case for one. |
| **GitHub / GitLab** | Dual: OAuth *login* provider **and** an outbound API integration (repos, pipelines, CI results) | Login → shared service. API access → token vault. |
| **Jira** | **Not an IdP.** Atlassian OAuth grants *your app* access to *Jira's* API on a user's behalf | Token vault / integration credential store — an authorization-delegation concern, not authentication |

Grouping Jira with the other three is the single most common way this kind of project goes wrong: it
lands in the login flow, where it does not belong. TCMS already has `defect:create` and links out to
defect trackers; Mission Control will want Jira too. **A shared outbound-credential vault is
probably the strongest, cheapest, least-coupled case for a shared service in this whole
evaluation** — and it touches the permission engine not at all.

---

## 4. Lane A — one service instance per application

> Each consuming application gets its own deployment of the auth service, its own database.

### What it actually is

If every application has its own instance with its own database and no shared state, then **the
network hop buys nothing.** Zero cross-application queries are possible by construction — that is
the premise. What is being shared is *code*, and code is shared by a **library**.

### The ledger

| | |
|---|---|
| **Buys** | Reuse of the engine and the schema shape; independent release cadence; blast radius contained per application; no new cross-application data model to design |
| **Costs (vs a library)** | Loses the RLS backstop (§2.2); loses transactional co-location; forces the mandatory cache (§2.3); adds a per-request RPC to hundreds of gated call sites; splits `app_user` from every FK that references it (§2.5); doubles the operational surface (two more deployments, two more databases, two more migration lanes) |
| **Costs (vs doing nothing)** | Everything above, plus a live-data migration of TCMS's existing grants |

### Verdict

**Do this as a shared library, not a service.** Concretely: a `tcms-authz` Gradle module (or a small
published jar) containing `PermissionQualifier`, `PermissionSet`, `PermissionCatalogue`, the
evaluator *algorithm* parameterized over a `GrantSource` port, and the migration templates for
`permission` / `role` / `role_permission` / `role_assignment`. Each application supplies its own
catalogue, its own role seeds, its own `GrantSource` implementation against its own tables — and
keeps its own RLS, its own transaction, its own FKs.

That gets ~90% of the reuse value at roughly 10% of the cost, and it does not surrender a single
one of the four invariants in §2.

---

## 5. Lane B — one shared instance, permissions keyed by the application you authenticate from

> One service, one database. Which application you authenticate from determines what you get; or
> applications are themselves gated by permission.

### What it actually is

This is a real authorization server with a new top dimension above `company`:

```
Instance → Application (TCMS | Mission Control) → Company → Project → …
```

That is Keycloak's realm/client model, and building it is building Keycloak.

### The unanswered design question that decides everything

**Is a company shared across applications, or per-application?** Both readings are defensible and
they lead to different products:

- **Shared company** ("Acme is one client, using both TCMS and Mission Control"). Now `company_id`
  is a *cross-application* identifier. TCMS's ERD §0.1 rule — `company_id` on every table, leading
  every composite index and every uniqueness constraint — was designed with the company as *this
  instance's* isolation boundary. Making it global means the auth service owns company lifecycle
  (create/suspend/delete), so `provision-company` stops being TCMS's one path in and becomes a
  downstream reaction to an event from the auth service. That is a large, invasive change to the
  most load-bearing invariant in the codebase.
- **Per-application company** ("Acme-in-TCMS and Acme-in-MC are different rows"). Then the shared
  database shares nothing but the user directory, and **you have built Lane A with a shared
  hostname** — all of §4's costs, plus one shared blast radius.

**Do not start Lane B without answering this.** It is not an implementation detail; it is the
product.

### The ledger

| | |
|---|---|
| **Buys** | True SSO — one login across both applications, which is a real end-user benefit; one place to integrate Entra/GitHub/GitLab instead of two; one place to manage a person who exists in both applications; one audit trail for access changes |
| **Costs** | Everything in Lane A, plus: a genuinely new cross-application data model; runtime-configurable catalogues and role sets, which forfeits the six-immutable-roles simplification and everything derived from it (§2.4); a shared blast radius — the auth service down means *both* applications are down, and a bug in it is a cross-*application* leak, a strictly worse failure than the cross-company leak the current design guards; live migration of TCMS's existing grants with no rollback path once Mission Control is also on it |

### The build-vs-buy question this forces

Lane B is Keycloak's job description. Keycloak (or Zitadel / Ory Hydra+Keto) gives you, for free and
already hardened: Entra/GitHub/GitLab identity brokering, OIDC/SAML, SSO, per-realm and per-client
roles, token issuance, admin UI, and the operational story.

**What none of them gives you is the part you said you like:** `PermissionQualifier`'s `OWN` /
`PLAN_SCOPE` narrowing, the project-grant-*replaces*-company-grant boundary, the
`evaluate(owner) ∩ role ∩ project` token intersection, the `OWN`-follows-`api_token.owner_id`
attribution. Those are domain semantics. Keycloak's role model cannot express them and its
authorization-services module (Keto likewise) would express them badly and remotely.

Which lands on the same split as §0: **broker authentication to Keycloak; keep authorization in the
library.**

---

## 6. Sizing

Rough order of magnitude, in engineer-weeks. These are estimates from the measured surface, not from
a task breakdown — treat the *ratios* as the signal, not the absolute numbers.

| Option | Estimate | Main drivers |
|---|---:|---|
| **Shared authz library** (§4 verdict) | **3–5 wk** + ~1–2 wk per adopting app | Parameterizing the evaluator over a `GrantSource` port; extracting catalogue + role seeds to config; re-homing ~4k LOC of tests |
| **Keycloak for authN only**, authz stays local | **2–4 wk** for TCMS | New login flow, user provisioning/linking on first SSO login, retiring `AuthenticationService`'s password path, migrating existing users. `PermissionVersionFilter` and the API-token path survive intact |
| **Jira/GitHub/GitLab outbound credential vault** | **2–3 wk** | Genuinely additive; touches the permission engine not at all |
| **Lane A as a service** | **8–14 wk** | Library work + service + deployment + the mandatory cache + splitting `app_user` from its FKs |
| **Lane B, built** | **4–7 months** to parity, plus permanent ops cost | Everything above + cross-application model + configurable catalogues + grant migration + forfeiting the derived-invariant tests |

---

## 7. Recommendation

1. **Do the library, not the service** (§4 verdict). It is the only option that keeps all four
   invariants in §2 and it captures most of the reuse.
2. ~~**Broker authentication** to Entra/GitHub/GitLab via an off-the-shelf IdP.~~ → **Already
   decided: #37 names Keycloak, 2026-08-02.** The live item is not *whether* to broker but *where
   the broker's consumer lives* — see §0.5. Keep `PermissionVersionFilter`, the API-token path, and
   the evaluator exactly where they are, in **both** readings.
3. **Build the Jira/GitHub/GitLab outbound credential vault as a shared component.** It is the clean
   cross-application concern and is independent of items 1 and 2, so it can go first. Its *shape* is
   already fixed by #4 and #41 (Cloud-only 3LO, service account with per-user opt-in); only its home
   is open. **Note this is currently M4 scope**, so "extract it" means "build M4's credential half
   somewhere else", not "add work".
4. **Do not build Lane B** unless SSO-across-applications is a stated product requirement *and* the
   shared-vs-per-application company question in §5 has been answered. If it becomes a requirement,
   adopt Keycloak for it rather than building it — which #37 already positions you for.
5. **Nothing here is scheduled, and item 5 is the only time-shaped observation in the document:**
   *if* the second application ever becomes real, this is cheapest to consider before M4 is built
   and more expensive after. That is a note about sequencing, **not a deadline and not a reason to
   act now** — and it is worth nothing at all if the second application never exists.

## 8. If Lane B is pursued anyway — what must be decided before any code

- Is a **company** shared across applications, or per-application? (§5)
- Who owns **company lifecycle** — the auth service, or `provision-company`? If the former, TCMS's
  single provisioning path becomes event-driven, and idempotent top-up-on-release stops being a
  local operation.
- Do the **six immutable roles** survive per-application, or does the service gain a role designer?
  If the latter, name what replaces `SeedDataTest.agentIsTestEngineerMinusThree` and the frontend's
  `satisfies Record<Union, string>` exhaustiveness.
- Where does the **foreign-project check** (§2.1) live, and how does the service learn what projects
  exist without becoming a second source of truth?
- What is the **cache invalidation contract**? The permission epoch exists; specify its key, its TTL
  ceiling, and what happens on a stale read.
- What is the **degraded mode** when the auth service is unreachable? Fail-closed matches this
  project's stated posture (`CompanyContextTaskDecorator` clears context so a forgetful handler
  fails closed) — but fail-closed on a shared dependency means both applications are down.

---

## Sources

Measurements taken 2026-08-09 at `fce9739`, via `wc -l` and `grep` over
`backend/src/main/java/com/tcms` and `backend/src/test`. Key files read in full:
`identity/PermissionEvaluator.java`, `project/ProjectAccess.java`, `auth/PermissionVersionFilter.java`.
Design context: `CLAUDE.md` (tenancy invariant, permission matrix, the five things that change how
the table reads), `docs/specs/2026-07-30-tcms-roles-permissions-naming.md`.
