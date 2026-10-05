# 004 — Issue tracker integration (Jira · Azure DevOps): plan of action (be · db · fe)

**Status (filed 2026-10-05):** Delivered — through plan 006: B1 `3ba1555f` (all eleven paths), B4 `fcb1ad61` (the integration module, V72–V74 since absorbed into V1), B6 `2c0e1214` (the UI); gated green in B7 `6932ebeb`. Phase 4 (sync-back) was deleted by D8, not deferred. Unverified: no real Jira or Azure DevOps instance has been connected; transport is proven against `MockRestServiceServer` and the `tracker-fake` profile only. Any `Status`, task table or checkbox below this line is the plan's history, not its state.

**Status:** proposed, nothing implemented · **Date:** 2026-08-28 · **Revision 5**
**Answers:** the standing Phase 2 line in [`docs/features.md`](../../features.md) — *"Issue tracker
integration (Jira, GitHub Issues, GitLab Issues, Azure DevOps) — link cases ↔ issues, one-click
defect creation from a failed step, status sync-back, inline status hover-cards"* — **narrowed by the
product to link + preview + one-click defect creation, with no status sync-back.**
**Initiative number:** `004-`. **Migrations start at `V72`**: `V70` is reserved for §255's
`gherkin_source` deleter and `V71` is claimed by ~~[`003`](003-case-authoring-redesign.md)~~ →
**[`005`](005-renders-backend-support.md), slice S3 — grounded 2026-08-28.** 003's phases 1–2 carry
no DDL and its only migration belongs to a blocked phase, so 003 released its claim rather than hold
a number ahead of this plan's (003's header, 005 §8.2). This plan claims **`V72`, `V73`, `V74`** and
nothing above — **unchanged** — and **`V72` may not be written before 005's `V71` has landed**.

## Revision history

| Rev | What it was |
|---|---|
| 1 | Written directly, not against the code. Ten decisions, four phases, a shared `SERVICE` credential. |
| 2 | Pressure-tested against the code. Seven corrections; `SERVICE` spine kept; six open questions raised. |
| 3 | The product answered questions 2 and 5. One answer invalidated the `SERVICE` spine. Recorded as an appendix. |
| 4 | Revision 3 folded into the decisions and phases. **The credential is per-user; there is no service credential anywhere in this plan.** Status sync-back is out of scope. One of revision 3's provider constraints corrected. Four open questions remained. |
| **5** | **This.** All four remaining questions answered and closed as `D17`–`D20`. **The link stores the tracker's stable id and no key**, which changes `V74`'s columns and what an unpreviewable chip may render. A Test Lead can see who has connected. **Nothing in this plan is still asking a question.** |

Superseded reasoning is kept struck-through in place, per the working agreement — several of these
decisions look arbitrary until you read the thing they were reversed by.

---

## The answers this plan is built on

Three product answers, quoted, because every decision below traces to one of them.

> **A1** — *"no health change, it's just an optional link for tracking"*

> **A2** — *"permissions for viewer and engineer are per project, so if a user is not assigned to a
> project, he won't see anything from it in our tcms"*

> **A3** — *"with a preview if the user has an authorized active session in the browsers, so the user
> himself has access to the jira/azure link. If not, it will just say preview unavailable"*

Plus two scope decisions taken when A3's proposed mechanism turned out not to exist (see
*The preview is the viewer's own access*):

- **Per-user connection is the read path.** Each person connects their own tracker account; the
  server calls with *their* credential.
- **Scope is link + preview + one-click defect creation. No status sync-back.**

And four answers that closed the last of the open questions:

- **A4 — the link stores the tracker's stable id, not the key.** Chosen with the downside stated in
  front of the decider. `D17`, and it is the one that changes a column list.
- **A5 — a link survives its binding's deletion** and degrades to a plain URL. `D18`.
- **A6 — a Test Lead sees who has connected, by name.** `D19`, taken over a real privacy
  counter-argument that was put and knowingly overridden.
- **A7 — GitHub and GitLab stay out** of this plan. `D20`.

---

## What exists today, verified against the code

Five facts decide most of this plan. Four are the *absence* of something the docs half-promise; the
fifth is machinery nobody thought to look at.

1. **No credential store exists.** `integration_credential` is designed in the ERD
   (`docs/specs/2026-07-30-tcms-erd.md:208`) and has never been migrated. There is no OAuth flow, no
   API client, and no project↔tracker binding of any kind. What *does* exist is a different shape
   that superseded it — see C6.
2. **`defect_link` is a typed-in link and was deliberately made to say so.** `V1` defaulted
   `provider` to `'JIRA'`; **`V62` dropped that default** because it named a product the instance
   does not talk to, and `DefectLinkInput` (`backend/…/execution/DefectLinkInput.java:20`) has no
   `provider` field so a caller cannot claim one either. The writer stores the literal `EXTERNAL`
   (`execution/DefectLink.java:55`, `:106`).
3. **`status_cache` and `synced_at` are served to clients and have no writer.** NULL is a true
   answer. `DefectLink.java:33` names "M4's adapter" as the thing that will fill them. **This plan
   does not fill them** — see D14, which is a change from revisions 1–3.
4. **`doc_link` can hold a story's URL but not its identity.** `target_kind` is
   `DOC_BLOCK · EXTERNAL_GIT · EXTERNAL_URL` (`V1__initial_schema.sql:993`). An `EXTERNAL_URL` row
   has a URL and nothing else: no provider, no key.
5. **`doc_link` has a mirror, and the mirror feeds case health.** `case_doc_link` (`V46`, widened by
   `V47`) is refreshed by `repository/DocumentationFacts.java:203-211`, whose second arm copies
   **every** row with `target_kind <> 'DOC_BLOCK'` into a table whose `ck_case_doc_link_target`
   enumerates exactly three kinds. `repository/SpecLinkage.java` then derives `UNLINKED` from a
   `count(*)` over that mirror **with no `target_kind` predicate at all**. A1 is therefore not a
   no-op; it is the first kind-discrimination that query has ever needed. See C3.

**So this plan is the slice `V62` and `DefectLink`'s javadoc were written in anticipation of** —
but only half of it. `provider` becomes able to say `JIRA_CLOUD` or `AZURE_DEVOPS` *truthfully*,
because the server will have spoken to one. `status_cache` and `synced_at` stay exactly as `V62`
left them, and D14 says why that is now a decision rather than a gap.

---

## Decisions taken

| # | Decision | Effect |
|---|---|---|
| **D1** | **A project binds to one or more tracker *projects*, not a workspace.** The unit is a Jira project key (`PAY`) or an ADO organization + team project (+ optional area path). Many rows per TCMS project; exactly one flagged default. | Table `tracker_binding` (`V72`), and under D2 it is **non-secret** — a connection fact, nothing more. A TCMS project spanning a Jira project *and* an ADO board is the normal case. |
| **D2** | ~~`SERVICE` mode only. `PER_USER` is deferred, not designed away.~~ → **superseded by A3. The credential is per-user and there is no service credential in this plan at all.** Each person connects their own tracker account; every outbound call — search, preview **and issue creation** — is made with the credential of the person whose request it is. | The largest change in this revision. `tracker_binding` loses `secret`, `secret_hint`, `account_id`, `expires_at`, `mode` and `user_id`; a second table, `tracker_identity`, gains all of them keyed by `(company_id, user_id, provider, base_url)`. Everything downstream moves with it — see *The per-user inversion*. |
| **D3** | **Per-user PAT / API token first; OAuth second, against the same table.** Jira Cloud = the user's own email + API token (Basic); Jira DC = the user's own PAT (Bearer); ADO = the user's own **organization-scoped** PAT (Bearer). | ~~PAT because all three are one string in a box.~~ → **re-argued in *Which mechanism, and in which order*.** The reason is now different and stronger: a PAT is the only mechanism on all three providers that is **entirely user-side** — no app registration, no Entra tenant config, no tracker-admin action, and no public redirect URI on a self-hosted instance. `tracker_identity` carries `auth_method`, `refresh_token`, `expires_at` and `scopes` from day one so OAuth is a second value, not a redesign. |
| **D4** | **It lives in the `integration` module, in a `tracker` sub-package. No new module, no foundation change, and `allowedDependencies` does not widen.** | Confirmed under the per-user inversion, which was the one thing that could have broken it — see C7. The per-user table needs a `user_id`, and `integration` already handles one as an opaque `UUID` it never resolves (`NotificationChannelService.java:149`), exactly as `storage` does. |
| **D5** | **Nothing about a story is mirrored into TCMS.** No `story` table, no import, **and no cached title or status on the link row.** | ~~Because mirroring leaks permissions.~~ → superseded in rev 2 by C1, then **restored and strengthened by A3**: under per-user reads, a title fetched with one person's credential and stored on a shared row would be served to a person who cannot see the issue. The prohibition is now a security property, not a tidiness preference. See D15. |
| **D6** | **The SPA composes issue-creation and linking, *and* an outbox event is the durable backstop.** | Unchanged by the per-user inversion, and I checked: the seam is forced by the module rule (`execution` may not import `integration`), not by the credential model. The `bindingId` collision risk survives **intact** — C4. |
| **D7** | **Case ↔ story linking is a new `doc_link` target kind, `EXTERNAL_ISSUE`.** | Confirmed, but the reason has changed under A1 and the change is recorded rather than buried — see C3's closing paragraph. It is now an *annotation* rather than a traceability edge, and it is still `doc_link` because a separate table would duplicate that table's RLS, delete-cascade, mirror and rendering story to gain one boolean. |
| **D8** | ~~Status sync-back is a poller, not a webhook.~~ → **superseded: there is no status sync-back.** Out of scope by product decision. | Revision 2's Phase 4 is deleted. No `TrackerStatusScheduler`, no company sweep, no backoff ledger, no rate-limit budget. The whole risk row about becoming the loudest client Jira has goes with it. |
| **D9** | **`provider` on `defect_link` stays free text. No `CHECK`.** | Confirmed, C2. `DefectLink.java:50-54` states it as a decision: *"Not a default and not an enum: the column is free text because M4's adapters name their own trackers, and constraining it to a Java enum now would make adding an adapter a schema change."* |
| **D10** | **The URL guard is `integration`-wide, not tracker-only, and it is in scope from phase 0.** | Confirmed and now *more* load-bearing: under D2 the server holds one credential per person per host, so an operator-supplied `base_url` that resolves somewhere unintended exfiltrates **every connected user's** token to that host, not one bot's. The surface already exists unguarded on the notification side — C5. |
| **D11** | **`case_doc_link`'s CHECK, `DocumentationFacts`' refresh *and* `SpecLinkage`'s predicate are part of `V74`.** | Forced by facts 4–5 and by A1. Three code sites, not one. |
| **D12** | ~~Which permission gates reading another system's issues is an open question.~~ → **answered by A2 + A3. No new catalogue key. The catalogue stays at 55.** | TCMS-side visibility is project assignment, which is already how the product works (two gates: company membership, then an explicit project grant). Tracker-side visibility is the viewer's own credential. There is nothing left for a 56th key to gate. |
| **D13** | **An `EXTERNAL_ISSUE` link does not change case health, coverage, or any derived fact.** A1, taken literally. | The default behaviour is the opposite (`SpecLinkage` counts every mirror row), so this costs an explicit predicate and a test that fails if a later reader tidies it away. C3. |
| **D14** | **`defect_link.status_cache` and `synced_at` stay writerless, and `V62`'s position is unchanged.** | ~~Revision 2: "`V73` — `defect_link` starts telling the truth", both columns get their first writer.~~ → **superseded.** With no poller there is nothing to write them, **and** under D2 there is a second, independent reason: a status fetched with one person's credential must not be written into a column three screens render to everybody. Two reasons where there was one. `V73` shrinks to the `provider` half. |
| **D15** | **No tracker content is ever persisted, cached across users, or logged.** Titles, statuses, assignees and descriptions are render-time only, and every cache key includes the requesting user. | The per-user inversion's sharpest failure mode is a cache keyed by binding. Revision 2 specified exactly that — `TrackerCache`, "per binding, TTL 60s" — which under D2 serves user A's issue to user B. See *The cache is the leak*. |
| **D16** | **A person who has not connected, or whose credential cannot see the issue, gets the same answer: "Preview unavailable".** No distinction is drawn in the rendered output between *not connected*, *token expired*, *no access* and *tracker down* — only the **call to action** differs, and only for the viewer's own account. | A3's literal requirement. Distinguishing them in the chip would turn the preview into an oracle for what exists in a tracker the viewer cannot read. |
| **D17** | **A link stores the tracker's stable id and no key.** ~~Leaning: store both, display the key, resolve by id.~~ → **superseded by A4.** `V74`'s columns become `external_provider`, `external_issue_id` and `external_host`; there is no `external_issue_key`. **And the consequence that follows from it: nothing derives a display label from `external_url`.** | The decision is the product's and is implemented as given. Its cost is **smaller than revision 4 framed it** and the reason is worth reading — see *The identifier, under id-only*. The one rule that has to be obeyed for that to be true is the second sentence: parsing `PAY-991` out of a stored URL yields a label that displays confidently, cannot be known to be stale, and is exactly the failure id-only was chosen to avoid. |
| **D18** | **A link survives the deletion of the binding it was made through**, and degrades to a plain URL: the chip stops previewing, keeps its href, and says so. | A5. It is somebody's traceability record and not the deleting request's business — `doc_link`'s existing delete semantics already say this. It is also **why `doc_link` carries `external_host` rather than a `binding_id`**: a foreign key from `documentation`'s table into `integration`'s would be a cross-module edge the architecture forbids *and* would force this row to be deleted or nulled with the binding. |
| **D19** | **A Test Lead can see who on the project has connected — names, not a count.** On the project settings page, gated by the existing `integration:manage` (IA · CA · TL). Scoped to the hosts *this project's bindings name*. | A6, taken **over a stated counter-argument**: a lead learns which team members hold a Jira or ADO account, which is personal information those people gave to TCMS for their own use and not to their lead. Overridden knowingly, because *"why can nobody on this team file defects?"* is unanswerable without it. The override is bounded by what the screen may show — D19's field list, below, is part of the decision and not an implementation detail. |
| **D20** | **GitHub and GitLab stay out of this plan**, and are a cheap follow-on: one adapter and one `provider` value each. **No migration and no contract change.** | A7. That it is cheap is a property this plan paid for rather than a hope: `defect_link.provider` carries no `CHECK` (D9), `tracker_identity` is keyed by host rather than by binding (D2), and `TrackerPort` returns `TrackerIssue` rather than a Jira DTO. Per-user PATs suit both better than they suit ADO, since neither vendor is retiring them. |

---

## The per-user inversion — what A3 changed and what it cost

Revision 3 established that A3's proposed mechanism does not exist. The finding stands and is worth
keeping, because it is the reason this plan is shaped the way it is:

**"The user has an authorized active session in the browser" cannot be reached from our page.**

- **Jira Cloud does not support CORS from third-party origins, deliberately.** Atlassian's tracker
  (`JRACLOUD-65573`, `JRACLOUD-30371`) states the reason and it is exactly this design: session
  authentication is accepted, so allowing cross-origin reads *"would allow any site to make random,
  authenticated requests to your site"*. `fetch(…, {credentials: 'include'})` from the SPA is
  refused before it is answered.
- **The iframe route is closed too.** Tracker pages send `X-Frame-Options: DENY` against
  clickjacking, and an embedded session cookie is a *third-party* cookie, which Chrome and Safari
  block or clear by default.

So the browser cannot lend us the session. The server can hold the user's own credential instead,
which delivers A3's intent exactly — **the preview shows what this viewer is entitled to see, and
nothing else** — and that is the choice the product made.

### What the inversion buys

**It deletes the leakage property this plan was previously forced to accept.** Revision 2's C1 had
to state, in the settings-page copy, *"Everyone who can see this project can see every issue this
credential can see."* That sentence is gone. Under D2 TCMS is structurally incapable of showing a
person tracker content they could not have read themselves, because it never holds a credential
that could fetch it.

It also improves the defect-creation half, and this is the coordinator's correction, verified and
adopted: **the issue is created as the person, not as a bot.** The Jira `reporter` / ADO
`System.CreatedBy` is the actual human looking at the failed step. A service credential would have
filed every defect in the instance under one machine account, which is worse product *and* worse
audit. The objection that a viewer's consent cannot be assumed at failure time does not apply —
they are looking at the step, in their own session, and clicking the button **is** the consent.

### What the inversion costs

| Cost | Where it lands |
|---|---|
| A person must connect before anything works | Account settings page, plus a *"Connect your Jira account"* call to action wherever a preview or a defect button is dark (D16) |
| Every connected user is a credential at rest | `tracker_identity` rows, each AES-GCM'd — many rows where revision 2 had one per project |
| Credentials expire per person, silently | Jira Cloud API tokens now carry a **mandatory maximum one-year expiry**, so *every* Jira Cloud connection dies within a year by design. `expires_at` + `last_error` + a health row in account settings are not optional metadata; they are the difference between "reconnect your account" and "the button stopped working" |
| Caching becomes a security problem | D15, and *The cache is the leak* below |
| One-click defect creation is unavailable to the unconnected | Degrades to the existing manual defect form, which still exists and still works. **Not a regression** — that form is the whole feature today |

### What it does **not** cost, checked

- **`allowedDependencies` does not widen.** `tracker_identity.user_id` is a `UUID` that `integration`
  never resolves to a person — the module already takes `UUID userId` as a plain parameter
  (`NotificationChannelService.java:149`, `:193`, `:230`) and gets it from
  `@AuthenticationPrincipal Jwt` in the controller (`NotificationChannelController.java:66`). This is
  `storage`'s pattern verbatim: *"it holds no domain concept, so an `owner_id` is a `UUID` it never
  resolves."* D4 survives the inversion untouched, which was the one architectural risk in it.
- **`SecretCipher` is still the right and only tool**, still package-private, still in the same
  module as the code that needs it. The inversion moves *which row* holds the ciphertext, not where
  the crypto lives.
- **The two-call seam is unaffected.** It is forced by the module boundary, not by the credential
  model — C4.

---

## Which mechanism, and in which order — PAT before OAuth

Three verified provider constraints, and one of revision 3's is corrected.

| Provider | Per-user PAT / API token | Per-user OAuth |
|---|---|---|
| **Jira Cloud** | The user's own Atlassian API token + their email, HTTP Basic. Self-issued, no admin involved. **Mandatory expiry, 1–365 days**, enforced since Dec 2024; pre-existing non-expiring tokens were retired in March 2025 | Atlassian OAuth 2.0 3LO. Needs **the TCMS operator** to register an app in Atlassian's developer console — per instance, and this is a self-hosted product |
| **Jira Data Center** | The user's own PAT, Bearer. Self-issued | OAuth 2.0 incoming link. Needs **a Jira admin** on the customer's instance to create the link and hand over a client id/secret |
| **Azure DevOps** | The user's own PAT, Bearer. Self-issued — but see the retirement note below | **Microsoft Entra ID OAuth**, not ADO OAuth. Needs an Entra app registration in the customer's tenant, and very likely tenant admin consent |

### Revision 3's Jira DC constraint is wrong as stated

Revision 3 recorded *"Jira Data Center OAuth 2.0 needs Jira 11.2+"*. Atlassian publishes the **Jira
OAuth 2.0 provider API** documentation for Data Center **9.12, 10.1, 10.3, 11.0 and 11.3**, and the
`/rest/oauth2/latest/authorize` + `/rest/oauth2/latest/token` endpoints are the same across them. The
capability predates 11.2 by several major versions. What *does* vary by version is scope
granularity, so the honest statement is **"the minimum Jira DC version we support for OAuth is an
operator question, not 11.2"** — and it does not affect the ordering below, because DC OAuth needs a
tracker admin either way.

### Azure DevOps' PAT story is the one actively being retired

Microsoft is retiring **global** PATs on a published timeline: creation and regeneration blocked
from **15 March 2026** — already past — and full decommission on **1 December 2026**, about three
months from this plan's date. **Organization-scoped PATs are not part of that retirement** and
remain the supported form. Separately, ADO administrators now have control-plane policies that can
**block PAT creation outright** in a tenant, and Microsoft's own guidance is to move to Entra-based
short-lived auth. ADO's proprietary OAuth is itself deprecated — no new app registrations since
April 2025, full deprecation during 2026 — so **Entra ID OAuth is the only forward path for ADO**,
and revision 3 is right about that.

### The recommendation

**Per-user PAT first, for all three providers. OAuth second.** Reasons, in the order they matter:

1. **A PAT is the only mechanism that needs nobody but the user.** OAuth on every one of the three
   requires an app registration by an operator or a tracker admin *before the first person can
   connect*. For a self-hosted product shipped as one image, that turns "try the integration" into a
   provisioning project, and it would block the whole feature on a task this plan cannot do for the
   customer.
2. **The storage shape is identical.** `tracker_identity` carries `auth_method`,
   `refresh_token BYTEA NULL`, `expires_at` and `scopes` from `V72`, with
   `CHECK (auth_method = 'PAT')` in slice 1 — D2's own trick, applied correctly this time. OAuth is a
   relaxed `CHECK`, a consent controller and a refresh path, against an unchanged table and an
   unchanged `TrackerPort`.
3. **The failure modes are already on the page.** A PAT expires; so does an OAuth access token. Both
   surface as the same *"reconnect your account"* row that Jira Cloud's mandatory one-year expiry
   makes mandatory anyway.

**ADO ships in slice 1 on organization-scoped PATs**, and this is decided rather than deferred: the
retirement covers global PATs only, and an org-scoped PAT is what an ADO user is told to create
today. The two risks are stated, not hidden — a tenant that has enabled the PAT-blocking policy
cannot use ADO until the Entra slice, and that must render as a specific, honest message rather than
a generic auth failure (*"Your organization has disabled personal access tokens. Entra sign-in is
not available yet."*). **The counter-ordering — hold ADO back until Entra — was priced and
rejected**: it would ship half the feature the product asked for, on a timeline set by somebody
else's roadmap, to avoid a mechanism that is currently supported.

---

## The identifier, under id-only (D17)

The link row's identity is **the tracker's own stable id**. Revision 4 leaned the other way and the
product chose this; what follows is what it actually costs, which is **less than revision 4 said**,
provided one rule is obeyed.

### What "id" means per provider, and whether it is enough

| | The id | Fetch from the id alone? | Live URL from the id alone? |
|---|---|---|---|
| **Jira Cloud / DC** | The numeric issue id (`10042`). Immutable across a project move; the **key** is what changes | **Yes.** `GET /rest/api/3/issue/{issueIdOrKey}` takes either | **No.** The browse URL is key-based (`/browse/PAY-991`), so a fresh URL exists only *after* a successful fetch returns the current key |
| **Azure DevOps** | The numeric work-item id. **Unique per organization, not per project**, so it survives a move between team projects in the same org | **Yes.** `GET https://dev.azure.com/{org}/{project}/_apis/wit/workitems/{id}` — the project segment is API consistency, not scope | **Yes.** `https://dev.azure.com/{org}/_workitems/edit/{id}` is id-addressable and cannot go stale |

So the id is sufficient to **fetch** on both, which is the thing that matters: a connected, entitled
viewer always sees the *current* key and title, fetched live, and never a stored one. The
asymmetry is only in URL construction, and it is absorbed by `external_url` already being required
for `EXTERNAL_ISSUE`.

### The stored URL is a pointer, and it is allowed to be historical

`external_url` is what the linker pasted or what the create call returned. After a Jira project move
it names the old key — and the database cannot know that. Three things make that survivable, and
they are the reason the downside is smaller than revision 4 claimed:

1. **Jira redirects moved keys.** The old key is retained (`moved_issue_key`) and
   `/browse/OLD-1` redirects to the issue in its new project — *"accesses to the previous Issue key
   or URL are automatically redirected to the current key or URL"*. So a stale href still lands on
   the right issue.

   **Two exceptions, and the first one was stated wrongly in revision 5.** ~~The exception is a site
   migration.~~ → Atlassian's own KB documents the realistic failure as **a move performed by a
   third-party app**: such tools *"may use Jira's Java API directly and may contain bugs that result
   in the `moved_issues_key` not being properly updated"*, and the redirect record is then never
   written. A **site migration** (Cloud-to-Cloud, Server-to-Cloud) is the second, and does not carry
   the mapping either.

   Both fail the same way and it is the good way: the href is **dead**, which is a visible failure
   rather than a quiet wrong answer. Neither is detectable from here, and neither needs to be —
   that is what makes the id the stored identity and the URL merely the href.
2. **ADO's URL is id-derived**, so it cannot go stale at all.
3. **Nothing renders text from it.** Which is the rule:

### The rule: no label is ever derived from `external_url`

Parsing `PAY-991` out of a stored URL gives a string that *looks* current, is unverifiable, and goes
silently wrong after exactly the event id-only was chosen to survive. It is also strictly worse than
having stored the key, because it adds a per-provider regex that fails on any URL shape nobody
anticipated. **So it is forbidden, and the migration comment says so** — the same way `V74` forbids a
cached title column, and for a related reason: both are a fetched fact given a permanent home.

### What an unpreviewable link therefore renders

Three states, and this is the answer to *"which of these does the user get"*:

| Viewer | Chip shows |
|---|---|
| Connected, entitled | The **live** key, title and status category, fetched this request. `PAY-991 · Fix the rounding · In progress` |
| Connected but the tracker is down or refuses, **or** not connected, **or** the binding was deleted (D18) | `Jira issue 10042 ↗` — the **provider and the stable id**, plus the href. No key, no title, no status |
| No link | — |

The fallback names the id rather than saying a bare *"Issue"*, and that is a deliberate small choice:
the id is the one string on the row that is **never wrong**, and five external links on one case
would otherwise render as five identical chips with no way to tell them apart. It is not
human-recognisable — nobody says "go look at 10042" — and that is the honest residual cost of
id-only, stated rather than engineered around: **during an outage, or before you connect, a list of
issue links is harder to scan than it would have been.** For anyone who has connected, which is
everyone the feature is for, it never appears.

**What this is not:** it is not a confidentiality measure. The key is in the href regardless, so
withholding it from the label protects nobody. The reason is truthfulness — a stale *pointer* that
redirects still gets you there, and a stale *label* just lies.

---

## Who has connected — what that list may show (D19)

The project settings page gains a members list beside the bindings. It exists to answer one
operational question — *why can nobody on this team file defects?* — and it is scoped to that:

**It shows:** each project member who could use the integration, and per **host this project's
bindings name**, one of three states — `Connected` · `Needs attention` · `Not connected`.

**It must not show:** the credential · the `secret_hint` · the `account_id` (the person's tracker
email or account handle) · the `scopes` · the `expires_at` date · the text of `last_error` · anything
at all about *what that person can see* in the tracker. `Needs attention` is a state, and the
sentence explaining it belongs on the affected person's own account page, where the credential is
theirs to read about.

**It is scoped to this project's bound hosts**, so a lead learns nothing about a colleague's
connections to a tracker their project is not bound to. That scoping is the mitigation the privacy
counter-argument bought; it is not a side effect of the query being convenient.

---

## Module placement

```
com.tcms.integration                    (allowedDependencies: api, tenancy, project — UNCHANGED)
├── SecretCipher · SecretHint · SecretRedaction        shared, package-private, reused as-is
├── OutboundUrlGuard                                   NEW, shared — C5
├── WebhookPoster                                      adopts the guard (warn-then-enforce)
├── NotificationChannel* …                             untouched
└── tracker/
    ├── TrackerPort                 search · get · create        (no `statuses` — D8 is out)
    ├── TrackerIssue                key, id, url, title, statusName, statusCategory, assignee, updatedAt
    │                               — a RENDER-TIME record. Never persisted, never cached across users (D15)
    ├── TrackerDraft                title, body, issueType + the binding's fixed fields
    ├── TrackerProvider             JIRA_CLOUD · JIRA_DC · AZURE_DEVOPS   (Java enum; the defect_link column is NOT constrained — D9)
    ├── TrackerAuthMethod           PAT   (OAUTH arrives with slice 2 — D3)
    ├── JiraAdapter                 /rest/api/3/search/jql · /issue · /issue/{key}
    ├── AzureDevOpsAdapter          /_apis/wit/wiql · /wit/workitems/${type} · /wit/workitems?ids=
    ├── TrackerClient               the shared RestClient: timeouts, size cap, OutboundUrlGuard
    ├── TrackerBinding  + Repository + Service + Controller      project-scoped, NON-SECRET
    ├── TrackerIdentity + Repository + Service + Controller      user-scoped, HOLDS THE CREDENTIAL
    ├── TrackerCache                keyed (userId, bindingId, issueKey) — D15
    ├── TrackerIssueCreatedEvent    CompanyScopedEvent → execution's OutboxHandler   — C4
    └── (no scheduler)
```

`TrackerClient` is `WebhookPoster`'s sibling and **its class header is required reading before
writing this one**: the timeouts are the reason it is a bean. The difference to state in the new
header is that this call runs on a *request* thread rather than the outbox dispatcher's — so a hung
tracker is a hung page, and the read timeout is the whole defence.

---

## Storage

### `V72` — `tracker_binding` (non-secret) and `tracker_identity` (the credential)

**Two tables where revision 2 had one, and the split is the whole point of D2.** A binding says
*which* tracker project this TCMS project maps to and is configured once by a Test Lead. An identity
says *who may talk to that tracker and as whom*, and is configured by each person for themselves.
They have different owners, different permissions, different lifecycles and different secrecy.

**`tracker_binding`** — configured under `integration:manage`, holds **no credential at all**:

| Column | Notes |
|---|---|
| `id`, `company_id NOT NULL`, `project_id NOT NULL` | `company_id` leads every index — ERD §0.1, `SchemaConventionTest` |
| `provider TEXT NOT NULL` | `CHECK (provider IN ('JIRA_CLOUD','JIRA_DC','AZURE_DEVOPS'))` and **not updatable**. Constrained here and *not* on `defect_link`, and the difference is real: this column selects an adapter that must exist, `defect_link.provider` records what some adapter said |
| `base_url TEXT NOT NULL` | `https://` only, enforced at the write **and** by a `CHECK` |
| `external_project TEXT NOT NULL` | Jira project key, or ADO `org/teamProject` |
| `config JSONB NOT NULL` | issue type, default area path, the JQL/WIQL scope clause |
| `is_default BOOLEAN NOT NULL` | partial unique index `(company_id, project_id) WHERE is_default` |
| `enabled`, `created_at`, `updated_at`, `lock_version` | a real `@Version`; the controller takes `If-Match` |

Unique: `(company_id, project_id, provider, base_url, external_project)`.
**Gone from revision 2:** `secret`, `secret_hint`, `account_id`, `expires_at`, `last_error`,
`last_checked_at`, `mode`, `user_id`. All of them moved to the row below, which is where they were
always about a *credential* rather than about a *mapping*.

**`tracker_identity`** — one row per person per tracker host:

| Column | Notes |
|---|---|
| `id`, `company_id NOT NULL`, `user_id NOT NULL` | Company-owned, per §0.1. **Cost stated:** a person who is a member of two companies both bound to the same Jira site connects twice. That is the isolation boundary doing its job — a credential row readable across companies would be the exact thing `company_id` exists to prevent — and it is a real papercut, not a design we are pretending has none |
| `provider`, `base_url` | Keyed to the **host**, not the binding, so one connect serves every project in this company bound to that site. Unique `(company_id, user_id, provider, base_url)` |
| `auth_method TEXT NOT NULL` | `CHECK (auth_method = 'PAT')` in `V72` — D3. Relaxing it is the OAuth slice's first line |
| `secret BYTEA NOT NULL`, `secret_hint TEXT` | AES-GCM via `SecretCipher`. The hint is recognition, never a credential |
| `account_id TEXT NULL` | Jira Cloud's Basic auth needs the user's email beside the token |
| `refresh_token BYTEA NULL`, `scopes TEXT NULL` | Present and always NULL under `PAT`. There for OAuth, per D3's argument that a second `auth_method` must not be a table redesign |
| `expires_at TIMESTAMPTZ NULL` | **Not optional metadata.** Jira Cloud enforces a maximum one-year token life, so every Jira Cloud row here has a death date by construction |
| `last_error TEXT`, `last_checked_at` | The ERD's sentence, kept verbatim and now per-person: *"an expired Jira token that silently stops creating defects — while runs keep passing and failing — is the realistic failure and is invisible otherwise"* |
| `enabled`, `created_at`, `updated_at`, `lock_version` | |

RLS: both tables join `V1`'s forced-policy list. **`tracker_identity` is additionally gated in the
service layer to `user_id = the caller`** — RLS gives company isolation, not per-person isolation,
and a company admin must not be able to read another person's credential row through any endpoint.
Repository interfaces carry `@Transactional(readOnly = true)` at the **interface**, or every derived
read returns zero rows, fail-closed and silent (`RepositoryTransactionTest` fails the build on one
that is missing it).

`tracker_issue_creation` — the outbound-attempt ledger, written before the call and completed after,
mirroring `notification_delivery`. **Diagnostic, not recovery** (recovery is the outbox handler's
job — C4). It records `user_id` too, because under D2 "who filed this" is a real question and the
tracker's own answer is only visible to people who can read that tracker.

### `V73` — `defect_link.provider` gets a truthful writer, and the other two columns do not

**Comment-only, and half the size revision 2 planned.** No `CHECK` on `provider` (D9). The change:
`provider`'s column comment stops saying "the pre-M4 writer states EXTERNAL" and names its new
writer — the binding the server actually called, through the caller's own credential.

**`status_cache` and `synced_at` keep their `V62` comments unchanged, and `V73` says so in its
header** (D14). That is the correction to revisions 1–3, which all framed `V73` as the migration
where those columns "start telling the truth". They do not. There is no poller, and even if there
were, a status fetched with one person's credential written into a column three screens render to
everybody is the leak D15 exists to prevent. `V62`'s position is not amended; it is confirmed by a
second, independent argument, and that is what the header records.

The Java half is where `V73` lives: `DefectLinkInput` gains an optional `bindingId`,
`DefectLinkService` resolves it and states the provider, and **`DefectLink` still does not map
`statusCache` or `syncedAt`** — `DefectLink.java:33-38` explains why the columns are unmapped rather
than mapped-and-null, and that reasoning is untouched by this plan.

### `V74` — `EXTERNAL_ISSUE`, its mirror, and the predicate A1 requires

Five objects across three code sites:

1. **`doc_link`** — `target_kind` CHECK extended, **three** new columns and
   `ck_doc_link_target` gains a fourth arm:

   | Column | Why it is there |
   |---|---|
   | `external_provider` | Which adapter reads this row. Free text, matching `defect_link.provider`'s reasoning (D9) — a fourth tracker must not be a migration |
   | `external_issue_id` | **The identity** (D17). Jira's numeric issue id, ADO's work-item id. Immutable across a project move on both |
   | `external_host` | The tracker site this id belongs to. **An id is only unique within a host**, so identity is `(provider, host, id)` and not `id` alone |

   `ck_doc_link_target`'s new arm:
   `(target_kind = 'EXTERNAL_ISSUE' AND external_issue_id IS NOT NULL AND external_host IS NOT NULL AND external_url IS NOT NULL)`.

   **There is no `external_issue_key` column** (D17), **no title column and no status column**
   (D5/D15). The migration comment must say all three, and say why they are different refusals: the
   key is *absent because it moves*, the title and status are *absent because they were fetched with
   one person's credential*. A later reader adding any of them will think they are adding a cache.

   **There is no `binding_id`** either, and that is D18: a foreign key from `documentation`'s table
   into `integration`'s would be a cross-module edge the architecture forbids, and it would make a
   binding's deletion cascade into somebody's traceability record. `external_host` is what replaces
   it — the reader resolves a binding by matching `(provider, host)` against the project's bindings,
   finds none after a deletion, and renders the degraded chip. **The host is stored, never parsed out
   of `external_url`**, for the same reason the label is not.
2. **`case_doc_link`** — the mirror gains the same three columns, and
   `ck_case_doc_link_target` (`V47`) is extended and **stays closed**. Its own comment says why it is
   closed: *"a fourth value arriving in this table is a defect in a statement that copies what it
   does not interpret, never a new capability."* Here it **is** a new capability, so the comment is
   amended in place rather than left to read as a contradiction.
3. **`DocumentationFacts.java:203-211`** — the refresh's second `SELECT` is
   `WHERE l.target_kind <> 'DOC_BLOCK'`, so it will copy `EXTERNAL_ISSUE` rows **the moment they
   exist, with no code change**, and then violate (2) until (2) lands. It must also carry the three
   new columns, or the mirror loses the provider, host and id the case's Links panel needs to render
   even a degraded chip.
4. **`SpecLinkage`** — **the A1 clause.** `UNLINKED` is a `count(*)` over the mirror's rows in a
   lateral aggregate with **no `target_kind` predicate**, so an external-issue link takes a case out
   of `UNLINKED` by default — the opposite of A1. The count gains
   `AND target_kind <> 'EXTERNAL_ISSUE'`, and the class javadoc's *"An external target clears one
   signal and can never raise the other"* section is amended in place, struck through: it argues the
   old policy for `EXTERNAL_GIT` and `EXTERNAL_URL` and must not read as covering the third kind.
5. **`stale`** — `false` for `EXTERNAL_ISSUE`, and the reason is **not** `V47 §3`'s. `V47` argues
   `false` is honest for `EXTERNAL_GIT` because the link is pinned to a commit sha, so the lines
   cannot change under it. An issue has no pin. Here `false` is honest for a different reason: under
   D13 the row carries no derived meaning at all, so there is nothing for a freshness marker to be
   about. The column comment must say **which** reason applies to which kind, because one sentence
   covering both would be wrong about one of them.

**Why this stays in `doc_link`, restated under A1.** An `EXTERNAL_ISSUE` row is now the only
`doc_link` kind inert to every derived fact — it is an annotation, not a traceability edge, which
weakens revision 1's "one traceability edge" argument considerably. It still wins: the case detail's
Links panel reads the **mirror** (`repository` may not import `documentation`, which is why `V46`
built the mirror in the first place), so a separate table would need its own mirror, its own
refresh, its own RLS policy, its own delete-cascade and its own arm in the detail render — to gain
one `WHERE` clause. **The decision is unchanged and the reason is not, and the next reader should
know that.**

`DocLink.targetKind` is already a `String` and not an enum, deliberately — *"the value is the
database's and an enum would be a second declaration of it that a migration could move out from
under"* — so the Java side is one constant. Every `DocLink` column stays `updatable = false`.

---

## The cache is the leak

Revision 2 specified `TrackerCache` as *"Caffeine, per binding, TTL 60s, bounded"*. **Under D2 that
is a cross-user data leak with a sixty-second window**: user A opens a case, the issue is cached
against the binding, user B — same project, no Jira access — opens the same case and is served A's
copy.

This is the single most likely defect in the whole plan, because a per-binding cache is the obvious
thing to write, it makes every test pass, and it is invisible unless two users with different
tracker entitlements exercise the same case inside the TTL.

Three rules, and each gets a test:

1. **Every cache key includes the requesting `user_id`.** `(userId, bindingId, issueKey)`. Not
   "includes it for correctness" — it is the isolation boundary of this feature.
2. **A failure is cached negatively, per user, briefly.** A 403 from the tracker for user B must not
   become a retry storm, and must not be shared with user A, for whom it is not true.
3. **Nothing tracker-shaped goes in a shared store.** No Redis, no second-level Hibernate cache, no
   `@Cacheable` on a method without the user in its key. The cache is in-process and bounded, and if
   this instance ever grows a shared cache the key rule travels with it.

The regression test is two users, one issue, one TTL window, asserting that B gets *Preview
unavailable* after A got a title.

---

## The creation seam, end to end

```
click "File defect"                        (only rendered if the viewer has a connected identity)
   │
   ├─(1) POST /projects/{p}/tracker/issues          integration   Idempotency-Key
   │        creates in Jira/ADO AS THE CALLER, writes tracker_issue_creation,
   │        records TrackerIssueCreatedEvent          → returns {key, url, provider, bindingId}
   │
   ├─(2) POST /executions/{e}/defects                execution    {externalKey, url, bindingId}
   │        writes defect_link, provider from the binding          → row exists NOW
   │
   └─(async) outbox tick → execution's OutboxHandler
            writes defect_link with the SAME four index columns    → collides, no twin
```

Four properties, each load-bearing:

1. **The reporter is the person.** (1) uses the caller's `tracker_identity`, so Jira's `reporter` and
   ADO's `System.CreatedBy` are the human who saw the failure. This is the coordinator's correction,
   adopted: it needs no service credential, and it is better product and better audit than a bot
   filing every defect in the instance.
2. **`Idempotency-Key` on (1)**, matching `V17`/`V42`/`V59`/`V66`. A double-click files one issue.
   This is the failure people actually hit.
3. **`bindingId` on (2)** — and the collision risk **survives the per-user inversion unchanged**,
   which the coordinator asked to be checked. `V62`'s unique index is
   `(company_id, execution_id, provider, external_key) WHERE execution_id IS NOT NULL` — **`provider`
   is in the key**. If (2) omits `bindingId`, its row says `EXTERNAL`, the async handler's row says
   `JIRA_CLOUD`, the index does not collide, and the run screen shows the same defect twice, both
   rows looking correct. Nothing about the credential model touches this.
4. **A crash between (1) and (2) self-heals** at the next outbox tick. The backstop also survives the
   inversion, and I checked why: the handler writes a row from the *event payload* and needs no
   credential at all, so it works even if the user's token is revoked one second after the issue was
   filed.

**What this still does not give you:** an issue created in the tracker for an execution the event
cannot name — a payload defect, a dead-lettered event. `OutboxStatus.DEAD` is where that lands,
visibly, and `tracker_issue_creation` is how you find the orphan. Stated rather than solved.

---

## Calling somebody else's server

`base_url` is typed by an operator, so the server connects wherever it is told — and under D2 it
carries **every connected user's** credential when it goes there. `OutboundUrlGuard`, in
`integration`, used by `TrackerClient` (enforced) and `WebhookPoster` (warn, then enforce — C5):

- **`https` only**, at the write and in the `CHECK`.
- **Private, loopback, link-local and unique-local destinations refused after DNS resolution**, not
  before — a hostname resolving to `169.254.169.254` is the attack, and a string check does not see
  it.
- **Redirects not followed.** A 3xx from a tracker is a misconfiguration, and following one
  relocates the credential to whatever the redirect names.
- **The guard runs against the binding's `base_url` at write time *and* at call time.** A binding
  edited after people have connected must not be able to redirect their tokens; changing `base_url`
  on a binding that has connected identities is refused, and the operator deletes and recreates it —
  which invalidates the identities, which is the correct and visible outcome.
- **Response body capped** (1 MiB) and **timeouts** — connect 5s, read 10s. There is no poller, so
  there is no second budget.
- The credential is never logged, never in a `Problem` detail, never in the audit event. Extend the
  existing `SecretRedaction` / `CredentialProtectionTest` pair; do not re-invent it.
- `tcms.integration.outbound.allow-private-hosts` defaults **false**; a self-hosted Jira DC on a
  private LAN is exactly who sets it, and setting it must be an operator's sentence. `${VAR}` with
  **no colon** — the working agreement's paragraph exists because that mistake shipped once, and
  `ConfigConventionTest` reads the text.

---

## Phases

Four, not five — D8 deletes revision 2's Phase 4. Each ends at a gate on `shanks` and a commit.
**`~/bin/tcms-remote`, not local** — standing directive. Read `docs/specs/shanks-runner-details.md`
first (the fd-limit false-red).

### Phase 0 — the fake tracker and the guard, before the feature they prove

Per the working agreement, *build the fault switch before the feature it proves*.

- `TrackerPort` + `FakeTrackerAdapter` behind a Spring profile `tracker-fake`, **with a per-user
  entitlement map** so the two-user leak test of *The cache is the leak* can be written before the
  cache exists. A fake that answers the same thing to everybody cannot prove D15.
- Dev-only `tcmsFaults.tracker('slow' | '401' | '403' | 'down' | 'drift')` in
  `frontend/src/lib/devFaults.ts` — `403` is its own arm because *no access* and *bad token* render
  identically (D16) and must still be separately drivable. **Both guards a literal
  `import.meta.env.DEV`**; routing that check through an imported constant ships it.
- `OutboundUrlGuard` and its table-driven refusal test, written **before** any adapter.
- Test transport: **`MockRestServiceServer.bindTo(RestClient.Builder)`**. No new dependency — there
  is no WireMock or MockWebServer in `backend/build.gradle.kts` and none is needed.

**Gate:** backend build. No contract change, no migration.

### Phase 1 — the binding, the connection, and their health

`V72`, both tables.

- **Binding CRUD** under `/api/v1/projects/{projectId}/tracker/bindings`, gated `integration:manage`
  (IA · CA · TL — roles spec `:255`), `If-Match` on update. Non-secret, so the dialog is an ordinary
  form.
- **Connect / disconnect** under `/api/v1/me/tracker-identities`, gated on **being the caller** and
  nothing else. The page is `TrackerConnectionsPage.tsx` under `frontend/src/features/account/`, not
  under `settings/` — this is a personal credential, and putting it on the project settings page
  would tell people it is a project-level thing.
- The connect form uses `NotificationChannelInput`'s **secret-set-separately** shape
  (null-means-unchanged). A form that submits every field blanks the credential of every connection
  anybody renamed — *"a defect with no error message"*, in that record's own words.
- `POST …/tracker-identities/{id}/test` calls the tracker once and writes `last_error` /
  `last_checked_at`. The connections page shows, per row: the host, the `secret_hint`, **the expiry
  date** and the last error. Jira Cloud's mandatory one-year expiry is why the expiry column is on
  the first screen rather than in a detail drawer.
- **The connected-members list (D19).** On the project settings page beside the bindings: every
  project member, and per bound host one of `Connected` · `Needs attention` · `Not connected`. The
  field restrictions in *Who has connected* are part of the decision — a test asserts the response
  body cannot carry a hint, an account id, an expiry, a scope list or an error string, because the
  natural implementation is to reuse the identity view and that view has all five.
- Also here: `WebhookPoster` adopts `OutboundUrlGuard` in **warn** mode, with a channel-health
  warning on the notification settings page. Enforcement on existing channels is its own later
  commit — C5.

**Gate:** backend build; frontend build + lint + `test:run`; both schema copies regenerated.

### Phase 2 — reading stories, as the reader

`GET …/tracker/issues?q=` (typeahead) and `GET …/tracker/issues/{key}`. Both resolve the caller's
`tracker_identity` for the binding's host; **no identity ⇒ `200` with an "unavailable" body, not a
`401`** — the viewer is authenticated to TCMS perfectly well, and a `401` would trip the SPA's
session-recovery path (`app/queryClient.ts`, `tcmsFaults.expire()`'s whole subject) for a condition
that has nothing to do with their TCMS session. That is a specific, easy defect and this line is
here to prevent it.

`TrackerCache` per *The cache is the leak*. `IssueChip` in the shared layer, not the page, with the
two rendered states of *The identifier* (D16/D17): the **live** key + title + status category for a
connected, entitled viewer, and `<Provider> issue <id> ↗` for everyone else — plus, for the viewer's
own account only, a call to action. Status → colour goes through `lib/labels.ts` with
`satisfies Record<Union, string>` on the **category** (`TODO · IN_PROGRESS · DONE`), never on the
tracker's free-text status names, which are per-project and unbounded.

**No component may derive a label from the href** (D17). That is one lint-shaped rule and one test:
a chip rendered from a row whose URL says `PAY-991` and whose id says `10042`, with the tracker
unreachable, must render `10042` and never `PAY-991`.

**Failure is a first-class state here.** A tracker that is down, a token that expired and an issue
the viewer cannot see must all render the case, the run and the panel — degraded, link still
clickable — never an error boundary. That is `DataRegion`'s matrix and this is a region, not a
route.

**Gate:** backend build; frontend build + lint + `test:run`.

### Phase 3 — linking, and filing as yourself

- `POST …/cases/{caseId}/issue-links` (`case:edit`) → `doc_link` `EXTERNAL_ISSUE`, `V74` including
  the mirror, the refresh **and the `SpecLinkage` predicate** (D11/D13). Shown on the case's Links
  panel.
- `POST …/tracker/issues` (`defect:create` — IA · CA · TL · TE, roles spec `:205`) — one-click
  file-a-defect from a failed step, pre-filled from the execution: case key, step, verdict, run,
  environment, a permalink back. Filed **as the caller**. Then (2), then the outbox backstop.
  `DefectPanel.tsx` gains the button, **frozen on a closed run** exactly as its `FROZEN_REASON`
  already does, and **hidden-with-a-reason for an unconnected viewer** rather than shown-and-failing.
- `V73`: `provider` says what was actually called; the other two columns keep their `V62` comments.

**Gate:** full backend suite on `shanks`, frontend `test:run`, packages build. Contract change →
**both** generated copies in the same commit.

### ~~Phase 4 — sync-back~~

**Deleted.** No poller, no `TrackerStatusScheduler`, no company sweep, no rate-limit budget, no
`status_cache` writer. Out of scope by product decision (D8/D14). The freshness question does not
arise, because nothing is stored to become stale.

---

## Contract

`docs/specs/openapi.yaml`, then `npm run gen:api` in **both** `frontend/` and `packages/client/`.
Every new schema gets its `required` array **including on inline nested objects** — `required` does
not descend, and without it every generated property is optional.

| Method | Path | Gate |
|---|---|---|
| `GET · POST` | `/projects/{projectId}/tracker/bindings` | `integration:manage` |
| `GET` | `/projects/{projectId}/tracker/connected-members` | `integration:manage` — D19 |
| `GET · PATCH · DELETE` | `/projects/{projectId}/tracker/bindings/{bindingId}` | `integration:manage` |
| `GET · POST` | `/me/tracker-identities` | **the caller, for the caller** |
| `DELETE` | `/me/tracker-identities/{identityId}` | the caller, for the caller |
| `POST` | `/me/tracker-identities/{identityId}/test` | the caller, for the caller |
| `GET` | `/projects/{projectId}/tracker/issues?q=&bindingId=` | `project:read` **+ the caller's own identity** |
| `GET` | `/projects/{projectId}/tracker/issues/{key}` | `project:read` **+ the caller's own identity** |
| `POST` | `/projects/{projectId}/tracker/issues` | `defect:create` **+ the caller's own identity** |
| `GET · POST` | `/projects/{projectId}/cases/{caseId}/issue-links` | `case:read` · `case:edit` |
| `DELETE` | `/projects/{projectId}/cases/{caseId}/issue-links/{linkId}` | `case:edit` |

**The catalogue stays at 55 keys** (D12), so `permission-matrix-summary.md`, `README.md`,
`lib/permissions.ts` and `matrixCopies.test.ts` are untouched. `project:read` gates *whether a link
is visible*; the caller's own credential gates *whether its content is*. That two-gate shape is A2
and A3 side by side, and it is why no 56th key is earned: there is nothing left for one to decide.
**This closes 005's Q6** (grounded 2026-08-28): `doc:review` (005 S9b) is the 56th catalogue key,
uncontested, and this plan adds none.

`DefectLinkInput` gains optional `bindingId`. `DefectLinkView` is **unchanged**, including
`statusCache`, which stays declared, served, and null (D14).

**MCP / CLI:** unaffected. A `tcms_link_issue` tool is a follow-on and is **not obviously wanted** —
an agent token has no human to consent as, so under D2 it has no tracker identity, which is a
question for that surface's own slice rather than a gap here.
`docs/specs/2026-08-08-mcp-tool-and-command-surface.md` is updated only if it ships.

---

## Tests

- **The two-user leak test** (D15). Two users with different tracker entitlements, one issue, one
  cache TTL window: B must get *Preview unavailable* after A got a title. If one test from this plan
  survives a rewrite, it is this one.
- **The A1 predicate test** (D13). A case whose only link is `EXTERNAL_ISSUE` stays `UNLINKED`. It
  must fail loudly if `SpecLinkage`'s count is later "tidied" back to a bare `count(*)` — assert the
  signal, not the SQL.
- **The mirror test** (C3). Insert an `EXTERNAL_ISSUE` `doc_link`, run the health refresh, assert the
  `case_doc_link` row exists with provider and key. Without `V74`'s mirror half this is what fails,
  which is the point of writing it.
- **The convergence test** (C4). Create an issue, let the SPA path write the link, run the outbox
  handler over the same event, assert **one** row. Then repeat with `bindingId` omitted and assert
  the documented *second* row — so the reason the field is required is pinned by a test rather than
  by a paragraph.
- **Identity isolation.** A company admin calling `/me/tracker-identities` sees their own rows and
  nobody else's, and no endpoint anywhere returns another person's `secret_hint`. RLS gives company
  isolation, not per-person isolation; this is the service-layer half.
- **The D19 field-restriction test.** `/tracker/connected-members` returns a name and a state and
  **nothing else** — asserted as a closed set of response keys, not as "does not contain the secret".
  The natural implementation reuses the identity view, which carries five fields this endpoint must
  not, so the test has to close the set rather than name the leaks it thought of.
- **The id-only tests (D17).** (a) A row whose `external_url` names one key and whose
  `external_issue_id` names a different issue renders the **id**, never the URL's key, with the
  tracker unreachable. (b) A connected viewer's chip shows the key returned by the fetch, so a moved
  issue displays its *new* key with no write to the row. (c) Identity is `(provider, host, id)`: two
  bindings to different hosts holding the same numeric id are two different issues and must not
  collide in the cache or in the link table.
- **The D18 test.** Delete a binding; the case's links still render, still have a working href, and
  the chip is degraded. Nothing cascades.
- **`SchemaConventionTest`** covers both new tables for free and fails them if `company_id` is
  missing or not leading. That is the intent; do not special-case it.
- **RLS:** `RlsIsolationTest` gains arms for `tracker_binding`, `tracker_identity`,
  `tracker_issue_creation` and `EXTERNAL_ISSUE` links.
- **SSRF:** table-driven refusal over `http://`, `127.0.0.1`, `10.x`, `169.254.169.254`, a hostname
  that *resolves* to a private address, and a 302 to any of them — against the guard, so both callers
  inherit it. Plus: a binding whose `base_url` is edited while identities exist is refused.
- **Credential protection:** extend `CredentialProtectionTest`. No token in a view, an audit event, a
  `Problem`, or a log line — now across many rows rather than one.
- **The 200-not-401 test.** An unconnected viewer reading a case gets a rendered page and an
  "unavailable" chip, not a session-recovery cycle.
- **Degraded UI:** one test per surface per failure mode (down · expired · forbidden · unconnected).
  These must call `drivingAFailedRead()` and say why, or they fail.
- **Contract conformance** is automatic across a `fetch` double — use
  `frontend/src/test/fixtures.ts`, and **anchor the path-keyed mock arms**
  (`/^\/projects\/[^/]+\/tracker\/issues$/`) so `/issues/{key}` is not caught by the prefix.
  `contractBodies.ts`' marked region is byte-identical across both copies — edit one, edit both.
- **Cited files:** every path named in a new comment must exist, or the **packages** job reddens.

**What the gates do not prove.** Nothing here talks to a real Jira or a real ADO. Field names,
pagination shapes, JQL/WIQL dialects, PAT header forms and error bodies are asserted against *our
fixtures*, which tests our parsing and not their API. **Under D2 the untested surface is larger than
it was**, because per-user auth differs per provider in ways a fixture cannot expose: Jira Cloud
Basic-with-email versus Jira DC Bearer, and ADO's org-scoped PAT semantics. The first real connection
is a manual step, and **"built and unverified"** is the honest sentence until one real person has
connected, previewed and filed once against each of the three.

**And what CI does not run.** Per `003`'s table: a push to master compiles the backend and runs
frontend *smoke* only. Every backend test above, and every test under `src/features/**`, lands in a
lane no push executes. After each phase: `gh workflow run tests.yml`, and read it before calling the
phase done.

---

## Risks

| Risk | Handling |
|---|---|
| **A per-binding cache serves one user's issue to another** (D15). | Cache key includes `user_id`; the two-user leak test. **The most likely defect in this plan** — it is the obvious implementation, it passes every single-user test, and it is invisible without two differently-entitled users inside one TTL. |
| **`SpecLinkage`'s predicate gets tidied away** and external links silently start clearing `UNLINKED` again, contradicting A1. | The A1 predicate test asserts the *signal*, and the amended javadoc says which kinds the old paragraph covers. |
| **Two defect rows for one defect** (C4). | `bindingId` on the link call; the convergence test. Both rows look correct, which is why it needs a test rather than a review. |
| **Nobody connects, and the feature looks broken.** | Every dark surface carries a specific call to action, never a generic error (D16). The manual defect form remains the fallback and is what exists today, so the unconnected state is not a regression. |
| **Credentials expire, per person, silently — and for Jira Cloud they are *guaranteed* to.** | `expires_at` on the first screen of the connections page, `last_error`, and a *"reconnect"* CTA on the dark chip. This is the ERD's own stated failure mode, now multiplied by the number of users. |
| **ADO's PAT path narrows underneath us** — global PATs decommission 1 Dec 2026, and tenant policy can block PAT creation outright. | Org-scoped PATs only, a specific message for the policy-blocked case, and Entra OAuth planned as the next slice against an unchanged `tracker_identity`. Stated as a runway, not as a solved problem. |
| **A binding's `base_url` is edited and redirects everyone's tokens.** | Refused while identities exist; delete-and-recreate invalidates them visibly. |
| **Somebody adds a "just show the key" label derived from the stored URL** (D17), and it goes silently wrong after a Jira project move. | Forbidden in the migration comment, in the component, and by a test that pins id-over-URL. It is the single most likely well-meaning regression in this plan, because the URL is right there and the id is not human-readable. |
| **The connected-members list grows into a credential-health screen** (D19). | The response key set is closed by a test. The privacy override was bought at a stated price and the price is the field list. |
| **An unpreviewable list of five links is hard to scan.** | Accepted and stated (D17). It affects only people who have not connected or whose tracker is down; the id disambiguates where a bare "Issue" would not. |
| **Retrofitting the URL guard breaks a working Slack channel** (C5). | Warn-then-enforce; enforcement is its own commit after an operator has seen the warning. |
| **ADO's API surface is genuinely different** — WIQL returns ids then a second batched fetch; creation is a JSON-Patch document. | Priced into the adapter, not the port. `TrackerPort` must not leak Jira's shape; that is what keeps GitHub/GitLab a later adapter rather than a later redesign. |

---

## Open questions — none

All ten questions this plan has raised across five revisions are closed. Recorded here so a reader
can see that the emptiness is a result rather than an omission:

| Raised | Question | Closed by |
|---|---|---|
| rev 1 | Does an external story count as coverage? | **A1** — no derived meaning of any kind (D13) |
| rev 2 | Does an external link clear `UNLINKED`? | **A1** — no, and it costs an explicit predicate (D13) |
| rev 2 | Which permission gates reading another system's issues? | **A2 + A3** — project assignment, then the caller's own credential. Catalogue stays at 55 (D12) |
| rev 2 | Key, id, or both? | **A4** — id only (D17) |
| rev 2 | What happens to links when a binding is deleted? | **A5** — they survive and degrade (D18) |
| rev 2 | GitHub and GitLab? | **A7** — out, cheap follow-on (D20) |
| rev 3 | Link-only, or link + defect creation + sync-back? | Product scope: link + preview + creation, **no sync-back** (D8/D14) |
| rev 4 | PAT or OAuth, and in which order? | Decided in *Which mechanism* — PAT first, all three providers (D3) |
| rev 4 | Does ADO ship in slice 1? | Decided — yes, on organization-scoped PATs, with the runway stated |
| rev 4 | May a Test Lead see who has connected? | **A6** — yes, by name, with a closed field list (D19) |

### What revision 5 discovered while closing them

Two things, both folded into the decisions above rather than left as new questions:

1. **Jira and ADO are asymmetric on URL derivability**, not on fetchability. Both fetch from the id;
   only ADO can build a live URL from it. That asymmetry is absorbed by `external_url` already being
   required, and it is why the stored URL is defined as a *pointer* rather than as data.
2. **Id-only forces a third column, not a swap.** `external_host` has to exist, because a tracker id
   is unique within a site and not globally — and once it exists it also answers D18, since it is
   what lets a link outlive the binding it was made through without a cross-module foreign key. The
   two answers turned out to need the same column, which is the only reason `V74` did not grow twice.

---

## Deferred, deliberately

- **Status sync-back.** Out of scope by product decision. `status_cache` and `synced_at` remain
  writerless and `V62`'s argument is confirmed rather than amended (D14).
- **OAuth 3LO / Entra ID.** Slice 2. `tracker_identity` carries `auth_method`, `refresh_token`,
  `expires_at` and `scopes` from `V72` so it is a relaxed `CHECK` and a consent controller, not a
  table redesign (D3). It is also the only path for ADO once PAT policy tightens.
- **Inbound webhooks.** Nothing to deliver to, with no sync-back.
- **Two-way sync.** TCMS is not a defect tracker; it links out.
- **Bulk import of stories as doc blocks.** That is the `.feature`-import-shaped question and it is
  `features.md`'s other Phase 2 line.
- **A shared credential table.** The ERD's `integration_credential` is **superseded, not pending** —
  `V54` shipped the Slack/Teams half without it, storing `secret` + `secret_hint` inline, and this
  plan follows that shape. The ERD section gains a struck-through line and *"→ superseded by `V54`
  and `004`: the credential lives on the row that uses it"*, per the working agreement. Three of its
  fields are carried and its sentence is kept verbatim: `expires_at`, `last_error`,
  `last_checked_at`.
- **GitHub and GitLab** (D20). One adapter and one `provider` value each, **no migration and no
  contract change** — and per-user PATs suit both better than they suit ADO, since neither vendor is
  retiring them. The cheapness is a property this plan paid for: no `CHECK` on
  `defect_link.provider`, `tracker_identity` keyed by host, and a `TrackerPort` that returns
  `TrackerIssue` rather than a Jira DTO.
- **An agent/MCP tracker identity.** An `Agent` token has no human to consent as, so it has no
  identity under D2. That is a question for the MCP surface's own slice.

---

## Sequencing

> **Build order superseded 2026-08-28** by [`006-build-batches.md`](006-build-batches.md): one operator, sequential batches by agent and by files touched, one contract wave, gates run once at the end. The table below remains the record of *why* the pieces depend on each other.

**Sequential.** Phase 0 → 1 → 2 → 3. ~~Nothing here runs in parallel with `003`, which holds
`openapi.yaml` and `V71`.~~ → **grounded 2026-08-28: `openapi.yaml` is held first by `005` (S1 + S3),
then by `003` Phase 2, and this plan takes it after both.** Three reasons, none of them courtesy:
`V72` must land after 005's `V71` or it is refused on every migrated database; the contract is
regenerated from the neighbour's landed `openapi.yaml`, never merged (005 §8.5), so it must be
*landed* and green, not mid-slice; and Phase 3 here links from `CaseDetailPage` and writes
`case_doc_link`, the case surfaces 003 Phase 2 is reshaping. The full order, with each step's reason,
is the table in 003's *Sequencing*. Phase 0 (the fake tracker and the guard) touches neither the
contract nor a migration and may start earlier.

Per `docs/specs/parallel-work-rules.md` and the standing rule: **one subagent at a time, and ask
before a second.** Phases 1, 3 hold `openapi.yaml` and a Flyway number, so no second lane may touch
the contract or a migration number while they run. `out-of-order` is unset and Testcontainers cannot
see a numbering conflict, which is why `V72`–`V74` are claimed at the top of this document rather
than at the moment each file is written.

**Commit at the end of each phase**, staging only that phase's files, on the current branch.

---

## What this plan does not claim

Nothing is built. No adapter has spoken to a real Jira or a real Azure DevOps, and no gate in this
repository can make it. Three of this plan's decisions rest on behaviour asserted only against
fixtures — Jira Cloud's Basic-with-email PAT form, Jira DC's Bearer PAT form, and ADO's org-scoped
PAT semantics — and the honest phrase for everything phases 2 and 3 produce, until one real person
has connected against each provider, is **built and unverified**.

Two of the provider facts this plan's *ordering* rests on are moving targets on somebody else's
schedule: ADO global PATs decommission on 1 December 2026, and ADO's PAT-blocking tenant policy is
available now. If either lands harder than expected before phase 3 ships, the ADO adapter is the
part of this plan that goes stale first, and the Entra slice is the answer rather than a patch.

**Nothing here is waiting on an answer.** Every question raised across five revisions is closed, so
what is left between this document and a working feature is execution and the manual verification
above — not another round of decisions. The two things most likely to be got wrong are both
well-meaning and both have a test named after them: a cache keyed by binding rather than by user,
and a label derived from the stored URL rather than from the id.
